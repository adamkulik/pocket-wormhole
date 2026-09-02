package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.Hotkey
import xyz.znix.xftl.game.SystemPowerButton
import xyz.znix.xftl.game.VanillaHotkeys
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.replaceArg
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sys.Input
import kotlin.math.max
import kotlin.math.min

class Cloaking(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.CLOAKING
    override val insertButtonSpace: Boolean get() = true
    override val isPowerLocked: Boolean get() = super.isPowerLocked || active
    override val hasWhiteLockingBox: Boolean get() = active

    /**
     * The time remaining on the cloak, or null if the cloak is inactive.
     */
    var timeRemaining: Float? = null

    val active: Boolean get() = timeRemaining != null

    private val cloakSound by onInit { it.sounds.getSample("cloak") }
    private val unCloakSound by onInit { it.sounds.getSample("decloak") }

    private var shouldPlayCloakSound = false

    // Note we clamp it to a minimum of one power, to avoid divide-by-zero errors
    // in unexpected conditions.
    val duration: Float get() = max(powerSelected, 1) * TIME_PER_POWER

    // Used for managing the fade-in and fade-out animations
    private var animationTimer: Float = 0f

    /**
     * Get the strength of the cloak hull image from 0-1.
     */
    val cloakFade: Float
        get() {
            // The part of the timer remaining, from 0-1
            val remaining = animationTimer / FADE_TIMER

            if (active) {
                return 1 - remaining
            } else {
                return remaining
            }
        }

    override fun makeExtraButtons(powerPos: IPoint): List<Button> {
        // If the system is unpowered, it shows the disabled level-1 image
        val power = max(powerSelected, 1)

        return listOf(CloakButton(power, powerPos))
    }

    override fun powerStateChanged() {
        super.powerStateChanged()

        // We have to update the UI, since the button height changes
        // Exclude the enemy AI from this, as they can fiddle with
        // the power multiple times a frame.
        if (ship == ship.sys.player) {
            // Null when a new game starts
            ship.sys.shipUI?.updateButtons()
        }
    }

    fun activateCloak() {
        // Stop the clock from being activated when it's on cooldown
        if (isPowerLocked)
            return

        if (powerSelected == 0)
            return

        // Block cloaking while we're already cloaked
        if (active)
            return

        // Hacking blocks the cloak
        if (isHackActive)
            return

        this@Cloaking.timeRemaining = duration
        animationTimer = FADE_TIMER

        // The cloak sound only plays when the user unpauses
        shouldPlayCloakSound = true
    }

    override fun hotkeyPressed(key: Hotkey) {
        super.hotkeyPressed(key)

        if (key.id == VanillaHotkeys.SYS_ACTION_CLOAKING)
            activateCloak()
    }

    override fun update(dt: Float) {
        super.update(dt)

        if (shouldPlayCloakSound) {
            shouldPlayCloakSound = false
            cloakSound.play()
        }

        animationTimer = max(0f, animationTimer - dt)

        if (timeRemaining != null) {
            timeRemaining = timeRemaining!! - dt

            // Hacking ends a cloak immediately
            if (isHackActive)
                timeRemaining = 0f

            // If the system is damaged, that caps the amount of remaining cloak time
            // Note that this means some damage won't have an effect - if
            // a power bar is damaged after it's contribution to the cloak is
            // over, it won't have any effect.
            timeRemaining = min(timeRemaining!!, duration)

            // We have to check for powerSelected here, since the above
            // won't work if the system is fully broken since duration is
            // always at least 5 seconds.
            if (timeRemaining!! <= 0 || powerSelected == 0) {
                timeRemaining = null

                // Don't apply ion damage - this means we hold onto
                // the power until the cooldown is finished (or
                // the system is damaged)
                ionTimer += COOLDOWN

                animationTimer = FADE_TIMER

                unCloakSound.play()
            }
        }
    }

    fun weaponFired() {
        if (ship.hasAugment(AugmentBlueprint.STEALTH_WEAPONS))
            return

        // Cut 20% from our cloak
        val previous = timeRemaining ?: return
        timeRemaining = previous - duration * 0.20f
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        SaveUtil.addTagFloat(elem, "timeRemaining", timeRemaining)
        SaveUtil.addTagFloat(elem, "animationTimer", animationTimer)
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        timeRemaining = SaveUtil.getTagFloatOrNull(elem, "timeRemaining")
        animationTimer = SaveUtil.getTagFloat(elem, "animationTimer")
    }

    private inner class CloakButton(power: Int, powerPos: IPoint) : SystemPowerButton(ship.sys, power, powerPos) {

        override val timeRemaining: Float? get() = this@Cloaking.timeRemaining
        override val duration: Float get() = this@Cloaking.duration
        override val isOff: Boolean get() = powerSelected == 0 || isPowerLocked || isHackActive

        override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            activateCloak()
        }
    }

    companion object {
        const val COOLDOWN = 20f
        const val TIME_PER_POWER = 5f
        const val FADE_TIMER = 0.5f

        val INFO: SystemInfo = CloakingInfo
    }
}

private object CloakingInfo : SystemInfo("cloaking") {
    override val canBeManned: Boolean get() = false

    override fun create(blueprint: SystemBlueprint) = Cloaking(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        val time = Cloaking.TIME_PER_POWER * (level + 1)
        return translator["cloak"].replaceArg(time.toInt())
    }
}

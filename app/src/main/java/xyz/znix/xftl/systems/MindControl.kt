package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sys.Input

class MindControl(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.MIND_CONTROL
    override val insertButtonSpace: Boolean get() = true

    /**
     * The time remaining on the cloak, or null if the cloak is inactive.
     */
    var timeRemaining: Float? = null

    val active: Boolean get() = timeRemaining != null

    var controlledCrew: LivingCrew? = null
        private set

    override val isPowerLocked: Boolean get() = super.isPowerLocked || active
    override val hasWhiteLockingBox: Boolean get() = active

    val ready: Boolean get() = powerSelected > 0 && !isPowerLocked && !isHackActive

    private val startSound by onInit { it.sounds.getSample("mindControl") }
    private val endSound by onInit { it.sounds.getSample("mindControlEnd") }

    private var button: MindControlButton? = null

    val duration: Float
        get() {
            return when (powerSelected) {
                // From the wiki
                0 -> 0.001f // Dummy value
                1 -> 14f
                2 -> 20f
                3 -> 28f
                else -> 28f
            }
        }

    override fun update(dt: Float) {
        super.update(dt)

        // TODO hacking

        val oldTime = timeRemaining
        if (oldTime == null || controlledCrew == null) {
            timeRemaining = null
            controlledCrew = null
            return
        }

        checkEnemyGone()
        if (timeRemaining == null) {
            // If checkEnemyGone reset the timer
            return
        }

        // Clamp the timer, so if the system is damaged it cuts down
        // the remaining time.
        val clamped = oldTime.coerceAtMost(duration)

        val newTime = clamped - dt
        if (newTime <= 0) {
            switchToCooldown()
            return
        }

        timeRemaining = newTime
    }

    private fun switchToCooldown() {
        // Only play the ending sound if a crew was mind-controlled.
        // If we counter enemy mind control, this should only play
        // for the enemy ship's system.
        if (controlledCrew != null) {
            endSound.play()
        }

        controlledCrew?.mindControlledBy = null
        timeRemaining = null
        controlledCrew = null

        ionTimer = 20f
    }

    private fun checkEnemyGone() {
        val crew = controlledCrew ?: return

        // If the ship the crew was on has gone, we're probably not
        // in combat and we can reset immediately, without triggering
        // a cooldown.
        // This also avoids playing the end sound effect when the
        // enemy ship is destroyed.
        if (!ship.sys.isShipPresent(crew.room.ship)) {
            controlledCrew = null
            timeRemaining = null
            return
        }

        // If the crew disappears (most notably because they died), then
        // go to cooldown right away.
        if (!crew.room.ship.crew.contains(crew)) {
            switchToCooldown()
            return
        }

        // If the crew is no longer mind-controlled by us, something odd
        // happened - so go onto cooldown.
        if (crew.mindControlledBy != this) {
            switchToCooldown()
            return
        }
    }

    fun isControlling(crew: LivingCrew): Boolean {
        // Check if one of the ships has jumped away, or is no longer
        // hostile towards the other.
        // This doesn't apply to intruders, since they can always
        // be mind-controlled, regardless of what ship they belong to.
        if (ship.sys.getEnemyOf(ship) == null && crew.room.ship != ship)
            return false

        return active && crew == controlledCrew
    }

    override fun makeExtraButtons(powerPos: IPoint): List<Button> {
        button = MindControlButton(powerPos)
        return listOf(button!!)
    }

    fun selectRoom(room: Room) {
        if (!ready)
            return

        // Block mind-control on enemy ships with a super shield
        // TODO super shield bypass
        if (room.ship != ship && room.ship.superShield > 0)
            return

        // The player can only target rooms they have visibility onto
        // TODO slug/lifeform scanner exception
        if (ship.isPlayerShip && !room.playerHasVision)
            return

        // If we're attacking a room on the player's ship, look for intruders.
        // Otherwise, look for crewmembers on enemy ships.
        val targetMode = when (room.ship) {
            ship -> AbstractCrew.SlotType.INTRUDER
            else -> AbstractCrew.SlotType.CREW
        }

        val suitableCrew = room.crew.filterIsInstance(LivingCrew::class.java)
            .filter { it.mode == targetMode }
            .filter { !it.isMindControlResistant }
        if (suitableCrew.isEmpty())
            return

        // Note we don't prefer countering mind control for our own
        // crew over controlling enemy crew.
        val crew = suitableCrew.random()

        // Countering mind control immediately puts both systems on cooldown
        val otherMC = crew.mindControlledBy
        if (otherMC != this && otherMC != null) {
            otherMC.switchToCooldown()
            switchToCooldown()
            return
        }

        crew.mindControlledBy = this
        controlledCrew = crew
        timeRemaining = duration

        startSound.play()
    }

    override fun hotkeyPressed(key: Hotkey) {
        super.hotkeyPressed(key)

        if (key.id == VanillaHotkeys.SYS_ACTION_MIND) {
            // This is a bit hacky, but it properly shows the super-shield warning
            button?.click(Input.MOUSE_LEFT_BUTTON)
        }
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        // To avoid serialising a reference to a ship that's gone, check
        // if the enemy has jumped away.
        checkEnemyGone()

        SaveUtil.addTagFloat(elem, "timeRemaining", timeRemaining)

        val crew = controlledCrew
        if (crew != null) {
            val shipWithEnemy = crew.room.ship
            SaveUtil.addRef(elem, "shipWithEnemy", refs, shipWithEnemy)
            SaveUtil.addTagInt(elem, "crewId", shipWithEnemy.crew.indexOf(crew))
        }
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        timeRemaining = SaveUtil.getTagFloatOrNull(elem, "timeRemaining")

        SaveUtil.getOptionalRef(elem, "shipWithEnemy", refs, Ship::class.java) { shipWithEnemy ->
            if (shipWithEnemy == null)
                return@getOptionalRef

            val crewId = SaveUtil.getTagInt(elem, "crewId")
            controlledCrew = shipWithEnemy.crew[crewId] as LivingCrew
            controlledCrew!!.mindControlledBy = this
        }
    }

    companion object {
        val INFO: SystemInfo = MindControlInfo
    }

    // Note the mind control button always uses the two-power height
    private inner class MindControlButton(powerPos: IPoint) : SystemPowerButton(ship.sys, 2, powerPos) {
        override val timeRemaining: Float? get() = this@MindControl.timeRemaining
        override val duration: Float get() = this@MindControl.duration
        override val isOff: Boolean get() = !ready

        private val superShieldWarning = WarningFlasher(
            game, powerPos + ConstPoint(35, -62),
            "warning_super_shield_mind",
            false, colour = GlowColour.WHITE
        )

        override val forceHighlight: Boolean
            get() = game.shipUI.isSelectingMindControlTarget

        public override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            if (disabled)
                return

            // If the enemy ship has a zoltan shield but there are intruders
            // on the player's ship, they can activate mind control and place
            // it on the enemy ship - it just doesn't do anything once un-paused.
            // TODO super shield bypass
            val hasIntruders = ship.intruders.any { (it as? LivingCrew)?.isMindControlResistant == false }
            val enemyShip = game.getEnemyOf(ship)
            if (!hasIntruders && enemyShip != null && enemyShip.superShield > 0) {
                superShieldWarning.startFor(3.5f)
                return
            }

            game.shipUI.mindControlSelected()
        }

        override fun draw(g: Graphics) {
            super.draw(g)
            superShieldWarning.draw(g)
        }
    }
}

private object MindControlInfo : SystemInfo("mind") {
    override val canBeManned: Boolean get() = false

    override fun create(blueprint: SystemBlueprint) = MindControl(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return when (level) {
            0 -> translator["mind_1"]
            1 -> translator["mind_2"]
            2 -> translator["mind_3"]
            else -> "INVALID LEVEL ${level + 1}"
        }
    }
}

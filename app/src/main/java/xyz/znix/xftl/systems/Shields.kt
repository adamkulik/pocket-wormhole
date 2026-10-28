package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.max

class Shields(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.SHIELD

    /**
     * The number of shield bubbles the player has selected: the paired bars
     * the total power level makes up, counting the reactor selection AND any
     * per-room power (eg a Zoltan bar), since both occupy bars of the pair
     * (vanilla: a Zoltan bar + one reactor bar completes a bubble).
     */
    val selectedShieldBars: Int get() = (powerSelected + forcedPower) / 2

    /**
     * The bubbles this system is actually running at: half the supplied
     * power, rounded down (a lone bar runs nothing). During ion storms this
     * drops below [selectedShieldBars], popping the excess bubbles until the
     * storm passes (GitHub issue #95; wiki: "Power will be removed
     * automatically from systems").
     */
    val suppliedShieldBars: Int get() = powerSupplied / 2

    var activeShields: Int = 0
        set(value) {
            val old = field
            field = value.coerceIn(0..selectedShieldBars)

            if (value < old) {
                shieldsDownSound.play()
            }
        }

    var rechargeTimer: Float = 0f
        private set

    // Used for hacking - the charge bar runs backwards
    // and then drops a shield level when it hits zero.
    var discharging: Boolean = false
        private set

    val rechargeDelay: Float get() = 2f

    val hackDrainTime: Float get() = 2f

    private val shieldsUpSound by onInit { it.sounds.getSample("shieldsUp") }
    private val shieldsDownSound by onInit { it.sounds.getSample("shieldsDown") }

    override fun update(dt: Float) {
        super.update(dt)

        if (isHackActive) {
            updateHacked(dt)
            return
        }

        // Check if our power has been reduced - damage, or an ion storm
        // cutting the reactor's output (GitHub issue #95).
        if (activeShields > selectedShieldBars) {
            activeShields = selectedShieldBars
        }
        if (activeShields > suppliedShieldBars) {
            activeShields = suppliedShieldBars
        }

        if (activeShields >= minOf(selectedShieldBars, suppliedShieldBars)) {
            rechargeTimer = 0f
            return
        }

        // Leave discharge mode immediately without
        // resetting the progress or anything like that.
        if (discharging) {
            discharging = false

            // If we were very, very close to discharging
            // this shield, assume it was an off-by-one-frame
            // error. Without this, a level-1 hack can't
            // take down two shields.
            if (rechargeTimer < dt * 2 + 0.02) {
                rechargeTimer = 0f
                activeShields--
            }
        }

        // The shield charge booster and manning multiply together, per the wiki.
        val boosterRate = 1f + ship.getAugmentValue(AugmentBlueprint.SHIELD_CHARGE_BOOSTER)
        val manningBonus = getSkillLevel(Skill.SHIELDS)?.let { SKILL_BONUSES[it.ordinal] } ?: 0
        val manningRate = 1f + manningBonus / 100f
        val chargeRate = boosterRate * manningRate

        rechargeTimer += dt * chargeRate
        if (rechargeTimer < rechargeDelay)
            return

        if (activeShields == 0) {
            shieldsUpSound.play()
        }

        rechargeTimer = 0f
        activeShields++
    }

    private fun updateHacked(dt: Float) {
        // Note that if we were recharging a shield layer,
        // then we have to undo that progress before we can
        // get started on the first bubble.
        // This happens (which is intended) because rechargeTimer
        // starts at a non-zero value in that case, and we have
        // to drain it before we switch to discharge mode.

        if (activeShields == 0) {
            rechargeTimer = 0f
            return
        }

        rechargeTimer = max(rechargeTimer - dt, 0f)
        if (rechargeTimer <= 0f && activeShields > 0) {
            rechargeTimer = hackDrainTime

            // This implements the above-mentioned system,
            // and is what sets rechargeTimer when we're
            // hacking fully-charged shields.
            if (!discharging) {
                discharging = true
                return
            }

            activeShields--

            // Without this, rechargeTimer would jump back to 2s for
            // one frame, which is very visible on the shield charge bar.
            if (activeShields == 0) {
                rechargeTimer = 0f
            }
        }
    }

    fun popShieldLayer() {
        if (activeShields == 0)
            return

        activeShields--

        // According to the wiki (on 08/06/2023) this skill is granted when
        // the shields are hit, not when they recharge.
        addSkillPoint(Skill.SHIELDS)
    }

    /**
     * Vanilla's ShieldBox + click completes the current bubble - pinned
     * against FTLGame.exe 1.6.14 (the box's click handler sets the click
     * step to 2, or 1 when the system has a Zoltan bar) and by user test
     * (from empty, one click adds two bars). A lone Zoltan bar only needs
     * one more bar, and an odd (half-bubble) system level only has room
     * for one more bar. The click is atomic: if that many bars aren't
     * available, nothing happens and the fail sound plays.
     */
    override fun increasePower() {
        if (isPowerLocked)
            return

        val step = when {
            forcedPower.mod(2) == 1 -> 1
            reactorRoom() - powerSelected >= 2 -> 2
            else -> 1
        }

        val wanted = powerSelected + step
        targetPower = wanted + forcedPower
        if (!setSystemPower(wanted))
            targetPower = powerSelected + forcedPower
    }

    /**
     * Vanilla's ShieldBox - click mirrors the + click (user-pinned in
     * vanilla 1.6.14: minus removes two bars): it removes bars down to the
     * previous bubble boundary - a full pair from an even total, just the
     * lone bar from an odd one. The result clamps at zero, so a pair held
     * up by Zoltan bars merely loses its reactor bars.
     */
    override fun decreasePower() {
        if (isPowerLocked)
            return

        val step = if ((powerSelected + forcedPower).mod(2) == 1) 1 else 2
        val wanted = (powerSelected - step).coerceAtLeast(0)

        targetPower = wanted + forcedPower
        if (!setSystemPower(wanted))
            targetPower = powerSelected + forcedPower
    }

    // A starved shield keeps whole bubbles: a lone reactor bar would run
    // nothing, so it goes back to the reactor (unless a Zoltan bar or an odd
    // system level makes it part of the top bubble, like the - click).
    override fun roundDownStarvedSelection(supplied: Int): Int {
        if ((supplied + forcedPower).mod(2) == 0 || supplied + forcedPower >= reactorRoom() + forcedPower)
            return supplied
        return (supplied - 1).coerceAtLeast(0)
    }

    override fun getPowerBarSpacing(powerLevel: Int): Int {
        // Group the power bars into pairs
        if (powerLevel.mod(2) == 1) {
            return 6
        }
        return 2
    }

    override fun dealDamage(damage: Int, ionDamage: Int) {
        super.dealDamage(damage, ionDamage)

        // If the player shoots a beam from shields to another room, the shields
        // should go down immediately. We can't afford to wait for the next
        // update, in case the frame-time is too high as the user could hit
        // another room on the same frame.
        if (activeShields > selectedShieldBars) {
            activeShields = selectedShieldBars
        }
    }

    override fun drawManningIcon(g: Graphics, x: Int, y: Int) {
        drawManningSkillIcon(x, y, getSkillLevel(Skill.SHIELDS))
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrInt(elem, "active", activeShields)
        SaveUtil.addTagFloat(elem, "rechargeTimer", rechargeTimer, 0f)
        SaveUtil.addTagBoolIfTrue(elem, "discharging", discharging)
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        activeShields = SaveUtil.getAttrInt(elem, "active")
        rechargeTimer = SaveUtil.getOptionalTagFloat(elem, "rechargeTimer") ?: 0f
        discharging = SaveUtil.getOptionalTagBool(elem, "discharging") ?: false
    }

    companion object {
        val INFO: SystemInfo = ShieldsInfo
        val SKILL_BONUSES = listOf(10, 20, 30)
    }
}

private object ShieldsInfo : SystemInfo("shields") {
    override val canBeManned: Boolean get() = true

    override fun create(blueprint: SystemBlueprint) = Shields(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return when (level) {
            0, 2, 4, 6 -> ""
            1 -> translator["shields_0"]
            3 -> translator["shields_1"]
            5 -> translator["shields_2"]
            7 -> translator["shields_3"]
            else -> "INVALID LEVEL ${level + 1}"
        }
    }
}

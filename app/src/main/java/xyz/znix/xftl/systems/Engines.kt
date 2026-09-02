package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.UIUtils
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.replaceArg
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader

class Engines(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.ENGINES

    private val enginesOnSound by onInit { it.sounds.getSample("enginesOn") }
    private val enginesOffSound by onInit { it.sounds.getSample("enginesOff") }

    val evasion: Int
        get() {
            if (powerSelected == 0)
                return 0

            val base = EVASIONS[powerSelected - 1]
            val bonus = getSkillLevel(Skill.ENGINES)?.let { SKILL_BONUSES[it.ordinal] } ?: 0

            return base + bonus
        }

    /**
     * The charge rate of the engines, relative to level 1 engines.
     */
    val chargeRate: Float
        get() {
            if (powerSelected == 0)
                return 0f

            // TODO manning bonus of 1.1, 1.17 and 1.25 times for different skill levels
            return CHARGE_RATES[powerSelected - 1]
        }

    val evasionMultiplier: Float
        get() {
            // If engines is off or hacked, you get no evasion
            if (powerSelected == 0 || isHackActive)
                return 0f

            return 1f
        }

    private var lastEnginesOn = false

    override fun powerStateChanged() {
        super.powerStateChanged()

        // Play the sound effect when the engines are turned on and off
        val enginesOn = powerSelected > 0

        // Don't play sounds while we're being deserialised
        if (ship.sys.isCurrentlyLoadingSave) {
            lastEnginesOn = enginesOn
            return
        }

        when {
            enginesOn && !lastEnginesOn -> enginesOnSound.play()
            !enginesOn && lastEnginesOn -> enginesOffSound.play()
        }

        lastEnginesOn = enginesOn
    }

    override fun drawManningIcon(g: Graphics, x: Int, y: Int) {
        drawManningSkillIcon(x, y, getSkillLevel(Skill.ENGINES))
    }

    // Nothing to serialise
    override fun saveSystem(elem: Element, refs: ObjectRefs) = Unit
    override fun loadSystem(elem: Element, refs: RefLoader) = Unit

    companion object {
        // https://ftl.fandom.com/wiki/Engines
        val EVASIONS: Array<Int> = arrayOf(5, 10, 15, 20, 25, 28, 31, 35)
        val CHARGE_RATES = arrayOf(1f, 1.26f, 1.58f, 1.84f, 2.11f, 2.4f, 2.68f, 2.96f)
        val SKILL_BONUSES = listOf(5, 7, 10)

        val INFO: SystemInfo = EngineInfo
    }
}

private object EngineInfo : SystemInfo("engines") {
    override val canBeManned: Boolean get() = true

    override fun create(blueprint: SystemBlueprint) = Engines(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        val evasion = Engines.EVASIONS[level]

        // Note the visible charge rate is wrong!
        val chargeRate = 100 + 25 * level
        val chargeRateStr = UIUtils.formatStringFTL(chargeRate)

        return translator["engine"].replaceArg(evasion).replaceArg(chargeRateStr, 2)
    }
}

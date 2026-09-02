package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader

class Piloting(blueprint: SystemBlueprint) : SubSystem(blueprint) {
    /**
     * Get the manned evasion bonus, in percent.
     */
    val evasion: Int
        // Note that the crew member's bonus is only applied when they're
        // actually manning the system - being in the room isn't enough.
        get() = getSkillLevel(Skill.PILOTING)?.let { SKILL_BONUSES[it.ordinal] } ?: 0

    private val computerPoint by lazy { configuration.computerPoint ?: ConstPoint.ZERO }

    override val sortingType = SortingType.PILOTING

    val evasionMultiplier: Float
        get() {
            // If piloting is broken or hacked, you get no evasion
            if (undamagedEnergy == 0 || isHackActive)
                return 0f

            val room = this.room!!

            // Find the pilot at the computer point, or the top-left if the computer point is not defined
            val pilot = room.crew.firstOrNull {
                it.mode == AbstractCrew.SlotType.CREW && it.standingPosition?.posEq(computerPoint) == true && it.canManSystem
            }

            // If a pilot is present (not just walking there), we get 100% of our original piloting.
            // We've filtered out anyone walking by checking standingPosition.
            if (pilot != null || ship.isAutoScout)
                return 1f

            // At this point, the system is not broken and we don't have a pilot. Use the evasion
            // multipliers for the autopilot.
            // We're using the AE numbers for this, taken from: https://ftl.fandom.com/wiki/Piloting
            return when (undamagedEnergy) {
                1 -> 0f
                2 -> 0.5f
                3 -> 0.8f
                else -> error("Unsupported power level for piloting: $undamagedEnergy")
            }
        }

    override fun isMannableBy(crew: AbstractCrew): Boolean {
        if (ship.engines!!.powerSelected == 0) {
            return false
        }

        return super.isMannableBy(crew)
    }

    override fun drawManningIcon(g: Graphics, x: Int, y: Int) {
        drawManningSkillIcon(x, y, getSkillLevel(Skill.PILOTING))
    }

    // Nothing to serialise
    override fun saveSystem(elem: Element, refs: ObjectRefs) = Unit
    override fun loadSystem(elem: Element, refs: RefLoader) = Unit

    companion object {
        val INFO: SystemInfo = PilotingInfo

        val SKILL_BONUSES = listOf(5, 7, 10)
    }
}

private object PilotingInfo : SystemInfo("pilot") {
    override val canBeManned: Boolean get() = true
    override val isSubSystem: Boolean get() = true

    override fun create(blueprint: SystemBlueprint) = Piloting(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return when (level) {
            0 -> translator["pilot_1"]
            1 -> translator["pilot_2"]
            2 -> translator["pilot_3"]
            else -> "INVALID LEVEL ${level + 1}"
        }
    }
}

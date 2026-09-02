package xyz.znix.xftl.game

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.rendering.DelayedTooltip
import xyz.znix.xftl.replaceArg
import xyz.znix.xftl.systems.*

/**
 * The tooltip for the button representing each system in the player's power bar.
 */
class SystemButtonTooltip(val game: InGameState, val system: AbstractSystem) : DelayedTooltip(game) {
    override fun getText(): String {
        val power = when (system) {
            is MainSystem -> system.powerSelected
            else -> system.undamagedEnergy
        }

        val levelText: String = when {
            power <= 0 -> game.translator[system.codename + "_off"]
            else -> {
                val name = system.info.getLevelName(power - 1, game.translator)

                game.translator["level"]
                    .replaceArg(power)
                    .replaceArg(name, 2)
            }
        }

        val lines = ArrayList<String>()
        lines += game.translator["tooltip_" + system.codename]
        lines += ""
        lines += levelText

        // TODO make this more modding-friendly
        val skillType: Skill? = when (system) {
            is Piloting -> Skill.PILOTING
            is Engines -> Skill.ENGINES
            is Shields -> Skill.SHIELDS
            is Weapons -> Skill.WEAPONS
            else -> null
        }
        val skillLevel = skillType?.let { system.getSkillLevel(it) }
        if (skillLevel != null && power > 0) {
            val desc = skillType.getDescription(game, skillLevel)
            lines += ""
            lines += game.translator["manning_bonus"].replaceArg(desc)
        }

        lines += ""
        lines += game.translator["status"]
        val powerStateKey = when (power) {
            system.energyLevels -> if (system is MainSystem) "full_powered" else "full_functional"
            0 -> "unpowered"
            else -> if (system is MainSystem) "partial_powered" else "partial_functional"
        }
        lines += "-" + game.translator[powerStateKey]

        if (power > 0 && system.info.canBeManned && system.manningCrew == null) {
            lines += "-" + game.translator["unmanned"]
        }

        // TODO hacking

        if (system is MainSystem) {
            lines += ""
            // TODO inject hotkey strings
            lines += game.translator["add_power"]
            lines += game.translator["remove_power"]
        }

        return lines.joinToString("\n")
    }
}

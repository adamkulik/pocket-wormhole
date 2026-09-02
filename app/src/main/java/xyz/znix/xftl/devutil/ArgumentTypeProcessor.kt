package xyz.znix.xftl.devutil

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.game.Achievement
import xyz.znix.xftl.game.ShipFamily
import xyz.znix.xftl.shipgen.EnemyShipSpec
import java.lang.reflect.Parameter

/**
 * Represents the type of an argument that is passed to a console command.
 *
 * This converts the string value into a type that's passed to the command
 * handler method, and optionally provides an auto-completion handler.
 */
interface ArgumentTypeProcessor {
    /**
     * Checks the given parameter is suitable for this type, for example
     * checking that the parameter's type matches the output of this processor.
     */
    fun validate(param: Parameter)

    /**
     * Parses a string value that was entered in the given console.
     *
     * This should print out an error message and return null if
     * the value is malformed, otherwise it should return the value
     * that is passed to the command function.
     */
    fun process(value: String, console: DebugConsole): Any?

    /**
     * Validate or create an autocompletion engine to use for this argument type.
     *
     * [previous] is the completion engine from last frame, and it should
     * be reused if it's suitable.
     *
     * Return null to indicate no auto-completion is available.
     */
    fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter? {
        return null
    }
}

object StringTypeProcessor : ArgumentTypeProcessor {
    override fun validate(param: Parameter) {
        check(param.type == String::class.java)
    }

    override fun process(value: String, console: DebugConsole): Any {
        return value
    }
}

object IntTypeProcessor : ArgumentTypeProcessor {
    override fun validate(param: Parameter) {
        check(param.type == Integer.TYPE)
    }

    override fun process(value: String, console: DebugConsole): Any? {
        val result = value.toIntOrNull()

        if (result == null) {
            console.addLine("Invalid integer value '$value'")
        }

        return result
    }
}

class BlueprintTypeProcessor(val type: Class<out Blueprint>) : ArgumentTypeProcessor {
    override fun validate(param: Parameter) {
        check(type.isAssignableFrom(param.type))
    }

    override fun process(value: String, console: DebugConsole): Any? {
        val result = console.game.blueprintManager.blueprints[value]

        if (result == null) {
            console.addLine("No such blueprint '$value'")
            return null
        }

        if (!type.isAssignableFrom(result.javaClass)) {
            val resultType = result.javaClass.simpleName
            console.addLine("Blueprint '$value' is the wrong type: expected ${type.simpleName}, found $resultType")
        }

        return result
    }

    override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter {
        if (previous is BlueprintCompleter && previous.owner == this)
            return previous

        return BlueprintCompleter(debugConsole, this, type)
    }
}

object EnemyShipSpecProcessor : ArgumentTypeProcessor {
    override fun validate(param: Parameter) {
        check(param.type == EnemyShipSpec::class.java)
    }

    override fun process(value: String, console: DebugConsole): EnemyShipSpec? {
        if (!console.game.eventManager.hasShip(value)) {
            console.addLine("No such ship specification '$value'")
            return null
        }

        return console.game.eventManager.getShip(value)
    }

    override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter {
        if (previous is EnemyShipSpecCompleter && previous.owner == this)
            return previous

        return EnemyShipSpecCompleter(debugConsole, this)
    }
}

object AchievementProcessor : ArgumentTypeProcessor {
    override fun validate(param: Parameter) {
        check(param.type == Achievement::class.java)
    }

    override fun process(value: String, console: DebugConsole): Achievement? {
        val ach = console.game.content.achievements.achievements[value]

        if (ach == null) {
            console.addLine("No such achievement '$value'")
        }

        return ach
    }

    override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter {
        if (previous is AchievementCompleter && previous.owner == this)
            return previous

        return AchievementCompleter(debugConsole, this)
    }
}

object ShipFamilyProcessor : ArgumentTypeProcessor {
    override fun validate(param: Parameter) {
        check(param.type == ShipFamily::class.java)
    }

    override fun process(value: String, console: DebugConsole): ShipFamily? {
        val ship = console.game.content.blueprintManager.blueprints[value] as? LazyShipBlueprint

        if (ship == null) {
            console.addLine("No such ship blueprint '$value' (when reading family)")
            return null
        }

        val family = console.game.content.shipFamilies.byShipId[ship.name]

        if (family == null) {
            console.addLine("Ship '${ship.name}' does not belong to a ship family")
        }

        return family
    }

    override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter {
        if (previous is ShipFamilyCompleter && previous.owner == this)
            return previous

        return ShipFamilyCompleter(debugConsole, this)
    }
}

class EnumTypeProcessor(val type: Class<*>) : ArgumentTypeProcessor {
    override fun validate(param: Parameter) {
        check(type.isAssignableFrom(param.type))
    }

    override fun process(value: String, console: DebugConsole): Any? {
        val options = type.enumConstants.map { it as Enum<*> }
        val result = options.firstOrNull { it.name.equals(value, ignoreCase = true) }

        if (result == null) {
            console.addLine("No such enum constant '$value', possible options:")
            for (option in options) {
                console.addLine("   " + option.name)
            }
            return null
        }

        return result
    }

    override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter {
        if (previous is EnumCompleter && previous.owner == this)
            return previous

        return EnumCompleter(debugConsole, this, type)
    }
}

package xyz.znix.xftl.devutil

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.Achievement
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.ShipFamily
import xyz.znix.xftl.shipgen.EnemyShipSpec
import java.lang.reflect.Parameter
import kotlin.reflect.KClass

/**
 * Marks a given function as representing a console command.
 *
 * The function's arguments are matched up to [ArgumentTypeProcessor]
 * instances, so commands can easily get parsing and auto-completion
 * for their arguments.
 *
 * Each parameter must either be of a specially-known type, or be annotated
 * with [ParType] to manually set their type.
 *
 * The specially-known types are:
 *
 * - int
 * - String
 * - [Blueprint] and it's subclasses
 * - [EnemyShipSpec]
 * - [Achievement]
 * - [ShipFamily]
 * - Any enum
 *
 * The last parameter can also be annotated with [CmdVarArg] (which requires
 * the parameter is of type List<String>), to take any arguments remaining
 * after the previously-specified parameters were processed.
 */
@Target(AnnotationTarget.FUNCTION)
annotation class ConsoleCommand(
    val name: String
)

@Target(AnnotationTarget.FUNCTION)
annotation class CmdHelp(
    val help: String
)

/**
 * Put this on a List<String> parameter to a console command, to accept any additional
 * number of arguments after the previous arguments were parsed.
 *
 * This can only go on the last parameter.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class CmdVarArg

/**
 * Sets the user-visible name of the parameter, as shown in the greyed-out
 * parameter hints.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class ParName(
    val name: String
)

/**
 * Specifies the type processor that should be used to parse a given argument.
 *
 * The referred class can either have a getInstance(Parameter) static method,
 * or have an INSTANCE field (the latter of which is automatically defined
 * for Kotlin's top-level objects).
 *
 * When using Kotlin, be sure to annotate methods and fields with [JvmStatic].
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class ParType(
    val type: KClass<out ArgumentTypeProcessor>
)

/**
 * Represents a class that adds console commands.
 */
abstract class ConsoleCommandProvider(val console: DebugConsole) {
    open val ship: Ship get() = console.game.player
    open val game: InGameState get() = console.game

    /**
     * A big list of commands, generated from the [ConsoleCommand]-annotated
     * methods in this class.
     */
    val commands: List<DebugConsole.Cmd>

    init {
        commands = ArrayList()

        val methods = javaClass.declaredMethods
        for (method in methods) {
            val cmdAnnotation = method.getAnnotation(ConsoleCommand::class.java) ?: continue
            val helpString = method.getAnnotation(CmdHelp::class.java)?.help ?: "No help message provided."

            val params = ArrayList<DebugConsole.CmdParameter>()
            var isVarArg = false

            for (param in method.parameters) {
                // A vararg parameter must go last
                if (isVarArg) {
                    error("Found parameter name=${param.name} after vararg parameter for method $method")
                }

                if (param.isAnnotationPresent(CmdVarArg::class.java)) {
                    isVarArg = true
                    continue
                }

                val typeAnnotation = param.getAnnotation(ParType::class.java)

                val type: ArgumentTypeProcessor

                if (typeAnnotation != null) {
                    type = getArgTypeInstance(typeAnnotation, param)
                } else if (Blueprint::class.java.isAssignableFrom(param.type)) {
                    @Suppress("UNCHECKED_CAST")
                    type = BlueprintTypeProcessor(param.type as Class<out Blueprint>)
                } else if (param.type.isEnum) {
                    type = EnumTypeProcessor(param.type)
                } else {
                    type = when (param.type) {
                        EnemyShipSpec::class.java -> EnemyShipSpecProcessor
                        Achievement::class.java -> AchievementProcessor
                        ShipFamily::class.java -> ShipFamilyProcessor
                        String::class.java -> StringTypeProcessor
                        Integer.TYPE -> IntTypeProcessor
                        else -> error("Invalid parameter type ${param.type} for console command $method")
                    }
                }

                type.validate(param)

                // Note we use param.name, which isn't included in bytecode by default.
                // For Java, pass -parameters to javac
                // For Kotlin, set javaParameters
                // We don't set this in the interests of file size, but
                // we'll use it if it's available for mod convenience.
                val nameAnnotation = param.getAnnotation(ParName::class.java)
                val name = nameAnnotation?.name ?: param.name ?: "param${params.size}"

                params.add(DebugConsole.CmdParameter(type, name))
            }

            // Allow private methods
            method.isAccessible = true

            val caller: (List<Any>) -> Unit = {
                method.invoke(this, *it.toTypedArray())
            }

            val cmd = DebugConsole.Cmd(cmdAnnotation.name, params, isVarArg, caller, helpString)

            commands.add(cmd)
        }
    }

    fun addLine(line: String) {
        console.addLine(line)
    }

    private fun getArgTypeInstance(typeAnnotation: ParType, parameter: Parameter): ArgumentTypeProcessor {
        val type: Class<*> = typeAnnotation.type.java

        val getInstance = type.methods.firstOrNull {
            it.name == "getInstance" && it.parameters.contentEquals(arrayOf(Parameter::class.java))
        }
        if (getInstance != null) {
            return getInstance.invoke(null, parameter) as ArgumentTypeProcessor
        }

        val instanceField = type.fields.firstOrNull { it.name == "INSTANCE" }
        if (instanceField != null) {
            return instanceField.get(null) as ArgumentTypeProcessor
        }

        error("Missing getInstance method or INSTANCE field for argument type processor: $type")
    }
}

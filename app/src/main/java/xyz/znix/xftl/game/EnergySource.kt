package xyz.znix.xftl.game

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.ZoltanEnergySource
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.systems.BatteryEnergySource
import xyz.znix.xftl.systems.MainSystem

/**
 * Represents a source of power for systems, such as a reactor or zoltan.
 *
 * A single energy source may either use [adjustShipPower] or [getSystemPower],
 * but never both for a single energy source. If you want to use both, simply
 * use two different [EnergySource]s.
 *
 * This interface should be implemented by singletons; the only reason it's
 * not an enum is for modding, as mods might want to add their own power sources.
 */
interface EnergySource {
    /**
     * A unique ID, used for serialisation.
     */
    val serialisationId: String

    /**
     * If true, [getSystemPower] may return a non-zero value.
     *
     * If false, [adjustShipPower] may do something.
     */
    val isPerSystem: Boolean get() = false

    /**
     * True if this power source can't be displaced with ion damage.
     */
    val isIonProof: Boolean get() = false

    fun adjustShipPower(ship: Ship, power: MutableMap<EnergySource, Int>)

    /**
     * Get an amount of power that's supplied to one specific system, without
     * affecting the rest of the ship.
     *
     * This is implemented principally for Zoltans.
     */
    fun getSystemPower(system: MainSystem): Int

    /**
     * Draw the power bar that this energy source provides, taking into account
     * a system's status, such as whether it's ionised or hacked.
     */
    fun drawSystemPowerBar(g: Graphics, system: AbstractSystem, x: Int, y: Int, width: Int, height: Int)

    /**
     * Draw the power bar that this energy source provides, as it should appear
     * in the ship's reactor power stack.
     */
    fun drawReactorPowerBar(g: Graphics, x: Int, y: Int, width: Int, height: Int)

    companion object {
        /**
         * The list of all the available energy sources, in order of priority
         * (with higher priority sources like the backup battery appearing
         *  later in the list, and Zoltan power comes earlier).
         */
        // TODO build this from installed mods
        val TYPES = listOf(
            ZoltanEnergySource,
            ReactorEnergySource,
            BatteryEnergySource
        )

        /**
         * The list of per-system energy sources, such as Zoltan power.
         */
        val PER_SYSTEM_TYPES = TYPES.filter { it.isPerSystem }

        /**
         * The list of energy sources that add reactor-style power, such
         * as the reactor and backup battery.
         */
        val GLOBAL_TYPES = TYPES.filter { !it.isPerSystem }
    }
}

object ReactorEnergySource : EnergySource {
    override val serialisationId: String get() = "reactor"

    override fun adjustShipPower(ship: Ship, power: MutableMap<EnergySource, Int>) {
        power[this] = ship.reactorPower
    }

    override fun getSystemPower(system: MainSystem): Int {
        return 0
    }

    override fun drawSystemPowerBar(g: Graphics, system: AbstractSystem, x: Int, y: Int, width: Int, height: Int) {
        when {
            system.isHackActive -> {
                // The power bars go purple when hacked, taking
                // priority over ion damage.
                g.colour = Constants.SYSTEM_HACKED
                g.fillRect(x, y, width, height)
            }

            system.isIonised -> {
                // The system is powered at this level (or it's
                // a subsystem), but ion damage is applied.
                // This changes the colour of all the remaining power.
                g.colour = Constants.SYSTEM_IONISED
                g.fillRect(x, y, width, height)
            }

            system.hasWhiteLockingBox -> {
                g.colour = Colour.white
                g.fillRect(x, y, width, height)
            }

            else -> {
                // System powered, or a subsystem that doesn't need powering
                drawReactorPowerBar(g, x, y, width, height)
            }
        }
    }

    override fun drawReactorPowerBar(g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        g.colour = Constants.SYS_ENERGY_ACTIVE
        g.fillRect(x, y, width, height)
    }
}

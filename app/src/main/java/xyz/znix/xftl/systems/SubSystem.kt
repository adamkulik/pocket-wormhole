package xyz.znix.xftl.systems

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Constants
import xyz.znix.xftl.game.ReactorEnergySource
import xyz.znix.xftl.rendering.Graphics

abstract class SubSystem(blueprint: SystemBlueprint) : AbstractSystem(blueprint) {
    abstract val sortingType: SortingType

    /**
     * Get the energy this system is operating at, accounting for
     * the bonus energy from manning it.
     */
    open val effectivePower: Int
        get() {
            if (info.canBeManned && manningCrew != null) {
                return undamagedEnergy + 1
            }
            return undamagedEnergy
        }

    init {
        check(blueprint.info!!.isSubSystem)
    }

    override fun drawPowerBars(g: Graphics, x: Int, yOfBottom: Int, hoverGlow: Boolean): Int {
        // The top of the bottom bar
        val baseY = yOfBottom - 6

        // Need to grab this as a local so the compiler knows it won't change
        val scriptedPowerLimit = this.scriptedPowerLimit

        for (i in 0 until energyLevels) {
            val y = baseY - i * 8

            when {
                i >= energyLevels - damagedEnergyLevels -> {
                    // System damaged/broken
                    g.colour = Constants.SYS_ENERGY_BROKEN
                    g.drawRect(x, y, 16 - 1, 6 - 1)
                    g.drawLine(x, y + 6, x + 16, y)
                }

                scriptedPowerLimit != null && i >= scriptedPowerLimit -> {
                    // System power limited by a scripted event
                    g.colour = Constants.SYS_ENERGY_EVENT_LOCKED
                    g.drawRect(x, y, 16 - 1, 6 - 1)
                    g.drawLine(x, y + 6, x + 16, y)
                }

                i >= undamagedEnergy -> {
                    // System depowered due to ion
                    g.colour = Constants.SYS_ENERGY_DEPOWERED
                    g.drawRect(x, y, 16 - 1, 6 - 1)
                }

                else -> {
                    // Use the generic power bar visuals
                    ReactorEnergySource.drawSystemPowerBar(g, this, x, y, 16, 6)
                }
            }

            // The repair bar
            if (i == energyLevels - damagedEnergyLevels) {
                g.colour = Constants.SYS_ENERGY_REPAIR
                val width = (16 * repairProgress).toInt()
                g.fillRect(x + 16 - width, y, width, 6)
            }

            // The sabotage bar
            if (i == energyLevels - damagedEnergyLevels - 1) {
                g.colour = Constants.SYS_ENERGY_SABOTAGE
                val width = (16 * damageProgress).toInt()
                g.fillRect(x, y, width, 6)
            }
        }

        return baseY - (energyLevels - 1) * 8
    }

    override fun drawManningIcon(g: Graphics, x: Int, y: Int) {
        if (!info.canBeManned)
            return

        val iconPath = when {
            isIonised -> "img/systemUI/manning_bar_ion.png"
            manningCrew != null -> "img/systemUI/manning_bar_on.png"
            else -> "img/systemUI/manning_bar_off.png"
        }

        val img = ship.sys.getImg(iconPath)
        img.draw(x, y + 2 - img.height)
    }

    // List of the default systems, for sorting purposes
    enum class SortingType {
        PILOTING,
        SENSORS,
        DOORS,
        BATTERY,
    }
}

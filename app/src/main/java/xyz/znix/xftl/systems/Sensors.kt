package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader

class Sensors(blueprint: SystemBlueprint) : SubSystem(blueprint) {
    override val sortingType = SortingType.SENSORS

    private val blocked: Boolean get() = ship.sys.currentBeacon.environmentType.isNebula || isHackActive
    val providesPlayerVision: Boolean get() = !blocked && effectivePower >= 1
    val providesEnemyVision: Boolean get() = !blocked && effectivePower >= 2
    val showsEnemyWeaponCharge: Boolean get() = !blocked && effectivePower >= 3
    val showsEnemyPowerLevels: Boolean get() = !blocked && effectivePower >= 4

    /**
     * True when the current location (a nebula, including the ion storm
     * inside one) is disabling the sensors, as opposed to damage, a lack of
     * power or an enemy hack.
     *
     * Vanilla names exactly these two causes in its `temp_disabled` tooltip
     * text: "Temporarily disabled by enemy or location" (text_misc.xml).
     */
    private val isDisabledByLocation: Boolean get() = ship.sys.currentBeacon.environmentType.isNebula

    /**
     * Vanilla disables the Sensors subsystem in nebulae (wiki, Sensors:
     * "Sensors are temporarily disabled in nebulas"; Environmental Hazards:
     * "Nebulas disable your Sensors"), and a disabled system cannot be manned
     * (wiki, Systems: "Ionized, damaged, hacked or disabled ... systems
     * cannot be manned"). The base implementation already covers the hacked
     * (enemy) case - this adds the location (nebula/ion storm) one.
     *
     * Without this the crew happily work the sensors console in a nebula,
     * where the system does nothing (GitHub issue #175).
     */
    override fun isMannableBy(crew: AbstractCrew): Boolean {
        if (isDisabledByLocation)
            return false

        return super.isMannableBy(crew)
    }

    // Nothing to serialise
    override fun saveSystem(elem: Element, refs: ObjectRefs) = Unit
    override fun loadSystem(elem: Element, refs: RefLoader) = Unit

    companion object {
        val INFO: SystemInfo = SensorInfo
    }
}

private object SensorInfo : SystemInfo("sensors") {
    override val canBeManned: Boolean get() = true
    override val isSubSystem: Boolean get() = true

    override fun create(blueprint: SystemBlueprint) = Sensors(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return when (level) {
            0 -> translator["sensor_1"]
            1 -> translator["sensor_2"]
            2 -> translator["sensor_3"]
            3 -> translator["sensor_4"]
            else -> "INVALID LEVEL ${level + 1}"
        }
    }
}

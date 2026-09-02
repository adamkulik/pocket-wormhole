package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.SystemInfo

class SystemBlueprint(xml: Element) : Blueprint(xml) {
    val type: String = xml.getChildTextTrim("type")

    val startPower: Int = xml.getChildTextTrim("startPower").toInt()
    val maxPower: Int = xml.getChildTextTrim("maxPower").toInt()
    override val cost: Int = xml.getChildTextTrim("cost").toInt()

    val upgradeCost: List<Int> = xml.getChild("upgradeCost").getChildren("level").map { it.text.toInt() }

    val onIconPath = "img/icons/s_${type}_green1.png"
    val roomIconPath: String = "img/icons/s_${type}_overlay.png"
    val roomIconOutlinePath: String = "img/icons/s_${type}_overlay2.png"

    // No idea what the 'locked' flag does.

    val info: SystemInfo? = BY_NAME[type]

    init {
        if (info == null) {
            // Must be a modded system.
            println("Warning: unimplemented system $type")
        }
    }

    fun createInstance(): AbstractSystem? {
        return info?.create(this)
    }

    companion object {
        /**
         * Padding inside the icon image file, which contains the glow
         */
        const val ICON_GLOW = 19

        val ALL_SYSTEMS = listOf(
            Doors.INFO,
            Engines.INFO,
            Medbay.INFO,
            Oxygen.INFO,
            Piloting.INFO,
            Sensors.INFO,
            Shields.INFO,
            Cloaking.INFO,
            Weapons.INFO,
            Drones.INFO,
            Teleporter.INFO,
            Artillery.INFO,

            // AE-only
            MindControl.INFO,
            Hacking.INFO,
            Clonebay.INFO,
            BackupBattery.INFO
        )

        val BY_NAME = ALL_SYSTEMS.associateBy { it.name }
    }
}

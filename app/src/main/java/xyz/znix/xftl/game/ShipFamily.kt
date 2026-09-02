package xyz.znix.xftl.game

import org.jdom2.Document
import org.jdom2.Element

/**
 * This stores the metadata about ship families, as found in xftl_ships.xml.
 */
class ShipFamily(elem: Element) {
    val ships: List<String>
    val achievements: List<String>
    val unlockId: String? = elem.getAttributeValue("unlockId")
    val hasQuest: Boolean = elem.getAttributeValue("hasQuest")?.toBoolean() ?: (unlockId != null)

    init {
        ships = elem.getChildren("ship").map { it.getAttributeValue("id") }
        achievements = elem.getChildren("ach").map { it.getAttributeValue("id") }
    }

    class FamilyTable(doc: Document) {
        val families: List<ShipFamily>
        val byShipId: Map<String, ShipFamily>

        init {
            check(doc.rootElement.name == "ship_data")

            families = ArrayList()

            for (elem in doc.rootElement.getChildren("ship_family")) {
                families.add(ShipFamily(elem))
            }

            byShipId = families.flatMap { family -> family.ships.map { Pair(it, family) } }.toMap()
        }
    }
}

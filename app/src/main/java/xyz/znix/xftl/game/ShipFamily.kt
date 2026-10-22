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

    /**
     * Whether the player can select this family in the hangar.
     *
     * Families without an [unlockId] (such as the Kestrel) are available
     * from the start. The rest are unlocked by their quest event - the
     * event has an `<unlockShip id="..."/>` element, which the
     * [DialogueWindow] records in the profile.
     */
    fun isUnlocked(profile: SaveProfile): Boolean {
        if (unlockId == null)
            return true

        return profile.allShipsUnlocked || isReallyUnlocked(profile)
    }

    /** Unlocked by its quest or an import, ignoring UNLOCK ALL. */
    fun isReallyUnlocked(profile: SaveProfile): Boolean {
        if (unlockId == null)
            return true

        return profile.getShipUnlock(this) != null
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

package xyz.znix.xftl.game

import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.rollChance
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import java.util.*
import kotlin.math.min
import kotlin.random.Random

/**
 * Carries persistent information about a store, like its available items and prices.
 */
class StoreData {
    val availableResources = ResourceSet()

    val sections = ArrayList<Section>()

    /**
     * If this store has a systems section, this contains the systems, or null if they're sold out.
     */
    val systems = ArrayList<SystemBlueprint?>()

    /**
     * If this store has a weapons section, this contains the weapons, or null if they're sold out.
     */
    val weapons = ArrayList<AbstractWeaponBlueprint?>()

    /**
     * If this store has a drones section, this contains the drones, or null if they're sold out.
     */
    val drones = ArrayList<DroneBlueprint?>()

    /**
     * If this store has an augments section, this contains the augments, or null if they're sold out.
     */
    val augments = ArrayList<AugmentBlueprint?>()

    /**
     * If this store has a crew section, this is the crew, or null if they're sold out.
     */
    val crew = ArrayList<LivingCrewInfo?>()

    fun generateRandomContents(game: InGameState, rand: Random) {
        // See doc/stores for generation details
        availableResources[Resource.FUEL] = (3..7).random(rand)
        availableResources[Resource.MISSILES] = (2..6).random(rand)
        availableResources[Resource.DRONES] = (2..4).random(rand)

        // Generate the section types
        val numSections = (2..4).random(rand)

        val ship = game.player
        val numSystems = ship.systems.size

        val possibleSections = ArrayList<Section>()
        possibleSections += Section.AUGMENTS
        possibleSections += Section.CREW
        possibleSections += Section.DRONES
        possibleSections += Section.WEAPONS

        // On non-AE, systems don't spawn once you've got them all.
        if (game.content.enableAdvancedEdition || numSystems < 11) {
            possibleSections += Section.SYSTEMS
        }

        // If you have <11 systems (including subsystems), there's
        // a 50% chance the first section will be a systems section.
        if (numSystems < 11 && rand.rollChance(50)) {
            possibleSections.remove(Section.SYSTEMS)
            sections += Section.SYSTEMS
        }

        // Randomly spawn the rest of the sections
        for (i in 0 until numSections) {
            sections += possibleSections.removeAt(possibleSections.indices.random(rand))
        }

        systems += generateSystems(game, rand)

        weapons += getForSection(game, rand, Section.WEAPONS, AbstractWeaponBlueprint::class.java)
        drones += getForSection(game, rand, Section.DRONES, DroneBlueprint::class.java)
        augments += getForSection(game, rand, Section.AUGMENTS, AugmentBlueprint::class.java)

        if (sections.contains(Section.CREW)) {
            crew += game.lootPool.getManyRandom(CrewBlueprint::class.java, rand, 3)
                .map { LivingCrewInfo.generateRandom(it, game) }
        }
    }

    private fun <T> getForSection(game: InGameState, rand: Random, section: Section, type: Class<T>): List<T?> {
        // Return an empty list to reduce the savefile size
        if (!sections.contains(section)) {
            return emptyList()
        }

        return game.lootPool.getManyRandom(type, rand, 3)
    }

    private fun generateSystems(game: InGameState, rand: Random): List<SystemBlueprint?> {
        val ship = game.player
        val systems = ArrayList<SystemBlueprint?>()

        // Return an empty list to reduce the savefile size
        if (!sections.contains(Section.SYSTEMS)) {
            return systems
        }

        val possibleSystems = ArrayList(game.blueprintManager.blueprints.values.filterIsInstance<SystemBlueprint>())
        val dronesSystem = possibleSystems.first { it.info == Drones.INFO }
        val shieldsSystem = possibleSystems.first { it.info == Shields.INFO }
        val medbaySystem = possibleSystems.first { it.info == Medbay.INFO }
        val clonebaySystem = possibleSystems.firstOrNull { it.info == Clonebay.INFO } // Null on non-AE

        val playerHasDrones = ship.systems.any { it is Drones }
        val playerHasShields = ship.systems.any { it is Shields }
        val playerHasMedical = ship.systems.any { it is Medbay || it is Clonebay }

        val forcedSystems = ArrayList<SystemBlueprint>()

        if (sections.contains(Section.DRONES) && !playerHasDrones) {
            forcedSystems += dronesSystem
            possibleSystems.remove(dronesSystem)
        }

        if (!playerHasShields) {
            forcedSystems += shieldsSystem
            possibleSystems.remove(shieldsSystem)
        }

        if (!playerHasMedical) {
            val medicalSystem = when {
                game.content.enableAdvancedEdition -> listOf(medbaySystem, clonebaySystem!!).random(rand)
                else -> medbaySystem
            }

            forcedSystems += medicalSystem

            // A forced medical system hides both the clonebay and medbay.
            possibleSystems.remove(medbaySystem)
            clonebaySystem?.let { possibleSystems.remove(it) }
        }

        // Remove all the systems the player already has
        possibleSystems.removeAll(ship.systems.map { it.blueprint }.toSet())

        // Remove any system that can't be installed on the player's ship.
        // This is here for modded ships, which might not be able to accommodate all the systems.
        val shipSlots = ship.systemSlots.associateBy { it.system }
        possibleSystems.removeIf { !shipSlots.containsKey(it) }

        // If the ship is full and can't take any more main systems, remove those
        // which don't replace an existing system.
        if (ship.mainSystems.size >= 8) {
            possibleSystems.removeIf { it.info?.isSubSystem != true && shipSlots.getValue(it).room.system == null }
        }

        // To match FTL with a little bit of odd behaviour, if the list of non-forced
        // systems is less than three systems long, that sets the number of systems.
        // Thus if you don't have a medical system, you can end up with a blank system
        // slot even though there's a system that's not displayed.
        // See doc/stores for more information.
        val numSystems = min(3, possibleSystems.size)

        while (systems.size < numSystems) {
            systems += possibleSystems.removeAt(possibleSystems.indices.random(rand))
        }

        return systems
    }

    fun saveToXML(elem: Element) {
        for (section in sections) {
            val sectionElem = Element("section")
            SaveUtil.addAttr(sectionElem, "id", section.name)
            elem.addContent(sectionElem)
        }

        for ((name, count) in availableResources.entries) {
            elem.setAttribute(name.name, count.toString())
        }

        saveBlueprints(elem, "drone", drones)
        saveBlueprints(elem, "weapon", weapons)
        saveBlueprints(elem, "system", systems)
        saveBlueprints(elem, "augment", augments)

        for ((i, crew) in crew.withIndex()) {
            if (crew == null)
                continue

            val crewElem = Element("crew")
            SaveUtil.addAttrInt(crewElem, "idx", i)
            crew.saveToXML(crewElem)
            elem.addContent(crewElem)
        }
    }

    fun loadFromXML(game: InGameState, elem: Element) {
        for (sectionElem in elem.getChildren("section")) {
            val sectionName = SaveUtil.getAttr(sectionElem, "id")
            sections += Section.valueOf(sectionName)
        }

        for (resource in Resource.entries) {
            val value = elem.getAttributeValue(resource.name)?.toInt() ?: continue
            availableResources[resource] = value
        }

        loadBlueprints(game, elem, "drone", drones, DroneBlueprint::class.java)
        loadBlueprints(game, elem, "weapon", weapons, AbstractWeaponBlueprint::class.java)
        loadBlueprints(game, elem, "system", systems, SystemBlueprint::class.java)
        loadBlueprints(game, elem, "augment", augments, AugmentBlueprint::class.java)

        for (crewElem in elem.getChildren("crew")) {
            val idx = SaveUtil.getAttrInt(crewElem, "idx")
            while (crew.size <= idx) {
                crew.add(null)
            }

            crew[idx] = LivingCrewInfo.loadFromXML(crewElem, game)
        }
    }

    private fun saveBlueprints(elem: Element, name: String, items: List<Blueprint?>) {
        for ((i, item) in items.withIndex()) {
            if (item == null)
                continue

            val itemElem = Element(name)
            SaveUtil.addAttrInt(itemElem, "idx", i)
            SaveUtil.addAttr(itemElem, "type", item.name)
            elem.addContent(itemElem)
        }
    }

    private fun <T> loadBlueprints(
        game: InGameState,
        elem: Element, name: String,
        items: MutableList<T?>,
        type: Class<T>
    ) {
        for (itemElem in elem.getChildren(name)) {
            val idx = SaveUtil.getAttrInt(itemElem, "idx")
            val itemName = SaveUtil.getAttr(itemElem, "type")

            val item = game.blueprintManager[itemName]
            require(type.isInstance(item))
            @Suppress("UNCHECKED_CAST")
            val casted: T = run { item as T } // Run block needed to suppress the warning

            // Grow the items list until it can fit the item in the correct index
            while (items.size <= idx) {
                items.add(null)
            }

            items[idx] = casted
        }
    }

    enum class Section {
        AUGMENTS,
        CREW,
        DRONES,
        SYSTEMS,
        WEAPONS;

        val localisationKey get() = "store_title_" + name.toLowerCase(Locale.UK)
    }
}

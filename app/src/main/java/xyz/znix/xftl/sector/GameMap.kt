package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.InGameState.GameContent
import xyz.znix.xftl.mapChildrenText
import xyz.znix.xftl.requireAttributeValue
import xyz.znix.xftl.savegame.ISerialReferencable
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.abs
import kotlin.random.Random

/**
 * Represents the overall map of the game. This loads and parses the sector data (which defines all the
 * different types of sectors) and generates a random set of them to be used in the sector map.
 */
class GameMap private constructor(df: Datafile, private val eventManager: EventManager, enableAE: Boolean) {
    private val sectorClasses = HashMap<SectorClass, List<SectorType>>()
    private val sectorTypes = HashMap<String, SectorType>()

    private val specialEvents = SpecialEvents(
        eventManager["START_GAME"],
        eventManager["NEUTRAL"],
        eventManager["FINISH_BEACON"],
        eventManager["FINISH_BEACON_NEBULA"],
        eventManager["FEDERATION_BASE"]
    )

    val sectors: List<List<SectorInfo>> = ArrayList()

    init {
        val xml = df.parseXML(df["data/sector_data.xml"])

        val namedSectorTypes = HashMap<String, List<String>>()

        for (elem in xml.rootElement.children) {
            when (elem.name) {
                "sectorType" -> parseSectorType(elem, namedSectorTypes)
                "sectorDescription" -> parseSectorDescription(elem)
                // TODO sectorMap is used in Multiverse (but not vanilla), support it.
                //  (note it might come from Hyperspace)
                else -> println("[warn] Unknown node type '${elem.name}' in sector_data.xml")
            }
        }

        val sectorClassesAE = HashMap<SectorClass, List<SectorType>>()
        outer@ for ((name, sectors) in namedSectorTypes) {
            val sectorClass = when (name.removePrefix(BlueprintManager.AE_PREFIX)) {
                "CIVILIAN" -> SectorClass.CIVILIAN
                "HOSTILE" -> SectorClass.HOSTILE
                "NEBULA" -> SectorClass.NEBULA

                // Skip unknown sector classes
                else -> continue@outer
            }

            // Keep the AE stuff separate, so we can override
            // the non-AE versions later.
            val classes = if (name.startsWith(BlueprintManager.AE_PREFIX)) sectorClassesAE else sectorClasses

            classes[sectorClass] = sectors.map {
                sectorTypes[it] ?: error("Missing sector $it specificed in category $name")
            }
        }

        // Apply the AE overrides
        if (enableAE) {
            sectorClasses.putAll(sectorClassesAE)
        }
    }

    /**
     * Create a new, random game map.
     */
    constructor(df: Datafile, eventManager: EventManager, enableAE: Boolean, random: Random)
            : this(df, eventManager, enableAE) {

        // This assertion lets us modify the sectors array in the constructor
        require(sectors is ArrayList)

        // Add the starting sector
        val startingType = sectorTypes["CIVILIAN_SECTOR"] ?: error("Cannot find CIVILIAN_SECTOR for first sector")
        val startingSector = SectorInfo(0, 0, startingType, SectorClass.CIVILIAN)
        sectors.add(listOf(startingSector))

        // Keep track of what sector types we've used, to avoid duplicating
        // sectors like homewords that are unique.
        val usedSectorTypes = HashSet<SectorType>()

        // Add the six intermediate columns
        for (columnNum in 1..6) {
            val lastColumn = sectors.last()

            // Choose how many sectors there will be in this column, choosing
            // a number different to the previous one.
            var numInColumn: Int
            do {
                numInColumn = (2..4).random(random)
            } while (numInColumn == lastColumn.size)

            // Except the 2nd column is special, as it joins the starting
            // beacon and thus must only have two beacons to avoid giving
            // the player more than two options.
            if (lastColumn.size == 1) {
                numInColumn = 2
            }

            // Build all the sectors in this column
            val column = ArrayList<SectorInfo>()
            for (i in 0 until numInColumn) {
                val sectorClass = SectorClass.random(random)

                val allTypes = sectorClasses[sectorClass] ?: error("No sectors for sector class $sectorClass")

                // Filter down the sector types to only those permitted at this point
                val availableTypes = allTypes.filter {
                    // Gated to later sectors
                    if (columnNum < it.minSector)
                        return@filter false

                    // If this is a unique sector, make sure it's not already been used
                    if (it.unique) {
                        if (usedSectorTypes.contains(it))
                            return@filter false
                    }

                    return@filter true
                }

                val type = availableTypes.random(random)
                usedSectorTypes.add(type)

                column.add(SectorInfo(columnNum, i, type, sectorClass))
            }

            // Add the connections between the previous sectors and
            // the new ones. This depends on the number of sectors
            // in this and the previous column, see sector-map for
            // more information.

            if (lastColumn.size == 2 && numInColumn == 4) {
                lastColumn[0].nextSectors += column[0]
                lastColumn[0].nextSectors += column[1]
                lastColumn[1].nextSectors += column[2]
                lastColumn[1].nextSectors += column[3]
            } else if (lastColumn.size == 4 && numInColumn == 2) {
                lastColumn[0].nextSectors += column[0]
                lastColumn[1].nextSectors += column[0]
                lastColumn[2].nextSectors += column[1]
                lastColumn[3].nextSectors += column[1]
            } else {
                // This should only happen when we change size by one.
                require(abs(lastColumn.size - numInColumn) == 1)

                // Link each sector (except the edge ones in the column
                // with the most sectors) to two others.

                // Link the nth+a sector in the previous column to the nth+b
                // one in the current column.
                fun linkColumnsOffset(prevOffset: Int, currOffset: Int) {
                    var i = 0
                    while (prevOffset + i < lastColumn.size && currOffset + i < column.size) {
                        lastColumn[prevOffset + i].nextSectors += column[currOffset + i]
                        i += 1
                    }
                }

                if (lastColumn.size > numInColumn) {
                    // Less nodes in this column, we need the first and second
                    // sectors in the first column linking to the first sector
                    // in the second column, and so on.
                    linkColumnsOffset(0, 0)
                    linkColumnsOffset(1, 0)
                } else {
                    // There's more sectors in the new column, do the opposite
                    linkColumnsOffset(0, 0)
                    linkColumnsOffset(0, 1)
                }
            }

            sectors.add(column)
        }

        // Add sector 8
        val finalType = sectorTypes["FINAL"] ?: error("Cannot find FINAL sector for the boss fight")
        val finalSector = SectorInfo(7, 0, finalType, SectorClass.HOSTILE)
        sectors.add(listOf(finalSector))

        // ... and link up all the sectors from the previous column, so it's accessible
        for (sector in sectors[sectors.size - 2]) {
            sector.nextSectors += finalSector
        }
    }

    private fun parseSectorType(elem: Element, namedSectorTypes: HashMap<String, List<String>>) {
        val name = elem.requireAttributeValue("name")
        if (namedSectorTypes.containsKey(name)) {
            println("[WARN] Overwriting sector type '$name'")
        }
        namedSectorTypes[name] = elem.mapChildrenText("sector")
    }

    private fun parseSectorDescription(elem: Element) {
        val name = elem.requireAttributeValue("name")

        // Specialcase a couple of unused sectors which don't have names set, and should never appear otherwise anyway
        // Note that abandoned sector doesn't refer to the lanius-filled in-game sector - that's LANIUS_SECTOR
        if (name == "ABANDONED_SECTOR" || name == "DEEP_SPACE_SECTOR") return

        // Sectors can be overwritten by mods
        if (sectorTypes.containsKey(name)) {
            println("Overwriting existing sector $name")
        }

        val sector = SectorType(eventManager, elem)
        check(name == sector.name)
        sectorTypes[name] = sector
    }

    fun generateSector(sectorInfo: SectorInfo, game: InGameState): Sector {
        val eventPool = ArrayList<Event>()
        for (ev in sectorInfo.type.events) {
            val count = ev.count.random()
            for (i in 1..count) {
                eventPool += ev.event.resolve()
            }
        }

        // Try ten times to generate a suitable sector
        var attemptCount = 0
        var sector: Sector
        while (true) {
            sector = Sector(sectorInfo, eventPool, specialEvents, game.difficulty)

            val path = sector.findShortestPath(sector.startBeacon, sector.finishBeacon)

            // If there isn't a path to the finish beacon, try again regardless
            // of how many attempts we've previously made.
            if (path == null) {
                attemptCount++
                continue
            }

            // Otherwise try and require at least a five-beacon path
            if (path.size < 5 && attemptCount < 10) {
                attemptCount++
                continue
            }

            break
        }

        // Set up the flagship, if required.
        if (sector.isLastStand) {
            attemptCount = 0
            while (true) {
                attemptCount++

                // The flagship goes on one of the two right-most columns.
                val flagshipBeacon = sector.beacons.filter { it.gridPos.x >= 4 }.random()

                // Make sure we have a path to the base!
                val path = sector.findShortestPath(flagshipBeacon, sector.finishBeacon) ?: continue

                // Try and pick a flagship position that gives the player
                // enough time to get there!
                // Note that the path length doesn't include the first beacon,
                // so the limits here are one lower than specified in doc/sector-map.
                if (path.size !in 3..5 && attemptCount < 10)
                    continue

                sector.bosses.add(FlagshipBoss(sector, game, flagshipBeacon))
                break
            }
        }

        return sector
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        // Register all the sectors now, since they reference each other.
        for (column in sectors) {
            for (sector in column) {
                refs.register(sector, "sectorInfo")
            }
        }

        for (column in sectors) {
            val columnElem = Element("column")
            for (sector in column) {
                val sectorElem = Element("sectorInfo")
                sector.saveToXML(sectorElem, refs)
                columnElem.addContent(sectorElem)
            }
            elem.addContent(columnElem)
        }
    }

    /**
     * Deserialise a game map from XML.
     */
    constructor(content: GameContent, elem: Element, refs: RefLoader)
            : this(content.datafile, content.eventManager, content.enableAdvancedEdition) {

        // This assertion lets us modify the sectors array in the constructor
        require(sectors is ArrayList)

        val postLoad = ArrayList<(GameMap) -> Unit>()

        for ((columnNumber, columnElem) in elem.getChildren("column").withIndex()) {
            val column = ArrayList<SectorInfo>()
            for ((columnIndex, sectorElem) in columnElem.getChildren("sectorInfo").withIndex()) {
                val sector = deserialiseSectorInfo(sectorElem, refs, columnNumber, columnIndex, postLoad)
                column += sector
            }
            sectors += column
        }

        // This is required to link up all the neighbouring sectors, since they're
        // specified by their index in the next column.
        for (fn in postLoad) {
            fn(this)
        }
    }

    class SpecialEvents(
        /**
         * The very first event that appears in the first beacon of the game.
         */
        val startGame: IEvent,

        /**
         * The event to be used when we've run out of events, but still have more beacons
         * to be filled. After modifying the vanilla FTL event XMLs, this is hardcoded
         * to the 'NEUTRAL' event/eventList, so we should obviously behave likewise.
         *
         * Note that I haven't confirmed these should *only* be used after running out of
         * other events - it's perfectly possible the base game peppers these in on a
         * random chance.
         *
         * The filler will be resolved once per element, so if it's an [EventList] each beacon
         * will a random (though certainly not guaranteed to be unique) event.
         */
        val filler: IEvent,

        /**
         * The event to show on regular (non-nebula-covered) exit beacons. This seems
         * to be the same for all sectors.
         *
         * Whether this or [exitNebula] is used depends on whether the exit beacon
         * is situated in a nebula itself, not whether the sector type is a nebula.
         */
        val exit: IEvent,

        val exitNebula: IEvent,

        /**
         * This is the event the player sees when they jump to the federation base
         * in the last stand, without the flagship present.
         */
        val fedBase: IEvent
    )

    /**
     * This stores the information about an available sector as it appears
     * on the sector map.
     */
    inner class SectorInfo(
        /**
         * The 0-7 index of this column throughout the game - this sets
         * the sector's X position.
         */
        val columnNumber: Int,

        /**
         * The index of this sector within it's column - this sets it's Y position.
         *
         * Row doesn't seem like the appropriate word since they're not
         * in a grid, but it's pretty much that.
         */
        val columnIndex: Int,

        /**
         * The exact type of sector.
         */
        var type: SectorType,

        val sectorClass: SectorClass
    ) : ISerialReferencable {
        /**
         * The sectors the player can jump to from this one.
         */
        val nextSectors = ArrayList<SectorInfo>()

        // Note we don't store the actual Sector object here - that's
        // only needed when the player is inside it, so it can be stored
        // separately and discarded when not in use to save memory.

        fun saveToXML(elem: Element, refs: ObjectRefs) {
            SaveUtil.addObjectId(elem, refs, this)
            elem.setAttribute("type", type.name)
            elem.setAttribute("class", sectorClass.name)

            // There's a  lot of SectorInfo objects, so put the next sectors
            // in attributes to save space.
            require(nextSectors.size <= 2)
            SaveUtil.addAttrInt(elem, "next1", nextSectors.getOrNull(0)?.columnIndex)
            SaveUtil.addAttrInt(elem, "next2", nextSectors.getOrNull(1)?.columnIndex)
        }
    }

    // Since the sector map relies on the identity of SectorInfo objects,
    // other classes shouldn't be deserialising their own copies.
    private fun deserialiseSectorInfo(
        elem: Element, refs: RefLoader,
        columnNumber: Int, columnIndex: Int,
        postLoad: ArrayList<(GameMap) -> Unit>
    ): SectorInfo {
        val typeName = elem.getAttributeValue("type")
        val sectorClass = SectorClass.valueOf(elem.getAttributeValue("class"))

        val type = sectorTypes[typeName] ?: error("Missing sector type '$typeName'")
        val sector = SectorInfo(columnNumber, columnIndex, type, sectorClass)

        for (i in 1..2) {
            // The index in the column of the next beacon is specified
            val index = SaveUtil.getAttrIntOrNull(elem, "next$i") ?: continue
            postLoad += {
                val nextColumn = it.sectors[columnNumber + 1]
                sector.nextSectors += nextColumn[index]
            }
        }

        SaveUtil.registerObjectId(elem, refs, sector)
        return sector
    }

    /**
     * The broad types of sector, which determines the colour of the sector on the map.
     */
    enum class SectorClass {
        CIVILIAN,
        HOSTILE,
        NEBULA;

        companion object {
            fun random(rand: Random): SectorClass {
                // 20% chance of a nebula
                if (rand.nextInt(5) == 0) {
                    return NEBULA
                }

                // Otherwise, 60% civilian and 40% hostile
                return if (rand.nextInt(10) < 4) HOSTILE else CIVILIAN
            }
        }
    }
}

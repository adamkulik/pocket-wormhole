package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.Datafile
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.InGameState.GameContent
import xyz.znix.xftl.mapChildrenText
import xyz.znix.xftl.requireAttributeValue
import xyz.znix.xftl.savegame.ISerialReferencable
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.vanillasave.VanillaRng
import kotlin.math.abs
import kotlin.random.Random

/**
 * Represents the overall map of the game. This loads and parses the sector data (which defines all the
 * different types of sectors) and generates a random set of them to be used in the sector map.
 */
class GameMap private constructor(df: Datafile, private val eventManager: EventManager, enableAE: Boolean) {

    /**
     * Vanilla's event creation, to lay out sectors like vanilla. It keeps
     * vanilla's state for the run (unique events, used text and crew names).
     */
    private val vanillaEvents by lazy { VanillaEventWalker(df, enableAE) }
    private val aeEnabled = enableAE

    /**
     * The first sector's layout seed, when the tree was generated from a seed:
     * vanilla takes it from the same random stream, right after the tree.
     */
    var firstLayoutSeed: Int? = null

    /**
     * The seed this map's sector tree was generated from, used by the
     * vanilla-format save writer. Callers that seed the generation should
     * set this to the same value so the tree can be regenerated from a
     * vanilla-format save.
     */
    var generationSeed: Int = Random.nextInt()

    private val sectorClasses = HashMap<SectorClass, List<SectorType>>()
    // Loaded on first use: the AE-only sector types reference AE events,
    // which aren't loaded at all while Advanced Edition content is off.
    private val sectorTypes = HashMap<String, Lazy<SectorType>>()

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
            // Without AE content, its sector lists aren't used (and would
            // load the AE sector types).
            if (!enableAE && name.startsWith(BlueprintManager.AE_PREFIX))
                continue

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
                sectorTypes[it]?.value ?: error("Missing sector $it specificed in category $name")
            }
        }

        // Apply the AE overrides
        if (enableAE) {
            sectorClasses.putAll(sectorClassesAE)
        }
    }

    /**
     * Create a new game map from [seed], the way vanilla does: the same seed
     * (a vanilla save's sector tree seed) gives the same sector tree as in
     * vanilla FTL 1.6.14 - the same columns, links, sector classes and types.
     *
     * The draws are vanilla's, in vanilla's order: the start sector's class
     * (then replaced by CIVILIAN_SECTOR), and for each of the six middle
     * columns its size (2-4, not the previous column's size, two after the
     * start), each new sector's class as the column is linked up, and then
     * each sector's type and name in turn.
     */
    constructor(df: Datafile, eventManager: EventManager, enableAE: Boolean, seed: Int)
            : this(df, eventManager, enableAE) {

        // Kept for the vanilla-format save writer, which stores it.
        generationSeed = seed
        val rng = VanillaRng(seed)

        // This assertion lets us modify the sectors array in the constructor
        require(sectors is ArrayList)

        // Vanilla: 20% nebula, 40% civilian, 40% hostile
        fun rollClass(): SectorClass {
            val roll = rng.nextInt(10)
            return when {
                roll < 2 -> SectorClass.NEBULA
                roll > 5 -> SectorClass.HOSTILE
                else -> SectorClass.CIVILIAN
            }
        }

        // Add the starting sector. Vanilla rolls a class for it first, and
        // then makes it a civilian sector anyway.
        rollClass()
        val startingType = sectorTypes["CIVILIAN_SECTOR"]?.value ?: error("Cannot find CIVILIAN_SECTOR for first sector")
        val startingSector = SectorInfo(0, 0, startingType, SectorClass.CIVILIAN)
        sectors.add(listOf(startingSector))

        // Keep track of what sector types we've used, to avoid duplicating
        // sectors like homewords that are unique.
        val usedSectorTypes = HashSet<SectorType>()

        // Add the six intermediate columns
        for (columnNum in 1..6) {
            val lastColumn = sectors.last()

            // Choose how many sectors there will be in this column, choosing
            // a number different to the previous one. The column after the
            // start sector joins it and thus always has two (but its size is
            // still rolled).
            var numInColumn: Int
            do {
                numInColumn = rng.nextInt(3) + 2
            } while (numInColumn == lastColumn.size)
            if (lastColumn.size == 1) {
                numInColumn = 2
            }

            // Create the sectors (rolling their classes) in vanilla's order,
            // which is also their order in the column, and link them up.
            // See sector-map for more information on the shapes.
            val classes = ArrayList<SectorClass>()
            val links = ArrayList<Pair<Int, Int>>() // previous column index to new index
            if (lastColumn.size == 4 && numInColumn == 2) {
                for (i in 0 until 2) {
                    classes += rollClass()
                    links += Pair(i * 2, i)
                    links += Pair(i * 2 + 1, i)
                }
            } else if (lastColumn.size == 2 && numInColumn == 4) {
                for (i in 0 until 4) {
                    classes += rollClass()
                    links += Pair(i / 2, i)
                }
            } else {
                // This should only happen when we change size by one: each
                // sector links to the one level with it and the next one
                // down, except at the edge of the column with more sectors.
                require(abs(lastColumn.size - numInColumn) == 1)

                // The last sector the previous sector links to, which the
                // next one shares.
                var shared = -1
                for (i in lastColumn.indices) {
                    if (shared != -1)
                        links += Pair(i, shared)
                    if (i == lastColumn.size - 1 && numInColumn <= lastColumn.size)
                        break

                    classes += rollClass()
                    links += Pair(i, classes.size - 1)

                    // A bigger column has one more sector, off the first one
                    if (i == 0 && lastColumn.size < numInColumn) {
                        classes += rollClass()
                        links += Pair(0, classes.size - 1)
                    }
                    shared = classes.size - 1
                }
            }

            // Then pick each sector's type, out of the ones permitted at this
            // point, and its name.
            val column = ArrayList<SectorInfo>()
            for ((i, sectorClass) in classes.withIndex()) {
                val allTypes = sectorClasses[sectorClass] ?: error("No sectors for sector class $sectorClass")

                val availableTypes = allTypes.filter {
                    // Gated to later sectors
                    if (columnNum < it.minSector)
                        return@filter false

                    // If this is a unique sector, make sure it's not already been used
                    if (it.unique && usedSectorTypes.contains(it))
                        return@filter false

                    return@filter true
                }

                val type = availableTypes[rng.nextInt(availableTypes.size)]
                if (type.unique)
                    usedSectorTypes.add(type)

                // Vanilla picks a name out of the sector type's name list here
                // (one each in vanilla - the text ids SectorType loads).
                rng.next()

                column.add(SectorInfo(columnNum, i, type, sectorClass))
            }

            for ((from, to) in links) {
                lastColumn[from].nextSectors += column[to]
            }

            sectors.add(column)
        }

        // Add sector 8
        val finalType = sectorTypes["FINAL"]?.value ?: error("Cannot find FINAL sector for the boss fight")
        val finalSector = SectorInfo(7, 0, finalType, SectorClass.HOSTILE)
        sectors.add(listOf(finalSector))

        // ... and link up all the sectors from the previous column, so it's accessible
        for (sector in sectors[sectors.size - 2]) {
            sector.nextSectors += finalSector
        }

        firstLayoutSeed = rng.next()
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

        sectorTypes[name] = lazy {
            val sector = SectorType(eventManager, elem)
            check(name == sector.name)
            sector
        }
    }

    /**
     * Generate a sector, laid out the way vanilla does it from [layoutSeed] (a
     * vanilla save's, or a new random one). [quests] are the ones delayed
     * from the last sector: vanilla places them last, on beacons of its choice.
     */
    @JvmOverloads
    fun generateSector(sectorInfo: SectorInfo, game: InGameState, layoutSeed: Int? = null, quests: List<Event> = emptyList()): Sector {
        val seed = layoutSeed
            ?: firstLayoutSeed?.takeIf { sectorInfo.columnNumber == 0 }
            ?: Random.nextInt()

        // Vanilla redraws crew names that are in use, the player's crew's too
        for (crew in game.playerCrew ?: emptyList()) {
            if (crew.info.name !in vanillaEvents.usedNames) vanillaEvents.usedNames += crew.info.name
        }

        // The first sector's events are STANDARD_SPACE's in vanilla
        val typeName = if (sectorInfo.columnNumber == 0) "STANDARD_SPACE" else sectorInfo.type.name
        val layout = VanillaSectorLayout(
            vanillaEvents, seed, typeName, sectorInfo.columnNumber,
            game.difficulty == Difficulty.HARD, aeEnabled,
            quests.map { it.deserialisationId }
        )
        val events = layout.beacons.map { b ->
            when {
                b === layout.start && sectorInfo.columnNumber == 0 -> specialEvents.startGame.resolve()
                else -> b.event?.let { eventManager.vanillaEvent(it) } ?: specialEvents.filler.resolve()
            }
        }
        val sector = Sector(sectorInfo, layout, seed, events)
        layout.flagship?.let { f ->
            sector.bosses.add(FlagshipBoss(sector, game, sector.beacons[layout.beacons.indexOf(f)]))
        }
        for ((i, b) in layout.questBeacons) {
            val beacon = sector.beacons[layout.beacons.indexOf(b)]
            beacon.hasQuest = true
            beacon.event = quests[i]
            sector.placedQuests += quests[i]
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

        val type = sectorTypes[typeName]?.value ?: error("Missing sector type '$typeName'")
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
        NEBULA
    }
}

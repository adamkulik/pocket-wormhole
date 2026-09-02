package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.game.BossManager
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Represents an in-game sector. Handles the placement of the beacons within it.
 */
class Sector {
    val info: GameMap.SectorInfo

    /**
     * The zero-indexed position of this sector in the sector map. Zero is the starting
     * sector, 7 is the last stand.
     */
    val sectorNumber: Int get() = info.columnNumber

    val type: SectorType get() = info.type

    val isLastStand: Boolean get() = info.type.name == "FINAL"

    val beacons = ArrayList<Beacon>()

    // The start and finish beacon. In the final sector, the finish
    // beacon represents the location of the rebel base.
    val startBeacon: Beacon
    val finishBeacon: Beacon

    // The position of the centre of the danger zone circle.
    // See doc/sector-map (heading 'Rebel fleet advance') for more
    // information about this.
    val dangerZoneCentre: Point

    /**
     * This is added to by the <modifyPursuit> event tag.
     *
     * A positive number means the pursuit is doubled for that many
     * jumps, while a negative number means there's no pursuit for
     * that many jumps.
     */
    var fleetAdvanceModifier: Int = 0

    /**
     * If true, all beacons are displayed with their full information
     * visible (environmental hazards, ship presence, store/distress
     * labels, etc).
     *
     * This is set by events with the <reveal_map/> tag.
     */
    var mapRevealed: Boolean = false

    /**
     * The boss ships present in this sector.
     *
     * Without mods this is either empty or contains the flagship, but mods can
     * theoretically add multiple bosses.
     */
    val bosses = ArrayList<BossManager>()

    // Note there's another constructor for deserialising a sector
    // from XML down at the bottom of the class.

    /**
     * Randomly generate a new sector.
     */
    constructor(
        info: GameMap.SectorInfo,

        /**
         * The list of events that should be used in this sector. Each beacon will be assigned an event from
         * this list, but no two beacons will share an event (unless that event appears twice in the list).
         *
         * This assignment is random, and the order of the items in the list does not matter. If there are
         * more events in the list than beacons in the sector, a random set of the events will go unused.
         *
         * If the list of events is shorter than the number of beacons we generate, those beacons will all
         * have the filler event.
         */
        events: List<Event>,

        specialEvents: GameMap.SpecialEvents,
        difficulty: Difficulty
    ) {
        this.info = info

        val eventPool = ArrayDeque(events.shuffled())

        val rand = Random.Default

        // Generate a random 6x6 grid. Each beacon to be placed will be offset from one position on the grid.
        val grid = ArrayList<IPoint>()
        for (x in 0 until GRID_SIZE.x) {
            for (y in 0 until GRID_SIZE.y) {
                grid += Point(x, y)
            }
        }

        // See https://twitter.com/subsetgames/status/1234309658854084608
        // In vanilla FTL, the map is split into a 6x4 grid, each cell may or
        // may not contain a beacon.

        val cells = HashMap<Beacon, IPoint>()

        var tmpStartBeacon: Beacon? = null
        var tmpFinishBeacon: Beacon? = null

        var skipped = 0
        val beaconCount = GRID_SIZE.x * GRID_SIZE.y - rand.nextInt(3)
        for (i in 1..beaconCount) {
            val gridPos = grid.random()
            grid.remove(gridPos)

            // There's a 20% chance to omit a given beacon. See doc/sector-map.
            if (rand.nextInt(5) == 0) {
                // Find the number of beacons that haven't been skipped.
                // -1 here so we don't count the one we're currently generating.
                val alreadyGenerated = i - 1 - skipped

                // Use this to avoid skipping >= 20% of the beacons.
                // (Note 20% is n/4 since n doesn't include skipped)
                if (skipped == 0 || skipped < alreadyGenerated / 4) {
                    skipped++
                    continue
                }
            }

            val pos = Point(gridPos.x * CELL_SIZE.x, gridPos.y * CELL_SIZE.y)
            pos.x += rand.nextInt(PADDING, CELL_SIZE.x - PADDING)
            pos.y += rand.nextInt(PADDING, CELL_SIZE.y - PADDING)

            // Limit y to at most 415 so it cleanly fits on the map
            pos.y = min(pos.y, 415)

            // In the two top-left-most cells, limit the Y to at least 30
            // to keep space clear for the next sector button.
            if (gridPos.y == 0 && gridPos.x >= 4) {
                pos.y = max(pos.y, 30)
            }

            var event: Event? = null
            var isFinish = false
            var isStart = false

            // If we're at the right-most side of the map, add an exit beacon.
            // Since we're using the grid cells in a random order, we can
            // just add this in the first time we see such a beacon.
            // We also re-use this to spawn the rebel base in the last stand.
            // (Note this applies to the last two columns, which is how you can
            //  get obnoxiously early exit beacons)
            // TODO support nebula exits.
            val exitRange = when {
                !isLastStand -> 4..5
                difficulty == Difficulty.HARD -> 3..4
                else -> 2..3
            }
            if (gridPos.x in exitRange && tmpFinishBeacon == null) {
                event = specialEvents.exit.resolve()
                isFinish = true

                if (isLastStand) {
                    event = specialEvents.fedBase.resolve()
                }
            }

            // Same goes for the start beacon
            if (gridPos.x == 0 && tmpStartBeacon == null) {
                event = type.startEvent.resolve()
                isStart = true

                // If this is the very first beacon in the game, it's replaced
                // with the START_GAME event.
                if (sectorNumber == 0) {
                    event = specialEvents.startGame.resolve()
                }
            }

            // Otherwise, just use a standard beacon from our event pool - or
            // if we ran out of those, use a filler event.
            if (event == null) {
                event = eventPool.pollFirst() ?: specialEvents.filler.resolve()
            }

            val beacon = Beacon(
                pos.const, event,
                isFinish && !isLastStand,
                isFinish && isLastStand,
                Random.nextInt()
            )
            require(beacon.gridPos posEq gridPos)
            beacons += beacon
            cells[beacon] = gridPos

            if (isFinish)
                tmpFinishBeacon = beacon
            if (isStart)
                tmpStartBeacon = beacon
        }

        // We'll check in GameMap whether this sector has a path from start
        // to end, so we don't need to do anything like that here.

        for (b in beacons) {
            // Find the position of our beacon on the grid
            val bPos = cells[b]!!

            val neighbours = beacons.filter {
                // Beacons must be adjacent or on a diagonal on the grid.
                if (cells[it]!!.distToSq(bPos) > 2)
                    return@filter false

                // Beacons must be within 165px of each other (see doc/sector-map)
                if (it.pos.distToSq(b.pos) > CONNECTION_DISTANCE * CONNECTION_DISTANCE)
                    return@filter false

                // Don't link beacons to themselves
                return@filter it != b
            }
            b.bindSector(this, neighbours)
        }

        startBeacon = tmpStartBeacon ?: error("Failed to place a starting beacon!")
        finishBeacon = tmpFinishBeacon ?: error("Failed to place a finish beacon!")

        dangerZoneCentre = Point(-959, rand.nextInt(50, 300))

        // The flagship is set up by GameMap.generateSector
    }

    fun getFleetAdvanceFor(beacon: Beacon): Int {
        val isNebula = beacon.environmentType.isNebula

        val base = when {
            // Non-nebula beacons use normal advance
            !isNebula -> DANGER_ZONE_ADVANCE

            // Nebula beacons in normal sectors use half the normal advance
            info.sectorClass != GameMap.SectorClass.NEBULA -> DANGER_ZONE_ADVANCE / 2

            // In nebula beacons, they use 80% of the normal advance.
            else -> (DANGER_ZONE_ADVANCE * 0.8f).toInt()
        }

        return when {
            fleetAdvanceModifier < 0 -> 0
            fleetAdvanceModifier > 0 -> base * 2
            else -> base
        }
    }

    /**
     * Attempt to add a quest to this sector.
     *
     * This may fail if it can't find space for the event, there isn't
     * anywhere to put it where the player can reach it before the fleet
     * overruns it, etc.
     *
     * If [ignoreDistance] is not set, then beacons that the player can't
     * reach before they're overtaken will not be used.
     */
    fun addQuest(currentBeacon: Beacon, questEvent: Event, ignoreDistance: Boolean = false): Boolean {
        // See doc/sector-map for how this works.

        val suitable = beacons.filter {
            if (it.visited || it.environmentType.isNebula || it == currentBeacon)
                return@filter false
            if (it.isExit || it.hasQuest || it.hasStore || it.event.isDistressBeacon)
                return@filter false
            if (it.isOvertaken)
                return@filter false

            // Check the path length, to check that we can reach the beacon before
            // the rebel fleet does, and to check there is a valid path.
            val path = findShortestPath(currentBeacon, it) ?: return@filter false

            if (!ignoreDistance) {
                val distToFleet = it.pos.distTo(dangerZoneCentre) - DANGER_ZONE_RADIUS
                val timeUntilFleet = distToFleet / DANGER_ZONE_ADVANCE

                // We must be able to reach the beacon *before* it's overrun
                if (timeUntilFleet <= path.size)
                    return@filter false
            }

            return@filter true
        }

        if (suitable.isEmpty())
            return false

        val beacon = suitable.random()
        beacon.hasQuest = true
        beacon.event = questEvent
        beacon.clearEnvironment()

        return true
    }

    /**
     * Find the shortest path between the two beacons. This contains
     * each beacon the ship must jump to on the way - that is, when
     * there's a path between two different beacons [start] and [end],
     * the path won't contain [start] but will contain [end].
     *
     * If there is no suitable path, null is returned.
     */
    fun findShortestPath(start: Beacon, end: Beacon): List<Beacon>? {
        // Find the path using a simple wavefront system

        val distances = HashMap<Beacon, Int>()
        val wavefront = HashSet<Beacon>()
        val nextWavefront = HashSet<Beacon>()
        wavefront += end

        // Build a map of distances from any given beacon to the end
        while (wavefront.isNotEmpty()) {
            for (beacon in wavefront) {
                // Update this beacon's weight
                val distance: Int = if (beacon == end) {
                    0
                } else {
                    val lowestNeighbour = beacon.neighbours.mapNotNull { distances[it] }.minOrNull()
                    requireNotNull(lowestNeighbour) { "Beacon in path search was in wavefront, but had no set neighbours!" }
                    lowestNeighbour + 1
                }

                if (distances[beacon] == distance)
                    continue

                distances[beacon] = distance
                nextWavefront += beacon.neighbours
            }

            wavefront.clear()
            wavefront += nextWavefront
            nextWavefront.clear()
        }

        // If the start beacon doesn't have a distance, there's no valid path.
        if (!distances.containsKey(start))
            return null

        // Follow the lowest weight to find the path.
        val path = ArrayList<Beacon>()
        var current = start
        while (current != end) {
            current = current.neighbours.sortedBy { distances[it] }.first()
            path += current
        }

        return path
    }

    fun saveToXML(elem: Element, globalRefs: ObjectRefs): ObjectRefs {
        // External stuff shouldn't be able to reference beacons
        val refs = ObjectRefs(globalRefs)

        // Register all the beacons, as they reference each other
        for (beacon in beacons) {
            refs.register(beacon, "beacon")
        }

        SaveUtil.addAttrRef(elem, "sectorInfo", refs, info)

        SaveUtil.addAttrInt(elem, "fleetAdvanceModifier", fleetAdvanceModifier)
        SaveUtil.addAttrBool(elem, "mapRevealed", mapRevealed)
        SaveUtil.addPoint(elem, "dangerZoneCentre", dangerZoneCentre)

        SaveUtil.addAttrRef(elem, "startBeacon", refs, startBeacon)
        SaveUtil.addAttrRef(elem, "finishBeacon", refs, finishBeacon)

        for (boss in bosses) {
            val bossElem = Element("boss")
            SaveUtil.addAttr(bossElem, "type", boss.serialisationType)
            // Note we don't register the boss with ObjectRefs - that's done by InGameState,
            // since it needs the object ID if it's saving the boss ship layout before
            // this sector is serialised.
            SaveUtil.addObjectId(bossElem, globalRefs, boss)
            boss.saveToXML(bossElem, refs)
            elem.addContent(bossElem)
        }

        // Serialise all the beacons
        for (beacon in beacons) {
            val beaconElem = Element("beacon")
            beacon.saveToXML(beaconElem, refs)
            elem.addContent(beaconElem)
        }

        // Serialise all the neighbour connections between beacons. Since there's
        // a huge number of these, we process them here rather than inside the
        // beacons themselves. This lets us use a much more compact (particularly
        // in terms of number of lines) format, which makes the pretty-printed
        // save state easier to read.
        val indexes = HashMap<Beacon, Int>()
        for ((i, beacon) in beacons.withIndex()) {
            indexes[beacon] = i
        }

        val serialisedNeighbours = StringBuilder()
        for ((i, beacon) in beacons.withIndex()) {
            // Only include neighbours that come later in the beacons
            // list - if they come earlier, we'll already have specified
            // this pair of neighbours when serialising the other one.
            val neighbours = beacon.neighbours.filter { i < indexes.getValue(it) }

            if (neighbours.isEmpty())
                continue

            serialisedNeighbours.append(i)
            for (neighbour in neighbours) {
                serialisedNeighbours.append(',')
                serialisedNeighbours.append(indexes.getValue(neighbour))
            }

            serialisedNeighbours.append(' ')
        }

        val neighboursElem = Element("neighbours")
        serialisedNeighbours.trim()
        neighboursElem.addContent(serialisedNeighbours.toString())
        elem.addContent(neighboursElem)

        // The game needs to make a reference to the player's current beacon.
        return refs
    }

    /**
     * Deserialise this sector from XML.
     */
    constructor(elem: Element, refs: RefLoader, game: InGameState, mapRefs: RefLoader) {
        info = SaveUtil.getAttrRefImmediate(elem, "sectorInfo", mapRefs, GameMap.SectorInfo::class.java)!!

        fleetAdvanceModifier = SaveUtil.getAttrInt(elem, "fleetAdvanceModifier")
        mapRevealed = SaveUtil.getAttrBool(elem, "mapRevealed")
        dangerZoneCentre = Point(SaveUtil.getPoint(elem, "dangerZoneCentre"))

        // Use a separate RefLoader to find the start and finish beacons
        val beaconRefs = RefLoader()

        // Load all the beacons
        val neighboursFor = HashMap<Beacon, ArrayList<Beacon>>()
        for (beaconElem in elem.getChildren("beacon")) {
            val beacon = Beacon.loadFromXML(beaconElem, refs, game)
            SaveUtil.registerObjectId(beaconElem, beaconRefs, beacon)
            beacons += beacon
            neighboursFor[beacon] = ArrayList()
        }

        // Calculate all the neighbours for each beacon
        val neighbourTuples = elem.getChildTextTrim("neighbours").split(' ', '\t')
        for (part in neighbourTuples) {
            // Each entry in the neighbours list of a comma-separated list of beacon indexes.
            // The first beacon in each entry is then connected to all the subsequent beacons.
            val partBeacons = part.split(',').map { beacons[it.toInt()] }
            val mainBeacon = partBeacons[0]
            val neighbours = partBeacons.subList(1, partBeacons.size)

            val mainNeighbours = neighboursFor.getValue(mainBeacon)

            for (neighbour in neighbours) {
                val neighbourNeighbours = neighboursFor.getValue(neighbour)

                // Make sure this connection is unique
                require(!mainNeighbours.contains(neighbour))
                require(!neighbourNeighbours.contains(mainBeacon))

                mainNeighbours.add(neighbour)
                neighbourNeighbours.add(mainBeacon)
            }
        }

        for (beacon in beacons) {
            val neighbours = neighboursFor.getValue(beacon)
            beacon.bindSector(this, neighbours)
        }

        beaconRefs.switchToResolveMode()
        startBeacon = SaveUtil.getAttrRefImmediate(elem, "startBeacon", beaconRefs, Beacon::class.java)!!
        finishBeacon = SaveUtil.getAttrRefImmediate(elem, "finishBeacon", beaconRefs, Beacon::class.java)!!

        for (bossElem in elem.getChildren("boss")) {
            val type = SaveUtil.getAttr(bossElem, "type")
            val boss = BossManager.createBySerialisationType(type, this, game)
            SaveUtil.registerObjectId(bossElem, refs, boss)
            boss.loadFromXML(bossElem, refs)
            bosses.add(boss)
        }
    }

    companion object {
        val OFFSET = ConstPoint(45, 40)
        var GRID_SIZE = ConstPoint(6, 4)
        val CELL_SIZE = ConstPoint(110, 110)
        const val PADDING = 10
        const val CONNECTION_DISTANCE = 165

        const val DANGER_ZONE_RADIUS = 767
        const val DANGER_ZONE_RADIUS_SQUARED = DANGER_ZONE_RADIUS * DANGER_ZONE_RADIUS

        // The amount the x position of the danger zone increases by
        // per jump in a normal sector.
        const val DANGER_ZONE_ADVANCE = 64
    }
}

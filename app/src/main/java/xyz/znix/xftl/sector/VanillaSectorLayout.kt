package xyz.znix.xftl.sector

import xyz.znix.xftl.vanillasave.VanillaRng
import kotlin.math.sqrt

/**
 * A sector's beacon map the way vanilla FTL 1.6.14 generates it
 * (StarMap::GenerateMap, GenerateEvents and GenerateNebulas): from the same
 * layout seed it gives the same beacons, start and exit beacons, nebulas and
 * the same event at every beacon. Vanilla saves store the seed, so a loaded
 * vanilla run gets its own map back.
 *
 * In the last stand the exit is the rebel base, further left, and the
 * flagship starts four to six beacons from it.
 */
class VanillaSectorLayout(
    private val walker: VanillaEventWalker,
    seed: Int,

    /** The sector type's name in sector_data.xml (the first sector is STANDARD_SPACE). */
    sectorName: String,

    /** The zero-based sector number, for the crew found in events. */
    level: Int,

    /** Hard difficulty (the last stand's base is placed differently). */
    hard: Boolean,

    /** Advanced Edition content on (the flagship's beacon event). */
    ae: Boolean,

    /** The quests delayed from the last sector, by event name, placed last. */
    quests: List<String> = emptyList(),
) {
    class Beacon(val cell: Int, val x: Int, val y: Int) {
        val links = ArrayList<Beacon>()
        var event: VanillaEventWalker.Made? = null
        var isExit = false
        var nebula = false
        var hasQuest = false
    }

    /** The beacons, in vanilla's order (the order vanilla saves list them in). */
    val beacons = ArrayList<Beacon>()
    val start: Beacon
    val exit: Beacon

    /** The last stand: the rebel base is at [exit]; the flagship starts here. */
    val isLastStand = sectorName == "FINAL"
    val flagship: Beacon?

    /** Where the rebel fleet starts: vanilla's fleetOffset and fleetFudge. */
    val fleetX: Int
    val fleetY: Int

    /** The beacon each of the delayed quests (by index) went to. */
    val questBeacons = HashMap<Int, Beacon>()

    /** Where vanilla draws its nebula images: x, y, width, height. */
    val nebulaRects = ArrayList<IntArray>()

    private val rng = VanillaRng(seed)

    init {
        walker.setSector(sectorName)
        val fleetFudge = rng.next() % 250 + 50
        var foundStart: Beacon
        var foundExit: Beacon
        var foundFlagship: Beacon? = null
        while (true) {
            // Every attempt starts with the text lists whole and the unique
            // events a failed attempt made back
            walker.startAttempt()
            nebulaRects.clear()

            // StarMap::PopulateGrid, cell by cell: maybe skipped, else a beacon
            // somewhere in the cell, linked to the beacons of the cells around it
            beacons.clear()
            val cells = HashMap<Int, MutableList<Beacon>>()
            var tried = 0
            var skipped = 0
            for (cx in 0 until 6) for (cy in 0 until 4) {
                val key = cx * 4 + cy
                if (cells.containsKey(key)) continue
                if (rng.next() % 5 == 0) {
                    skipped++
                    if (tried / skipped > 4) {
                        cells[key] = ArrayList()
                        tried++
                        continue
                    }
                }
                rng.next() // the beacon's animation phase (Location's constructor)
                val bx = cx * 110 + 10 + rng.next() % 90
                var by = (cy * 110 + 10 + rng.next() % 90).coerceAtMost(415)
                if (cx > 3 && cy == 0 && by < 30) by = 30
                val b = Beacon(key, bx, by)
                beacons += b
                cells[key] = arrayListOf(b)
                for (dx in -1..1) for (dy in -1..1) {
                    if (cx + dx !in 0 until 6 || cy + dy !in 0 until 4) continue
                    for (o in cells[(cx + dx) * 4 + cy + dy] ?: continue) {
                        if (o === b) continue
                        val d = sqrt(((o.x - b.x) * (o.x - b.x) + (o.y - b.y) * (o.y - b.y)).toDouble())
                        if (d > 165.0) continue
                        b.links += o
                        o.links += b
                    }
                }
                tried++
            }

            // Every beacon reachable from the first one, or start again
            val seen = HashSet<Beacon>()
            fun visit(b: Beacon) {
                if (seen.add(b)) b.links.forEach { visit(it) }
            }
            visit(beacons[0])
            if (seen.size != beacons.size) continue

            fun cellBeacon(x: Int, y: Int) = cells.getOrPut(x * 4 + y) { ArrayList() }.firstOrNull()

            val s = cells[rng.next() % 4]?.firstOrNull() ?: beacons[0]
            s.event = walker.get(walker.sectorStart[sectorName] ?: "START_BEACON", level, rng)

            // The exit: a random beacon in the two right columns, at least five
            // beacons along the path from the start. The last stand's base is
            // further left, four (hard: five or six) beacons along. 16 tries
            // (shared with the flagship), or start again.
            var tries = 0
            var e: Beacon? = null
            val (lo, hi) = if (!isLastStand) 4 to 100 else if (hard) 4 to 7 else 3 to 5
            while (tries < 16) {
                var cand: Beacon?
                do {
                    val ey = rng.next() % 4
                    var ex = rng.next() % 2 + 4
                    if (isLastStand) ex = rng.next() % 2 + (if (hard) 3 else 2)
                    cand = cellBeacon(ex, ey)
                } while (cand == null)
                tries++
                val len = hops(s, cand!!) + 1
                if (len > lo && len < hi) {
                    e = cand
                    break
                }
            }
            if (e == null) continue
            e.isExit = true
            e.event = walker.get("FINISH_BEACON", level, rng)
            generateEvents(walker.sectorEvents[sectorName].orEmpty(), level)

            if (isLastStand) {
                rng.next() // a random beacon's flag, unused here
                // The flagship: a beacon of the two right columns four to six
                // beacons from the base, on the exit's remaining tries
                var f: Beacon? = null
                do {
                    var cand: Beacon?
                    do {
                        val fy = rng.next() % 4
                        val fx = rng.next() % 2 + 4
                        cand = cellBeacon(fx, fy)
                    } while (cand == null)
                    tries++
                    if (hops(cand!!, e) + 1 in 4..6) f = cand
                } while (f == null && tries < 16)
                if (f == null) continue

                // StarMap::TurnIntoFleetLocation on the flagship's beacon
                f.event = walker.get(
                    if (f.nebula) "FLEET_EASY_NEBULA" else if (ae) "FLEET_EASY_DLC" else "FLEET_EASY", level, rng
                )
                e.event = walker.get("FEDERATION_BASE", level, rng)
                s.event = walker.get("LAST_STAND_START", level, rng)
                foundFlagship = f
            }

            foundStart = s
            foundExit = e
            break
        }
        start = foundStart
        exit = foundExit
        flagship = foundFlagship
        if (isLastStand) {
            fleetX = 60
            fleetY = 200
        } else {
            fleetX = -959
            fleetY = fleetFudge
        }

        // The rock homeworld's crystal beacon counts as a quest beacon
        for (b in beacons) if (b.event?.name == "ROCK_CRYSTAL_BEACON") b.hasQuest = true

        // At the end of GenerateMap, the quests the last sector had no room
        // for: StarMap::AddQuest, without the rebel fleet check
        quests.forEachIndexed { i, name -> addQuest(name, level)?.let { questBeacons[i] = it } }
    }

    /**
     * StarMap::AddQuest: the quest's event is made first, then one draw picks
     * a beacon (in vanilla's order) that isn't the start, the exit, in a
     * nebula, a quest already, a store or a distress beacon.
     */
    private fun addQuest(name: String, level: Int): Beacon? {
        val made = walker.get(name, level, rng) ?: return null
        val suitable = beacons.filter { b ->
            if (b === start || b.isExit || b.nebula || b.hasQuest) return@filter false
            val e = b.event?.let { walker.element(it) }
            e == null || (e.getChild("store") == null && e.getChild("distressBeacon") == null)
        }
        if (suitable.isEmpty()) return null
        val b = suitable[Integer.remainderUnsigned(rng.next(), suitable.size)]
        b.hasQuest = true
        b.event = made
        return b
    }

    private fun hops(from: Beacon, to: Beacon): Int {
        val dist = HashMap<Beacon, Int>()
        val queue = ArrayDeque<Beacon>()
        dist[from] = 0
        queue += from
        while (queue.isNotEmpty()) {
            val b = queue.removeFirst()
            for (n in b.links) if (n !in dist) {
                dist[n] = dist[b]!! + 1
                queue += n
            }
        }
        return dist[to] ?: -1
    }

    /** RandomAmount: min..max (no chance on sector events). */
    private fun amount(min: Int, max: Int) = if (max == 0) min else min + rng.next() % (max + 1 - min)

    /**
     * StarMap::GenerateEvents: nebulas first (all the sector's NEBULA* events,
     * placed with nebula images), then the other events in sector_data order on
     * random beacons still without one, then NEUTRAL on the rest.
     */
    private fun generateEvents(list: List<Triple<String, Int, Int>>, level: Int) {
        val pool = beacons.toMutableList()
        val nebNames = ArrayList<String>()
        val rest = ArrayList<Triple<String, Int, Int>>()
        for (e in list) {
            if ("NEBULA" in e.first) repeat(amount(e.second, e.third)) { nebNames += e.first }
            else rest += e
        }
        if (nebNames.isNotEmpty()) generateNebulas(nebNames, level)

        for (e in rest) {
            val n = amount(e.second, e.third)
            if (n > 0) {
                if (pool.isEmpty()) return
                var made = 0
                while (true) {
                    val b = pool.removeAt(rng.next() % pool.size)
                    if (b.event == null) {
                        made++
                        b.event = walker.get(e.first, level, rng)
                    }
                    if (made >= n) break
                    if (pool.isEmpty()) return
                }
            }
            if (pool.isEmpty()) return
        }
        for (b in pool) if (b.event == null) b.event = walker.get("NEUTRAL", level, rng)
    }

    /**
     * StarMap::GenerateNebulas: nebula images placed around a random beacon,
     * then next to the ones already placed; the beacons inside get the nebula
     * events (the exit becomes FINISH_BEACON_NEBULA).
     */
    private fun generateNebulas(nebNames: MutableList<String>, level: Int) {
        val imgs = if (nebNames.size < 6) NEBULA_SMALL else NEBULA_LARGE
        fun img() = imgs[Integer.remainderUnsigned(rng.next(), imgs.size)]
        var im = img()
        val cand = beacons.filter { !it.nebula }.toMutableList()
        while (cand.size < nebNames.size + 4 && nebNames.isNotEmpty())
            nebNames.removeAt(Integer.remainderUnsigned(rng.next(), nebNames.size))
        if (cand.isEmpty() || nebNames.isEmpty()) return
        var c = cand[rng.next() % cand.size]
        var x = c.x - im.first / 2
        var y = c.y - im.second / 2
        var fails = 0
        do {
            var any = false
            val it = cand.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (!(x + 5 < b.x && b.x < x + im.first - 5 && y + 5 < b.y && b.y < y + im.second - 5)) continue
                b.nebula = true
                if (b.event == null) {
                    val name = if (nebNames.isEmpty()) "NEBULA"
                    else nebNames.removeAt(Integer.remainderUnsigned(rng.next(), nebNames.size))
                    b.event = walker.get(name, level, rng)
                } else if (b.isExit) {
                    b.event = walker.get("FINISH_BEACON_NEBULA", level, rng)
                }
                it.remove()
                any = true
            }
            if (any) nebulaRects += intArrayOf(x, y, im.first, im.second) else fails++
            // Vanilla would divide by zero here; we just stop
            if (cand.isEmpty()) break
            if (fails < 21 && nebulaRects.isNotEmpty()) {
                val p = nebulaRects[Integer.remainderUnsigned(rng.next(), nebulaRects.size)]
                im = img()
                x = p[0] + rng.next() % (p[2] + im.first) - im.first
                y = rng.next() % (p[3] + im.second) + p[1] - im.second
            } else {
                fails = 0
                c = cand[rng.next() % cand.size]
                x = c.x - im.first / 2
                y = c.y - im.second / 2
            }
        } while (nebNames.isNotEmpty())
    }

    companion object {
        /** Nebula images on the beacon map (width, height): small ones for fewer than 6 nebula events. */
        private val NEBULA_SMALL = listOf(119 to 63, 67 to 110, 89 to 67, 117 to 108)
        private val NEBULA_LARGE = listOf(250 to 234, 200 to 250, 250 to 200)
    }
}

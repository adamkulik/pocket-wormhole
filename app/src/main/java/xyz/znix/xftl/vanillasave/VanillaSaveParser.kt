package xyz.znix.xftl.vanillasave

import xyz.znix.xftl.game.Difficulty

/** A parsed vanilla continue.sav (format 11). */
class VanillaSaveData {
    var advanced: Boolean = true
    var difficulty: Difficulty = Difficulty.NORMAL

    // Header
    var shipName: String = ""
    var blueprintName: String = ""
    var sectorNumber: Int = 0 // 0-based; vanilla's 'sector' field is this + 1
    val stateVars = HashMap<String, Int>()

    // Player ship
    val ship = ShipData()

    // Cargo: blueprint ids
    val cargo = ArrayList<String>()

    // Seeds / fleet
    var sectorTreeSeed: Int = 0
    var sectorLayoutSeed: Int = 0
    var fleetOffset: Int = 0
    var fleetFudge: Int = 0
    var pursuitMod: Int = 0

    // The encounter tail, preserved verbatim for re-export
    var tail: ByteArray = ByteArray(0)

    // Map
    var currentBeaconId: Int = 0
    var hiddenCrystalWorld: Boolean = false
    val beacons = ArrayList<VanillaBeacon>()
    val questEvents = ArrayList<Pair<String, Int>>() // event id to beacon index
    val distantQuests = ArrayList<String>()

    fun isVisited(index: Int): Boolean =
        index in beacons.indices && beacons[index].visitCount > 0

    class ShipData {
        var blueprint: String = ""
        var name: String = ""
        var gfx: String = ""
        var hull: Int = 30
        var fuel: Int = 0
        var droneParts: Int = 0
        var missiles: Int = 0
        var scrap: Int = 0
        var reactor: Int = 5
        var ftlProgressTicks: Int = 0

        val crew = ArrayList<VanillaCrew>()
        val systems = HashMap<String, VanillaSystem>() // keyed by vanilla slot name
        var cloneProgress: Int = 0
        var cloneGoal: Int = 0
        var shieldsActive: Int = 0
        var shieldsRechargeTicks: Int = 0
        var superShield: Int = 0
        var superShieldMax: Int = 5
        var cloakTicks: Int = 0
        var cloakGoal: Int = 0
        var cloakActive: Boolean = false
        val breaches = ArrayList<Triple<Int, Int, Int>>() // room, square, health
        val roomOxygen = ArrayList<Float>()
        val doors = ArrayList<VanillaDoor>()
        val weapons = ArrayList<Pair<String, Boolean>>() // id to armed
        val drones = ArrayList<VanillaDrone>()
        val augments = ArrayList<String>()
    }

    class VanillaCrew {
        var name: String = ""
        var race: String = ""
        var health: Int = 100
        var spriteX: Int = 0
        var spriteY: Int = 0
        var roomId: Int = 0
        var roomSquare: Int = 0
        var isPlayer: Boolean = true
        var cloneReady: Int = 0
        var savedSquare: Int = 0
        var savedRoom: Int = 0
        val skills = HashMap<String, Int>() // slot name to xp actions
        var colour: Int = 0
    }

    class VanillaSystem {
        var capacity: Int = 0
        var power: Int = 0
        var damaged: Int = 0
        var ion: Int = 0
        var deionisationTicks: Int = 0
        var repairProgress: Int = 0
        var damageProgress: Int = 0
        var hackLevel: Int = 0
        var hacked: Boolean = false
    }

    class VanillaDoor {
        var maxHealth: Int = 4
        var health: Int = 4
        var open: Boolean = false
    }

    class VanillaDrone {
        var name: String = ""
        var armed: Boolean = false
        var health: Int = 20
    }

    class VanillaBeacon {
        var visitCount: Int = 0
        var seen: Boolean = false
        var enemyPresent: Boolean = false
        var shipEventId: String = ""
        var autoBlueprintId: String = ""
        var shipEventSeed: Int = 0
        var fleetPresence: Int = 0
        var underAttack: Boolean = false
        var storePresent: Boolean = false
        var store: VanillaStore? = null
    }

    class VanillaStore {
        val shelves = ArrayList<VanillaShelf>()
        var fuel: Int = 0
        var missiles: Int = 0
        var droneParts: Int = 0
    }

    class VanillaShelf {
        var type: Int = 0
        val items = ArrayList<VanillaItem>()
    }

    class VanillaItem {
        var available: Boolean = true
        var name: String = ""
        var extra: Int = 0
    }
}

/** Parses vanilla save bytes into [VanillaSaveData]. The unmodelled
 * encounter tail after unknown_mu is ignored. */
object VanillaSaveParser {
    /** Optional stage tracer for debugging (position, stage). */
    var trace: ((Int, String) -> Unit)? = null

    /**
     * Parses a vanilla save. [layoutFor] must return the ship layout's tile
     * counts per room and door count for the saved blueprint id (the format
     * stores rooms and doors without counts, so the layout resolves them).
     */
    fun parse(data: ByteArray, layoutFor: (String) -> Pair<List<Int>, Int>): VanillaSaveData {
        val r = VanillaSaveReader(data)
        val out = VanillaSaveData()

        val fmt = r.int()
        require(fmt == VanillaSaveFormat.FILE_FORMAT) { "Unsupported vanilla save format $fmt" }
        r.bool() // random_native
        out.advanced = r.bool()
        out.difficulty = when (r.int()) {
            0 -> Difficulty.EASY
            1 -> Difficulty.NORMAL
            else -> Difficulty.HARD
        }
        repeat(4) { r.int() } // lifetime profile stats
        out.shipName = r.string()
        out.blueprintName = r.string()
        out.sectorNumber = r.int() - 1
        r.int() // unknown_beta

        repeat(r.int()) {
            out.stateVars[r.string()] = r.int()
        }

        val (roomTiles, doorCount) = layoutFor(out.blueprintName)
        trace?.invoke(r.pos, "beforeShip")
        readShip(r, out.ship, roomTiles, doorCount)

        trace?.invoke(r.pos, "afterShip/beforeCargo")
        repeat(r.int()) { out.cargo.add(r.string()) }

        trace?.invoke(r.pos, "afterCargo/beforeSeeds")
        out.sectorTreeSeed = r.int()
        out.sectorLayoutSeed = r.int()
        out.fleetOffset = r.int()
        out.fleetFudge = r.int()
        out.pursuitMod = r.int()

        trace?.invoke(r.pos, "afterSeeds/beforeCurrent")
        out.currentBeaconId = r.int()
        r.bool() // waiting
        r.int() // wait_event_seed
        r.string() // unknown_epsilon
        r.bool() // sector_hazard_visible
        r.bool() // flagship visible
        r.int() // flagship hop
        r.bool() // flagship moving
        r.bool() // flagship retreating
        r.int() // flagship base turns

        trace?.invoke(r.pos, "afterBaseTurns/beforeRoute")
        repeat(r.int()) { r.bool() } // route flags
        r.int() // sector_number (duplicate)
        trace?.invoke(r.pos, "afterSectorNumber/beforeBeaconCount")
        out.hiddenCrystalWorld = r.bool()

        repeat(r.int()) {
            val beacon = VanillaSaveData.VanillaBeacon()
            beacon.visitCount = r.int()
            if (beacon.visitCount > 0) {
                r.string() // bg starscape
                r.string() // bg sprite
                r.int(); r.int(); r.int() // sprite pos + rotation
            }
            beacon.seen = r.bool()
            beacon.enemyPresent = r.bool()
            if (beacon.enemyPresent) {
                beacon.shipEventId = r.string()
                beacon.autoBlueprintId = r.string()
                beacon.shipEventSeed = r.int()
            }
            beacon.fleetPresence = r.int()
            beacon.underAttack = r.bool()
            beacon.storePresent = r.bool()
            if (beacon.storePresent) {
                val store = VanillaSaveData.VanillaStore()
                repeat(r.int()) {
                    val shelf = VanillaSaveData.VanillaShelf()
                    shelf.type = r.int()
                    // Vanilla writes all three items unconditionally
                    // (avail=0 = sold out, with the name preserved).
                    repeat(3) {
                        val avail = r.int()
                        if (avail == 0 || avail == 1) {
                            // Vanilla always writes three (avail, name, extra)
                            // triples; a non-0/1 avail is the legacy -1
                            // terminator from older Pocket Wormhole exports.
                            val item = VanillaSaveData.VanillaItem()
                            item.available = avail == 1
                            item.name = r.string()
                            item.extra = r.int()
                            shelf.items.add(item)
                        }
                    }
                    store.shelves.add(shelf)
                }
                store.fuel = r.int()
                store.missiles = r.int()
                store.droneParts = r.int()
                beacon.store = store
            }
            out.beacons.add(beacon)
        }

        repeat(r.int()) {
            val event = r.string()
            out.questEvents.add(event to r.int())
        }
        repeat(r.int()) { out.distantQuests.add(r.string()) }

        // Preserve the encounter tail verbatim (kept for diagnostics and
        // possible future use; the writer now generates its own tail from
        // the template prefix/suffix plus our ship's extended info, since
        // a verbatim donor tail only loads while the ship still matches
        // the donor's loadout). Captured FROM unknown_mu onward (including
        // the mu int), matching VanillaSaveFormat.TEMPLATE_TAIL's
        // convention.
        out.tail = data.copyOfRange(r.pos, data.size)
        return out
    }

    private fun readShip(r: VanillaSaveReader, ship: VanillaSaveData.ShipData, roomTiles: List<Int>, doorCount: Int) {
        ship.blueprint = r.string()
        ship.name = r.string()
        ship.gfx = r.string()

        repeat(r.int()) { r.string(); r.string() } // starting crew (hangar display)
        r.bool() // hostile
        ship.ftlProgressTicks = r.int()
        r.bool() // jumping
        r.int() // jump anim ticks

        trace?.invoke(r.pos, "afterJump")
        ship.hull = r.int()
        ship.fuel = r.int()
        ship.droneParts = r.int()
        ship.missiles = r.int()
        ship.scrap = r.int()

        trace?.invoke(r.pos, "afterResources")
        repeat(r.int()) { ship.crew.add(readCrew(r)) }

        trace?.invoke(r.pos, "afterCrew")
        ship.reactor = r.int()

        for ((slotName, _) in VanillaSaveFormat.SYSTEM_SLOTS) {
            val sys = VanillaSaveData.VanillaSystem()
            sys.capacity = r.int()
            if (sys.capacity > 0) {
                sys.power = r.int()
                sys.damaged = r.int()
                sys.ion = r.int()
                sys.deionisationTicks = r.int()
                sys.repairProgress = r.int()
                sys.damageProgress = r.int()
                r.int() // battery power
                sys.hackLevel = r.int()
                sys.hacked = r.bool()
                r.int(); r.int(); r.int() // temp capacity
                ship.systems[slotName] = sys
            }
        }

        trace?.invoke(r.pos, "afterSystems")
        if ((ship.systems["clonebay"]?.capacity ?: 0) > 0) {
            ship.cloneProgress = r.int()
            ship.cloneGoal = r.int()
            r.int() // doom ticks
        }
        if ((ship.systems["battery"]?.capacity ?: 0) > 0) {
            r.bool(); r.int(); r.int()
        }

        // Shields info (always present)
        ship.shieldsActive = r.int()
        r.int() // energy shield layers
        ship.superShieldMax = r.int()
        ship.shieldsRechargeTicks = r.int()
        r.bool(); r.int() // drop anim
        r.bool(); r.int() // raise anim
        r.bool(); r.int() // energy shield anim
        r.int(); r.int() // unknowns

        if ((ship.systems["cloaking"]?.capacity ?: 0) > 0) {
            r.int(); r.int() // unknowns
            ship.cloakGoal = r.int()
            ship.cloakTicks = r.int()
            ship.cloakActive = ship.cloakTicks > 0
        }

        trace?.invoke(r.pos, "afterConditionals")
        // Rooms: per room (o2, a, b) + 3 ints per tile; the tile values and
        // a/b are transient state we don't consume. The oxygen percentage is
        // kept (0-1 floats for the loader).
        ship.roomOxygen.clear()
        for (tiles in roomTiles) {
            ship.roomOxygen.add(r.int() / 100f)
            r.int(); r.int()
            repeat(tiles) {
                r.int(); r.int(); r.int()
            }
        }

        trace?.invoke(r.pos, "afterRooms")
        val breachCount = r.int()
        repeat(breachCount) {
            val x = r.int()
            val y = r.int()
            val health = r.int()
            ship.breaches.add(Triple(x, y, health))
        }

        // Doors: no count in the file - the layout's door list resolves it.
        ship.doors.clear()
        repeat(doorCount) {
            val door = VanillaSaveData.VanillaDoor()
            door.maxHealth = r.int()
            door.health = r.int()
            r.int() // nominal health
            door.open = r.bool()
            r.bool() // walking through
            r.int() // unknown
            r.int() // unknown
            ship.doors.add(door)
        }

        trace?.invoke(r.pos, "afterBreaches")
        r.int() // cloak_anim_ticks (transient)

        val crystalCount = r.int()
        repeat(crystalCount) {
            repeat(5) { r.int() }
            r.bool(); r.bool(); r.int(); r.bool(); r.int(); r.int(); r.int()
        }

        trace?.invoke(r.pos, "afterCloakCrystals")
        val weaponCount = r.int()
        repeat(weaponCount) {
            val id = r.string()
            val armed = r.bool()
            ship.weapons.add(id to armed)
        }

        trace?.invoke(r.pos, "afterWeapons")
        val droneCount = r.int()
        repeat(droneCount) {
            val drone = VanillaSaveData.VanillaDrone()
            drone.name = r.string()
            drone.armed = r.bool()
            r.bool() // player controlled
            r.int(); r.int(); r.int(); r.int() // body x/y/room/square
            drone.health = r.int()
            ship.drones.add(drone)
        }

        trace?.invoke(r.pos, "afterDrones")
        repeat(r.int()) { ship.augments.add(r.string()) }
    }

    /** The doors have no count in the file; the loader knows the layout's
     * door count and calls this once per door. */
    private fun readCrew(r: VanillaSaveReader): VanillaSaveData.VanillaCrew {
        val crew = VanillaSaveData.VanillaCrew()
        crew.name = r.string()
        crew.race = r.string()
        r.bool() // boarding drone
        crew.health = r.int()
        crew.spriteX = r.int()
        crew.spriteY = r.int()
        crew.roomId = r.int()
        crew.roomSquare = r.int()
        crew.isPlayer = r.bool()
        crew.cloneReady = r.int()
        r.int() // death order
        val tintCount = r.int()
        val tints = ArrayList<Int>()
        repeat(tintCount) { tints.add(r.int()) }
        crew.colour = tints.getOrNull(1) ?: 0
        r.bool() // mind controlled
        crew.savedSquare = r.int()
        crew.savedRoom = r.int()
        // Skills are in vanilla slot order: pilot, engines, shields, weapons,
        // repair, combat.
        val skillNames = listOf("pilot", "engines", "shields", "weapons", "repair", "combat")
        for (skill in skillNames) {
            crew.skills[skill] = r.int()
        }
        r.bool() // male
        repeat(5) { r.int() } // repairs, kills, evasions, jumps, masteries
        r.int() // stun ticks
        r.int() // health boost
        r.int() // clonebay priority
        r.int() // damage boost
        r.int() // unknown lambda
        r.int() // universal death count
        repeat(12) { r.bool() } // skill masteries
        r.int() // unknown
        r.bool(); r.bool() // animation running flags
        repeat(5) { r.int() } // animation timers
        r.int() // unknown
        if (crew.race == "crystal") {
            r.int(); r.int(); r.int() // lockdown recharge
        }
        return crew
    }
}

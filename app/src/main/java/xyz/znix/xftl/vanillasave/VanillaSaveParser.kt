package xyz.znix.xftl.vanillasave

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.weapons.DroneBlueprint

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

    // The session counters behind the final score (vanilla game-state
    // offsets 0x08, 0x30 and 0x58). The save stores them right after the
    // difficulty, in this order; the fourth value there is an unscored
    // statistic we don't model.
    var scoreShipsDefeated: Int = 0
    var scoreBeaconsVisited: Int = 0
    var scoreScrapCollected: Int = 0

    // Seeds / fleet
    var sectorTreeSeed: Int = 0
    var sectorLayoutSeed: Int = 0
    var fleetOffset: Int = 0
    var fleetFudge: Int = 0
    var pursuitMod: Int = 0

    // The encounter tail, preserved verbatim for re-export
    var tail: ByteArray = ByteArray(0)

    /**
     * The ship at the current beacon (the one being fought or talked to), as
     * vanilla saved it, if there is one and its section could be read.
     */
    var nearbyShip: ShipData? = null
    var nearbyShipIsFlagship: Boolean = false

    // The current beacon's hazards
    var redGiant: Boolean = false
    var pulsar: Boolean = false
    var pds: Boolean = false
    var asteroids: Boolean = false
    var nearbyAI: NearbyShipAI? = null

    /** The nearby ship's AI: whether it gave up or is running, and when it will. */
    class NearbyShipAI {
        var surrendered = false
        var escaping = false
        var destroyed = false
        var surrenderThreshold = 0
        var escapeThreshold = -1

        /** Counts down to 0, when the ship jumps away. */
        var escapeTicks = 15000
        var stalemateTriggered = false
        var stalemateTicks = 0
        var boardingAttempts = 0
        var boardersNeeded = 0
    }

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

        /** Each weapon's charge, in ticks, from the save's extended ship info (if it could be read). */
        val weaponCharge = ArrayList<Int>()
        var hacking: VanillaSaveData.HackingState? = null
        val systems = HashMap<String, VanillaSystem>() // keyed by vanilla slot name
        var cloneProgress: Int = 0
        var cloneGoal: Int = 0
        var shieldsActive: Int = 0
        var shieldsRechargeTicks: Int = 0
        var superShield: Int = 0
        var superShieldMax: Int = 5
        var hostile: Boolean = true

        /** Burning tiles: room, tile as in the file (columns first) and the fire's health, 1-100. */
        val fires: MutableList<Triple<Int, Int, Int>> = ArrayList()

        /** Every artillery gun's system: the flagship has one per gun, in its blueprint's order. */
        val artillery: MutableList<VanillaSystem> = ArrayList()
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
        var deathOrder: Int = -1
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
        // Vanilla's temporary capacity modifiers: cap, loss, divisor.
        // Real saves carry (1000, 0, 1) on every system.
        var tempCapacityCap: Int = 0
        var tempCapacityLoss: Int = 0
        var tempCapacityDivisor: Int = 0
    }

    class VanillaDoor {
        var maxHealth: Int = 4
        var health: Int = 4
        var open: Boolean = false
    }

    /** Vanilla's hacking system: the system it targets, its probe and its pulse. */
    class HackingState {
        var targetSystem: Int = -1
        var probeLaunched: Boolean = false
        var probeLanded: Boolean = false
        var targeting: Boolean = false
        var pulseTicks: Int = 0
        var pulseGoalTicks: Int = 0
        var noProbe: Boolean = false
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
    fun parse(data: ByteArray, layoutFor: (String) -> Pair<List<Int>, Int>): VanillaSaveData =
        parse(data, layoutFor) { null }

    /** With [droneTypeOf], the weapons' charge can be read past the ship's drones. */
    fun parse(
        data: ByteArray,
        layoutFor: (String) -> Pair<List<Int>, Int>,
        droneTypeOf: (String) -> DroneBlueprint.DroneType?
    ): VanillaSaveData = parse(data, layoutFor, droneTypeOf) { 1 }

    /** With [artilleryOf], the number of artillery guns on a blueprint, the flagship can be read. */
    fun parse(
        data: ByteArray,
        layoutFor: (String) -> Pair<List<Int>, Int>,
        droneTypeOf: (String) -> DroneBlueprint.DroneType?,
        artilleryOf: (String) -> Int
    ): VanillaSaveData {
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
        // Score counters, written here by vanilla's FUN_00442460:
        // ships defeated, beacons visited, scrap collected, unused.
        out.scoreShipsDefeated = r.int()
        out.scoreBeaconsVisited = r.int()
        out.scoreScrapCollected = r.int()
        r.int()
        out.shipName = r.string()
        out.blueprintName = r.string()
        out.sectorNumber = r.int() - 1
        r.int() // unknown_beta

        repeat(r.int()) {
            out.stateVars[r.string()] = r.int()
        }

        val (roomTiles, doorCount) = layoutFor(out.blueprintName)
        trace?.invoke(r.pos, "beforeShip")
        readShip(r, out.ship, roomTiles, doorCount, artilleryOf(out.blueprintName))

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

        // The encounter and the nearby ship, in the same layout as the
        // player's (FTL Profile Editor's SavedGameParser). Best effort: a
        // save it can't be read from still loads, just without the ship's state.
        try {
            readNearbyShip(r, out, layoutFor, droneTypeOf, artilleryOf)
        } catch (e: Exception) {
            trace?.invoke(r.pos, "nearbyError: $e")
            out.nearbyShip = null
        }
        return out
    }

    private fun readNearbyShip(
        r: VanillaSaveReader, out: VanillaSaveData, layoutFor: (String) -> Pair<List<Int>, Int>,
        droneTypeOf: (String) -> DroneBlueprint.DroneType?,
        artilleryOf: (String) -> Int
    ) {
        r.int() // unknown_mu
        // The encounter: the ship event's seed, the surrender, escape,
        // destroyed, dead crew, got away and last event ids, an unknown int,
        // the text, the affected crew seed and the choices made
        r.int()
        repeat(6) { r.string() }
        r.int()
        r.string()
        r.int()
        val choices = r.int()
        check(choices in 0..64) { "bad encounter" }
        repeat(choices) { r.int() }

        if (r.bool()) readNearbyShipItself(r, out, layoutFor, artilleryOf)

        // The environment, the projectiles in flight, then each ship's
        // extended info: as far as it's modelled (the weapons' charge).
        try {
            out.redGiant = r.bool()
            out.pulsar = r.bool()
            out.pds = r.bool()
            r.int() // vulnerable ships
            out.asteroids = r.bool()
            if (out.asteroids) repeat(5) { r.int() } // asteroid field
            repeat(3) { r.int() } // solar flare fade, havoc, PDS ticks
            skipProjectiles(r)
            if (!readExtendedShipInfo(r, out.ship, droneTypeOf)) return
            out.nearbyShip?.let { readExtendedShipInfo(r, it, droneTypeOf) }
        } catch (e: Exception) {
            trace?.invoke(r.pos, "extendedInfoError: $e")
        }
    }

    /**
     * A ship's extended info: false if it has something that isn't read yet,
     * so what follows can't be found.
     */
    private fun readExtendedShipInfo(
        r: VanillaSaveReader, ship: VanillaSaveData.ShipData, droneTypeOf: (String) -> DroneBlueprint.DroneType?
    ): Boolean {
        for (drone in ship.drones) {
            r.bool(); r.bool() // deployed, armed
            skipDronePod(r, droneTypeOf(drone.name) ?: return false)
        }
        if ((ship.systems["hacking"]?.capacity ?: 0) > 0) {
            val state = VanillaSaveData.HackingState()
            state.targetSystem = r.int()
            state.probeLaunched = r.int() != 0
            state.probeLanded = r.int() != 0
            state.targeting = r.int() != 0
            state.pulseTicks = r.int()
            state.pulseGoalTicks = r.int()
            state.noProbe = r.int() != 0
            ship.hacking = state
            skipDronePod(r, DroneBlueprint.DroneType.HACKING)
        }
        if ((ship.systems["mind"]?.capacity ?: 0) > 0) repeat(2) { r.int() }
        if ((ship.systems["weapons"]?.capacity ?: 0) > 0) {
            val count = r.int()
            check(count == ship.weapons.size) { "weapon modules" }
            val charges = ArrayList<Int>()
            repeat(count) { charges += readWeaponModule(r) }
            ship.weaponCharge.clear()
            ship.weaponCharge += charges
        }
        repeat(ship.artillery.size) { readWeaponModule(r) }
        return r.int() == 0 // drones without a system aren't read yet
    }

    /** A weapon's state; returns its charge, in cooldown ticks. */
    private fun readWeaponModule(r: VanillaSaveReader): Int {
        val charge = r.int()
        repeat(5) { r.int() } // goal, subcooldown and its goal, boost, charge
        repeat(r.int() * 2) { r.int() } // current targets
        repeat(r.int() * 2) { r.int() } // previous targets
        r.bool(); r.bool(); r.int() // autofire, fire when ready, target
        repeat(7) { r.int() } // weapon animation
        r.int(); r.bool(); r.bool() // protract ticks, firing, unknown
        r.int(); repeat(7) { r.int() } // anim charge, charge animation
        r.int() // last projectile id
        skipProjectiles(r)
        return charge
    }

    /** Projectiles in flight: a count, then each one. */
    private fun skipProjectiles(r: VanillaSaveReader) {
        repeat(r.int()) { skipProjectile(r) }
    }

    /** A projectile: its type (0 for none), what all types share, what its type adds. */
    private fun skipProjectile(r: VanillaSaveReader) {
        val type = r.int()
        if (type == 0) return
        check(type in 1..6) { "unknown projectile type $type" }
        trace?.invoke(r.pos, "projectile$type@${r.pos}")
        repeat(29) { r.int() }
        repeat(2) { r.string() }
        repeat(18) { r.int() } // two animations, four more fields
        repeat(3) { r.string() }
        repeat(5) { r.int() }
        when (type) {
            1 -> repeat(2) { r.int() }
            4 -> repeat(5) { r.int() }
            5 -> repeat(25) { r.int() }
            6 -> repeat(12) { r.int() }
        }
    }

    /** A drone's flight state (see VanillaSaveWriter.writeDronePod). */
    private fun skipDronePod(r: VanillaSaveReader, type: DroneBlueprint.DroneType) {
        fun skipAnim() {
            r.bool(); r.bool()
            repeat(5) { r.int() }
        }

        when (type) {
            DroneBlueprint.DroneType.REPAIR, DroneBlueprint.DroneType.BATTLE -> return
            else -> {}
        }
        repeat(29) { r.int() }
        skipAnim()
        when (type) {
            DroneBlueprint.DroneType.BOARDER -> repeat(9) { r.int() }
            DroneBlueprint.DroneType.HACKING -> {
                repeat(4) { r.int() }
                repeat(2) { skipAnim() }
            }
            DroneBlueprint.DroneType.COMBAT, DroneBlueprint.DroneType.SHIP_REPAIR -> repeat(5) { r.int() }
            DroneBlueprint.DroneType.SHIELD -> r.int()
            else -> {}
        }
    }

    private fun readNearbyShipItself(
        r: VanillaSaveReader, out: VanillaSaveData, layoutFor: (String) -> Pair<List<Int>, Int>,
        artilleryOf: (String) -> Int
    ) {
        out.nearbyShipIsFlagship = r.bool()
        val start = r.pos
        val blueprint = r.string()
        r.seek(start)
        val (tiles, doors) = layoutFor(blueprint)
        val ship = VanillaSaveData.ShipData()
        trace?.invoke(r.pos, "nearbyShipStart")
        readShip(r, ship, tiles, doors, artilleryOf(blueprint))
        out.nearbyShip = ship
        trace?.invoke(r.pos, "nearbyShipEnd")

        val ai = VanillaSaveData.NearbyShipAI()
        ai.surrendered = r.bool()
        ai.escaping = r.bool()
        ai.destroyed = r.bool()
        ai.surrenderThreshold = r.int()
        ai.escapeThreshold = r.int()
        ai.escapeTicks = r.int()
        ai.stalemateTriggered = r.bool()
        ai.stalemateTicks = r.int()
        ai.boardingAttempts = r.int()
        ai.boardersNeeded = r.int()
        out.nearbyAI = ai
    }

    private fun readShip(
        r: VanillaSaveReader, ship: VanillaSaveData.ShipData, roomTiles: List<Int>, doorCount: Int, artilleryCount: Int
    ) {
        ship.blueprint = r.string()
        ship.name = r.string()
        ship.gfx = r.string()

        repeat(r.int()) { r.string(); r.string() } // starting crew (hangar display)
        ship.hostile = r.bool()
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
            // A slot per system, but each artillery gun is a system
            repeat(if (slotName == "artillery") artilleryCount.coerceAtLeast(1) else 1) {
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
                    sys.tempCapacityCap = r.int()
                    sys.tempCapacityLoss = r.int()
                    sys.tempCapacityDivisor = r.int()
                    ship.systems.putIfAbsent(slotName, sys)
                    if (slotName == "artillery") ship.artillery += sys
                }
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
        // Rooms: o2, then 3 ints per tile (fire health, ignition, extinguish
        // progress), then the station's square and direction. The oxygen
        // percentage (0-1 floats for the loader) and the fires are kept.
        ship.roomOxygen.clear()
        for ((room, tiles) in roomTiles.withIndex()) {
            ship.roomOxygen.add(r.int() / 100f)
            repeat(tiles) { tile ->
                val fireHealth = r.int()
                r.int(); r.int()
                if (fireHealth > 0) ship.fires += Triple(room, tile, fireHealth)
            }
            r.int(); r.int()
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
        crew.deathOrder = r.int()
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

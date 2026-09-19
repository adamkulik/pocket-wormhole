package xyz.znix.xftl.vanillasave

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.StoreData
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.layout.Door
import xyz.znix.xftl.systems.BackupBattery
import xyz.znix.xftl.systems.Cloaking
import xyz.znix.xftl.systems.Clonebay
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.Shields
import xyz.znix.xftl.systems.SubSystem
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Serialises the current run to vanilla FTL's continue.sav (format 11).
 *
 * The generated file can be dropped into a vanilla install (or another device
 * running Pocket Wormhole) and continued there. Vanilla's per-beacon state is
 * fully written; the map layout itself cannot be carried across, since both
 * engines regenerate it from the stored seeds using their own algorithms.
 *
 * Field order and encodings follow saveformat-ref/model11.py, which is
 * validated against real vanilla saves.
 */
object VanillaSaveWriter {
    fun write(game: InGameState, out: OutputStream) {
        out.use { it.write(writeToByteArray(game)) }
    }

    fun writeToByteArray(game: InGameState): ByteArray {
        val w = VanillaSaveByteWriter()
        val player = game.player
        val sector = game.currentBeacon.sector

        // 1. Header
        w.int(VanillaSaveFormat.FILE_FORMAT)
        // Vanilla 1.6.14 writes random_native = false (verified in real
        // saves); match it byte-for-byte.
        w.bool(false) // random_native
        w.bool(game.isAdvancedEdition)
        w.int(game.difficulty.saveFormatValue)
        // Lifetime profile stats: xftl doesn't track these, so they're zeroed.
        // On a real vanilla install the profile's own totals are used anyway.
        repeat(4) { w.int(0) }
        val displayText = shipDisplayText(player)
        w.string(if (displayText != null) game.translator[displayText] else player.name)
        w.string(player.name)
        w.int(sector.sectorNumber + 1)
        w.int(0) // unknown_beta

        // state_vars: vanilla stores achievement counters here; xftl doesn't
        // track them. Written empty, which is a valid state.
        w.int(0)

        // 2. Player ship
        writeShip(w, game, player)

        // 3. Cargo
        val cargo = player.cargoBlueprints.filterNotNull()
        w.int(cargo.size)
        for (item in cargo) {
            w.string(item.name)
        }

        // 4. Seeds and fleet state. Vanilla regenerates its own map from
        // these; the values only have to be self-consistent for OUR loader
        // when the save comes back to us.
        w.int(game.gameMap.generationSeed)
        w.int(sector.layoutSeed)
        w.int(sector.dangerZoneCentre.x)
        w.int(sector.dangerZoneCentre.y)
        w.int(sector.fleetAdvanceModifier)

        // 5. Beacon map state
        val beacons = sector.beacons
        val currentId = beacons.indexOf(game.currentBeacon)
        w.int(currentId)
        w.bool(false) // waiting
        w.int(0) // wait_event_seed
        w.string("") // unknown_epsilon
        w.bool(sector.mapRevealed) // sector_hazard_visible

        // Rebel flagship state
        val boss = sector.bosses.firstOrNull()
        w.bool(boss?.beacon != null)
        w.int(boss?.nextBeacon?.let { beacons.indexOf(it) } ?: 0)
        w.bool(boss?.jumping ?: false)
        w.bool(false) // retreating
        w.int(0) // flagship_base_turns

        // Visited-route flags: cosmetic (draws the green travel route), so
        // vanilla's exact semantics don't matter much. Real saves write a
        // shorter list than the beacon count; we write one entry per beacon
        // marking the visited ones.
        w.int(beacons.size)
        for (beacon in beacons) {
            w.bool(beacon.visited)
        }

        w.int(sector.sectorNumber)
        w.bool(false) // hidden_crystal_world

        // 6. Beacons
        w.int(beacons.size)
        for (beacon in beacons) {
            writeBeacon(w, game, beacon)
        }

        // 7. Quests. Quests sitting on beacons are identified by the beacon's
        // event; delayed quests go to the distant-quest list.
        val questBeacons = beacons.withIndex().filter { it.value.hasQuest && !it.value.visited }
        w.int(questBeacons.size)
        for ((index, beacon) in questBeacons) {
            w.string(beacon.event.deserialisationId)
            w.int(index)
        }

        val delayed = game.delayedQuests
        w.int(delayed.size)
        for (quest in delayed) {
            w.string(quest.deserialisationId)
        }

        w.int(0) // unknown_mu

        // 8. Encounter tail: a byte-exact real-vanilla tail (the template,
        // or the donor's own tail when this run was imported), since vanilla
        // hangs on our invented minimal encoding.
        val donorTail = game.vanillaTail
        if (donorTail != null) {
            w.raw(donorTail)
        } else {
            w.raw(VanillaSaveFormat.TEMPLATE_TAIL)
        }

        return w.toByteArray()
    }

    private fun shipDisplayText(player: Ship) = player.type.shipTitle

    private fun writeShip(w: VanillaSaveByteWriter, game: InGameState, ship: Ship) {
        w.string(ship.name)
        val title = ship.type.shipTitle
        w.string(if (title != null) game.translator[title] else ship.name)
        w.string(ship.type.img)

        // Vanilla writes ONE (race, name) pair PER CREW MEMBER (with the
        // ship's default crew names); initialCrew is a list of specifiers
        // carrying an amount, so expand them.
        val startingCrew = ArrayList<Pair<String, String>>()
        for (crewSpec in ship.type.initialCrew) {
            repeat(crewSpec.amount) {
                startingCrew.add(crewSpec.race to "")
            }
        }
        w.int(startingCrew.size)
        for ((race, name) in startingCrew) {
            w.string(race)
            w.string(name)
        }

        // Vanilla writes hostile=true for the player ship (verified in real
        // saves), then the jump state.
        w.bool(true)
        w.int((ship.ftlChargeProgress * 85000f).roundToInt())
        w.bool(false)
        w.int(0)

        w.int(ship.health)
        w.int(ship.fuelCount)
        w.int(ship.dronesCount)
        w.int(ship.missilesCount)
        w.int(ship.scrap)

        // Crew. Boarding-drone pawns are AbstractCrew but not LivingCrew;
        // vanilla stores them via the drones section, which we cover below.
        val crew = ship.crew.filterIsInstance<LivingCrew>()
        w.int(crew.size)
        for (member in crew) {
            writeCrew(w, game, member)
        }

        // Reactor
        w.int(ship.purchasedReactorPower)

        // The 16 fixed system slots
        for ((_, xftlName) in VanillaSaveFormat.SYSTEM_SLOTS) {
            val system = ship.systems.firstOrNull { it.blueprint.name == xftlName }
            writeSystem(w, system)
        }

        // Conditional post-system blocks, in vanilla's order: clonebay,
        // battery, shields (always), cloaking.
        val clonebay = ship.systems.firstOrNull { it is Clonebay } as Clonebay?
        if (clonebay != null) {
            val level = clonebay.powerSelected.coerceIn(1, Clonebay.durationCount)
            val goal = (Clonebay.durationForLevel(level) * VanillaSaveFormat.TICKS_PER_SECOND).toInt()
            w.int((clonebay.cloneProgress * goal).roundToInt())
            w.int(goal)
            w.int(0) // doom_ticks
        }

        val battery = ship.systems.firstOrNull { it is BackupBattery } as BackupBattery?
        if (battery != null) {
            w.bool(battery.timeRemaining != null)
            w.int(battery.undamagedEnergy) // used_battery
            w.int(((battery.timeRemaining ?: 0f) * VanillaSaveFormat.TICKS_PER_SECOND).toInt())
        }

        val shields = ship.systems.firstOrNull { it is Shields } as Shields?
        w.int(shields?.activeShields ?: 0)
        w.int(ship.superShield)
        w.int(ship.maxSuperShield)
        w.int(((shields?.rechargeTimer ?: 0f) * VanillaSaveFormat.TICKS_PER_SECOND).toInt())
        w.bool(false); w.int(0) // shield drop anim
        w.bool(false); w.int(0) // shield raise anim
        w.bool(false); w.int(0) // energy shield anim
        w.int(0); w.int(0) // unknown lambda/mu

        val cloaking = ship.systems.firstOrNull { it is Cloaking } as Cloaking?
        if (cloaking != null) {
            w.int(0) // unknown_alpha
            w.int(0) // unknown_beta
            w.int((cloaking.duration * VanillaSaveFormat.TICKS_PER_SECOND).toInt())
            w.int(((cloaking.timeRemaining ?: 0f) * VanillaSaveFormat.TICKS_PER_SECOND).toInt())
        }

        // Rooms: (o2, a, b) per room + 3 ints per tile. The a/b/tile values
        // are transient state we don't model - zeros match pristine saves.
        val layoutRooms = ship.type.rooms
        for ((index, layoutRoom) in layoutRooms.withIndex()) {
            val room = ship.rooms.getOrNull(index)
            w.int(((room?.oxygen ?: 1f) * 100f).roundToInt())
            w.int(0)
            w.int(0)
            val tiles = layoutRoom.size.x * layoutRoom.size.y
            repeat(tiles) {
                w.int(-1); w.int(0); w.int(0)
            }
        }

        // Breaches: (room, square, health). xftl breach health is 0-1; write
        // it scaled to vanilla's 0-10 style numbers.
        val breaches = ArrayList<Triple<Int, Int, Int>>()
        for (room in ship.rooms) {
            for ((idx, breach) in room.breaches.withIndex()) {
                if (breach != null) {
                    breaches.add(Triple(room.id, idx, (breach.health * 10f).roundToInt()))
                }
            }
        }
        w.int(breaches.size)
        for ((roomId, square, health) in breaches) {
            w.int(roomId)
            w.int(square)
            w.int(health)
        }

        // Doors: no count in the file - the entry count is implied by the
        // layout's door list, which Ship mirrors in the same order.
        val layoutDoorCount = ship.type.doors.size
        for (i in 0 until layoutDoorCount) {
            val door: Door? = ship.doors.getOrNull(i)
            val max = door?.maxHealth ?: 4
            val health = max - (door?.damage ?: 0)
            w.int(max)
            w.int(health)
            w.int(max)
            w.bool(door?.open ?: false)
            w.bool(false) // walking_through
            w.int(0) // unknown_delta
            w.int(0) // unknown_epsilon
        }

        w.int(0) // cloak_anim_ticks

        // Crystal lockdowns (AE): xftl doesn't model the flying-shard state,
        // so write an empty list (a lockdown in progress is lost on export).
        w.int(0)

        // Weapons on hardpoints
        val weapons = ship.hardpoints.mapNotNull { it.weapon }
        w.int(weapons.size)
        for (weapon in weapons) {
            w.string(weapon.type.name)
            w.bool(true) // armed
        }

        // Drones: slot entries (in-flight external drones are transient and
        // dropped, like projectiles).
        val dronesSystem = ship.systems.firstOrNull { it is Drones } as Drones?
        val slotDrones = dronesSystem?.drones?.filterNotNull() ?: emptyList()
        w.int(slotDrones.size)
        for (info in slotDrones) {
            w.string(info.type.name)
            w.bool(info.instance != null)
            w.bool(true) // player_controlled
            w.int(0) // body_x
            w.int(0) // body_y
            w.int(0) // body_room_id
            w.int(0) // body_room_square
            w.int(20) // health
        }

        // Augments
        w.int(ship.augments.size)
        for (aug in ship.augments) {
            w.string(aug.name)
        }
    }

    private fun writeCrew(w: VanillaSaveByteWriter, game: InGameState, member: LivingCrew) {
        w.string(member.info.name)
        w.string(member.blueprint.name)
        w.bool(false) // boarding_drone
        w.int(member.health.roundToInt())

        // Sprite position + room/square. Vanilla's sprite frame includes the
        // ship's layout offset - without it crew render outside the hull on
        // load. 17 = half a room-square, centring the crew in their square.
        val standing = member.standingPosition
        val room = standing?.room ?: member.room
        val squareX = standing?.x ?: 0
        val squareY = standing?.y ?: 0
        val square = squareY * room.width + squareX
        val off = member.room.ship.offset
        val spriteX = xyz.znix.xftl.Constants.ROOM_SIZE * (room.x + off.x + squareX) + 17
        val spriteY = xyz.znix.xftl.Constants.ROOM_SIZE * (room.y + off.y + squareY) + 17
        w.int(spriteX)
        w.int(spriteY)
        w.int(room.id)
        w.int(square)
        w.bool(member.ownerShip === game.player)

        w.int(0) // clone_ready
        w.int(-1) // death_order

        // Sprite tints: (0, colour index) matches real saves.
        w.int(2)
        w.int(0)
        w.int(member.info.colour)

        w.bool(member.mindControlledBy != null)

        // Saved position (used by mind control release)
        val saved = member.savedPosition
        if (saved != null) {
            w.int(saved.x)
            w.int(saved.y)
        } else {
            w.int(square)
            w.int(room.id)
        }

        // Skills: xftl keeps 0..2 progress (1 = one colour change); vanilla
        // stores the number of performed actions, which is progress divided
        // by amountPerAction.
        for (skill in Skill.entries) {
            val progress = member.info.skills[skill] ?: 0f
            w.int((progress / skill.amountPerAction).roundToInt())
        }

        // Humans have a visual gender (colour < base colour count = male);
        // every other race is genderless.
        val male = member.blueprint.name == "human" &&
                member.info.colour < member.blueprint.baseNumberOfColours
        w.bool(male)

        // Crew stats (repairs, kills, evasions, jumps, masteries): xftl
        // doesn't track these.
        repeat(5) { w.int(0) }

        w.int(0) // stun_ticks
        w.int(0) // health_boost
        w.int(-1) // clonebay_priority
        w.int(0) // damage_boost
        w.int(0) // unknown_lambda
        w.int(0) // universal_death_count

        // Skill masteries: one bool per level per skill.
        for (skill in Skill.entries) {
            val progress = member.info.skills[skill] ?: 0f
            w.bool(progress >= 1f)
            w.bool(progress >= 2f)
        }

        w.int(0) // unknown

        // Animation state: (running, ?, 5 ints)
        w.bool(false)
        w.bool(false)
        repeat(5) { w.int(0) }

        w.int(0) // unknown

        if (member.blueprint.name == "crystal") {
            w.int(0)
            w.int(0)
            w.int(0)
        }
    }

    private fun writeSystem(w: VanillaSaveByteWriter, system: AbstractSystem?) {
        if (system == null || system.energyLevels <= 0) {
            // Vanilla writes only the capacity for uninstalled systems; a
            // system present in-engine but at level 0 counts as uninstalled.
            w.int(0)
            return
        }

        w.int(system.energyLevels)
        val selectedPower = when (system) {
            is MainSystem -> system.powerSelected
            is SubSystem -> system.undamagedEnergy
            else -> system.undamagedEnergy
        }
        w.int(selectedPower)
        w.int(system.damagedEnergyLevels)
        w.int(system.ionPowerLimit ?: 0)
        w.int((system.ionTimer * VanillaSaveFormat.TICKS_PER_SECOND).toInt())
        w.int((system.repairProgress * VanillaSaveFormat.TICKS_PER_SECOND).toInt())
        w.int((system.damageProgress * VanillaSaveFormat.TICKS_PER_SECOND).toInt())
        w.int(0) // battery_power
        w.int(system.hackedBy?.powerSelected ?: 0)
        w.bool(system.isHackActive)
        w.int(0) // tempcapacity_cap
        w.int(0) // tempcapacity_loss
        w.int(0) // tempcapacity_divisor
    }

    private fun writeBeacon(w: VanillaSaveByteWriter, game: InGameState, beacon: Beacon) {
        val visited = beacon.visited
        w.int(if (visited) 1 else 0)
        if (visited) {
            // Background art: xftl generates this visually rather than
            // storing it, so write a valid generic entry.
            w.string("stars/bg_dullstars2.png")
            w.string("")
            w.int(0)
            w.int(0)
            w.int(0)
        }

        w.bool(visited) // seen

        val ship = beacon.ship
        w.bool(ship != null)
        if (ship != null) {
            w.string(beacon.event.deserialisationId)
            w.string(ship.spec?.autoBlueprint?.resolve()?.name ?: "")
            w.int(ship.generationSeed ?: 0)
        }

        w.int(if (beacon.isOvertaken) 1 else 0) // fleet_presence
        w.bool(false) // under_attack

        w.bool(beacon.hasStore)
        if (beacon.hasStore) {
            writeStore(w, game, beacon)
        }
    }

    private fun writeStore(w: VanillaSaveByteWriter, game: InGameState, beacon: Beacon) {
        val data = beacon.getStore(game) ?: StoreData()

        // Vanilla's store: only the sections the store actually stocks are
        // written (2-4 shelves), each with EXACTLY three (avail, name, extra)
        // items - sold-out items keep their name with avail=0, and empty
        // sections are left out entirely. Vanilla's parser reads all three
        // items unconditionally, so inventing terminators hangs it (found by
        // the freeze bisect).
        val shelves = ArrayList<Triple<Int, Int, List<String?>>>()
        if (data.systems.any { it != null }) {
            shelves.add(Triple(VanillaSaveFormat.SHELF_SYSTEM, data.systems.size,
                data.systems.map { it?.name }))
        }
        if (data.weapons.any { it != null }) {
            shelves.add(Triple(VanillaSaveFormat.SHELF_WEAPON, data.weapons.size,
                data.weapons.map { it?.name }))
        }
        if (data.drones.any { it != null }) {
            shelves.add(Triple(VanillaSaveFormat.SHELF_DRONE, data.drones.size,
                data.drones.map { it?.name }))
        }
        if (data.augments.any { it != null }) {
            shelves.add(Triple(VanillaSaveFormat.SHELF_AUGMENT, data.augments.size,
                data.augments.map { it?.name }))
        }
        if (data.crew.any { it != null }) {
            shelves.add(Triple(VanillaSaveFormat.SHELF_CREW, data.crew.size,
                data.crew.map { it?.race?.name }))
        }

        w.int(shelves.size)
        for ((type, count, names) in shelves) {
            w.int(type)
            for (index in 0 until 3) {
                val name = names.getOrNull(index)
                if (name == null) {
                    // Sold-out slot: vanilla keeps the item with avail=0; the
                    // name is lost in xftl's model, so write a blank one.
                    w.bool(false)
                    w.string("")
                    w.int(0)
                } else {
                    w.bool(true)
                    w.string(name)
                    w.int(0) // extra data
                }
            }
        }

        w.int(data.availableResources.fuel)
        w.int(data.availableResources.missiles)
        w.int(data.availableResources.droneParts)
    }

    /**
     * The unmodelled encounter tail. Every real save examined starts it with
     * the four encounter-outcome event names, so those are written with the
     * current encounter's names (or vanilla's defaults), followed by empty
     * text and a zeroed blob. This is the one part of the file that hasn't
     * been byte-verified against vanilla's own writer - see the validation
     * checklist.
     */

    private val Difficulty.saveFormatValue: Int
        get() = when (this) {
            Difficulty.EASY -> 0
            Difficulty.NORMAL -> 1
            Difficulty.HARD -> 2
        }
}

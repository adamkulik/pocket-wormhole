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
import xyz.znix.xftl.systems.Artillery
import xyz.znix.xftl.systems.BackupBattery
import xyz.znix.xftl.systems.Cloaking
import xyz.znix.xftl.systems.Clonebay
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.systems.Hacking
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.MindControl
import xyz.znix.xftl.systems.Shields
import xyz.znix.xftl.systems.SubSystem
import xyz.znix.xftl.systems.Weapons
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
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

        // 5. Beacon map state. Our beacon indices carry no positional
        // meaning (our generator picks grid cells at random), while
        // vanilla's correlate with progression - its own fresh-arrival
        // saves sit at index 1-2. Exports are therefore written through an
        // export order: when the player stands at the sector's entry
        // beacon (the supported transfer-at-boundary workflow), that
        // beacon is placed in slot 1, so vanilla lands the run next to its
        // own map entrance. All per-beacon state and quest indices below
        // go through the same order so they stay coherent. Exact position
        // pinning would require pinning vanilla's generator.
        val beacons = sector.beacons
        val exportOrder: List<Beacon> = if (game.currentBeacon === sector.startBeacon && beacons.size >= 2) {
            val others = beacons.filter { it !== sector.startBeacon }
            listOf(others[0], sector.startBeacon) + others.drop(1)
        } else {
            beacons
        }
        val currentId = exportOrder.indexOf(game.currentBeacon)
        w.int(currentId)
        // Per the exe: a "fleet warning text present" flag, the fleet's
        // warning sentinel (-1 in real saves while no warning is active)
        // and its warning text. The fleet's actual danger-zone position is
        // carried by the danger-zone seeds above, so the fleet's advance
        // survives the round-trip (verified: real saves keep the same
        // dzx/dzy semantics).
        w.bool(false)
        w.int(-1)
        w.string("")
        w.bool(sector.mapRevealed) // sector_hazard_visible

        // Rebel flagship / fleet-pursuit state: active flag, fleet map
        // position (clamped by the exe to sectorCount-3), jumping flag,
        // retreating flag, base-turn counter. xftl's bosses only exist in
        // the Last Stand sector, which maps 1:1 onto vanilla's flagship
        // pursuit being active there.
        val boss = sector.bosses.firstOrNull()
        w.bool(boss?.beacon != null)
        w.int(boss?.nextBeacon?.let { beacons.indexOf(it) } ?: 0)
        w.bool(boss?.jumping ?: false)
        w.bool(false) // retreating
        w.int(0) // base_turns

        // Visited-sector route: one bool per SectorInfo entry across the
        // map's branching columns (matches real saves: ~20 entries). The
        // exe's loader walks this list to rebuild its highest-sector-reached
        // counter and sets its LAST STAND flag once that counter passes the
        // final column (sector number > 7). The old code wrote the BEACON
        // count with per-beacon visited flags here, which flagged deep
        // columns - including the Last Stand - as visited and dropped even
        // a sector-1 run into the endgame on load.
        val sectorEntries = game.gameMap.sectors.flatten()
        w.int(sectorEntries.size)
        for (info in sectorEntries) {
            w.bool(game.visitedSectors.contains(info))
        }

        w.int(sector.sectorNumber)
        w.bool(false) // hidden_crystal_world

        // 6. Beacons (in export order - see above)
        w.int(exportOrder.size)
        for (beacon in exportOrder) {
            writeBeacon(w, game, beacon)
        }

        // 7. Quests. Quests sitting on beacons are identified by the beacon's
        // event; delayed quests go to the distant-quest list. Indexes are
        // remapped through the export order so quests stay attached to the
        // right exported beacon.
        val questBeacons = beacons.withIndex().filter { it.value.hasQuest && !it.value.visited }
        w.int(questBeacons.size)
        for ((_, beacon) in questBeacons) {
            w.string(beacon.event.deserialisationId)
            w.int(exportOrder.indexOf(beacon))
        }

        val delayed = game.delayedQuests
        w.int(delayed.size)
        for (quest in delayed) {
            w.string(quest.deserialisationId)
        }

        // 8. Encounter tail: the template's bytes are real vanilla output
        // (proven to load), BUT its extended-ship-info section describes
        // the DONOR's ship, and vanilla cross-checks that against the ship
        // section (weapon-module count == weapon list length; drone pods
        // per drone type). Pasting a whole template/donor tail verbatim
        // only worked for a ship identical to the donor's - any other
        // loadout desynced vanilla's loader mid-tail: the phone->vanilla
        // freeze. Splice: proven prefix (mu, encounter, environment,
        // projectiles) + OUR ship's extended info + proven suffix (nu,
        // autofire, flagship, occupancy).
        w.raw(VanillaSaveFormat.TAIL_PREFIX)
        writeExtendedShipInfo(w, game, player)
        w.raw(VanillaSaveFormat.TAIL_SUFFIX)

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

    /**
     * The tail's per-ship section (vanilla's FUN_004a1830): deployed-drone
     * flags/pods, hacking/mind state, one weapon module per weapon, one per
     * artillery level, standalone drones. Everything is written from OUR
     * ship's live state - the byte counts here are cross-checked by
     * vanilla's loader against the ship section above.
     */
    private fun writeExtendedShipInfo(w: VanillaSaveByteWriter, game: InGameState, ship: Ship) {
        // Deployed drones: (deployed, armed) per slot drone plus a pod for
        // types with a flying body. In-flight drones are transient (dropped
        // like projectiles), so both flags are false.
        val dronesSystem = ship.systems.firstOrNull { it is Drones } as Drones?
        val slotDrones = dronesSystem?.drones?.filterNotNull() ?: emptyList()
        for (info in slotDrones) {
            w.bool(false)
            w.bool(false)
            writeDronePod(w, info.type.type)
        }

        // Hacking: target + timing fields + a hacking pod.
        val hacking = ship.systems.firstOrNull { it is Hacking }
        if (hacking != null && hacking.energyLevels > 0) {
            w.int(-1) // target room: none
            w.int(0)
            w.bool(false)
            w.int(0)
            w.int(0)
            w.int(0)
            w.bool(false)
            writeDronePod(w, DroneBlueprint.DroneType.HACKING)
        }

        // Mind control: two scalars.
        val mind = ship.systems.firstOrNull { it is MindControl }
        if (mind != null && mind.energyLevels > 0) {
            w.int(0)
            w.int(0)
        }

        // Weapon modules - the count MUST equal the ship section's weapon
        // list (vanilla reads exactly that many modules).
        val weapons = ship.hardpoints.mapNotNull { it.weapon }
        val weaponsSystem = ship.systems.firstOrNull { it is Weapons }
        if (weaponsSystem != null && weaponsSystem.energyLevels > 0) {
            w.int(weapons.size)
            for (weapon in weapons) {
                writeWeaponModule(w, (weapon.type.chargeTime * 1000).toInt())
            }
        }

        // Artillery: one module per artillery level; the gun's blueprint id
        // sits on the artillery hardpoint's spec (same lookup Artillery uses).
        for (artillery in ship.artillery) {
            if (artillery.energyLevels <= 0) continue
            val bpId = artillery.configuration.spec.weapon
            val chargeTime = if (bpId != null)
                (game.blueprintManager[bpId] as? AbstractWeaponBlueprint)?.chargeTime ?: 10f
            else 10f
            writeWeaponModule(w, (chargeTime * 1000).toInt())
        }

        // Standalone (system-less) drones: none.
        w.int(0)
    }

    /** A drone pod: transient flight state, written as inert defaults. */
    private fun writeDronePod(w: VanillaSaveByteWriter, type: DroneBlueprint.DroneType) {
        when (type) {
            DroneBlueprint.DroneType.REPAIR, DroneBlueprint.DroneType.BATTLE -> return
            else -> {}
        }
        // mourning, space, dest, pos x10, ticks x6, hops, pi, rho, overload,
        // tau, upsilon, dpos x2, death anim (2 bools + 5 ints)
        repeat(3 + 10 + 6 + 6 + 2) { w.int(0) }
        w.bool(false); w.bool(false)
        repeat(5) { w.int(0) }
        when (type) {
            DroneBlueprint.DroneType.BOARDER -> repeat(9) { w.int(0) }
            DroneBlueprint.DroneType.HACKING -> {
                repeat(4) { w.int(0) }
                repeat(2) {
                    w.bool(false); w.bool(false)
                    repeat(5) { w.int(0) }
                }
            }
            DroneBlueprint.DroneType.COMBAT,
            DroneBlueprint.DroneType.SHIP_REPAIR -> repeat(5) { w.int(0) }
            DroneBlueprint.DroneType.DEFENSE -> {}
            DroneBlueprint.DroneType.SHIELD -> w.int(0)
            DroneBlueprint.DroneType.REPAIR, DroneBlueprint.DroneType.BATTLE -> {}
        }
    }

    /**
     * A weapon module: per-gun charge/targeting state. The defaults match
     * the template's fresh-loadout modules (the only bytes of this structure
     * vanilla has ever been proven to accept from us).
     */
    private fun writeWeaponModule(w: VanillaSaveByteWriter, coolGoalMs: Int) {
        w.int(0) // cool
        w.int(coolGoalMs)
        w.int(0) // subcool
        w.int(0) // subgoal
        w.int(0) // boost
        w.int(0) // charge
        w.int(0) // targets count
        w.int(0) // prev targets count
        w.bool(false) // autofire
        w.bool(false) // fire_ready
        w.int(-1) // target_id
        // anim: (bool, bool, 5 ints)
        w.bool(true); w.bool(false)
        w.int(0); w.int(0); w.int(1000); w.int(0); w.int(0)
        w.int(0) // protract
        w.bool(false) // firing
        w.bool(false) // phi
        w.int(-1) // anim_charge
        // charge_anim
        w.bool(false); w.bool(false)
        w.int(0); w.int(0); w.int(1000); w.int(-1000); w.int(-1000)
        w.int(-1) // last_proj
        w.int(0) // pending projectiles
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

    // Sprite paths verified to exist in the vanilla dat (img/stars/*).
    // Rotated per-beacon for variety.
    private val VERIFIED_SPRITES = listOf(
        "stars/planet_populated_orange.png",
        "stars/planet_gas_yellow.png",
        "stars/planet_peach.png",
        "stars/planet_populated_brown.png",
        "stars/planet_populated_dark.png",
    )

    private fun writeBeacon(w: VanillaSaveByteWriter, game: InGameState, beacon: Beacon) {
        val visited = beacon.visited
        w.int(if (visited) 1 else 0)
        if (visited) {
            // Background art: xftl generates this visually rather than
            // storing it, so write a valid generic entry with a sprite
            // path verified to exist in the dat.
            w.string("stars/bg_dullstars2.png")
            w.string(VERIFIED_SPRITES[beacon.pos.x % VERIFIED_SPRITES.size])
            w.int(0)
            w.int(0)
            w.int(180)
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

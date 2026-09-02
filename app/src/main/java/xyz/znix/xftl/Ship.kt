package xyz.znix.xftl

import org.jdom2.Element
import xyz.znix.xftl.Constants.*
import xyz.znix.xftl.ai.FriendlyCrewAI
import xyz.znix.xftl.ai.IntruderCrewAI
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.*
import xyz.znix.xftl.drones.AbstractExternalDrone
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.game.*
import xyz.znix.xftl.hangar.EditableShip
import xyz.znix.xftl.hangar.FinalisedEditableSystem
import xyz.znix.xftl.layout.*
import xyz.znix.xftl.math.*
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ISerialReferencable
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.weapons.*
import java.util.*
import java.util.stream.Collectors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

class Ship(
    val type: ShipBlueprint,

    val sys: InGameState,

    /**
     * If this ship was customised by the player, this is it.
     *
     * Note this must ABSOLUTELY NOT be modified once the game has started!
     * Serialisation in particular would be completely wrecked if the room
     * indices change.
     *
     * While it would be possible to always create an EditableShip instance,
     * even for unmodified ships, it constraints what simplifications we can
     * do with EditableShip while still being able to properly load all the
     * vanilla ships, with all their details.
     */
    val customised: EditableShip?,

    val spec: EnemyShipSpec?,

    boss: BossManager?,
) :
    ISerialReferencable {

    // Helper methods, providing quick access to important stuff from the blueprint
    val name: String get() = type.name
    val maxHealth: Int get() = type.maxHealth
    val isPlayerShip: Boolean get() = type.isPlayerShip
    val isUsingBossUI: Boolean get() = boss?.useBossShipUI ?: false
    val isAutoScout: Boolean get() = type.isAutoScout
    val weaponSlots: Int? get() = customised?.weaponSlots ?: type.weaponSlots
    val droneSlots: Int? get() = customised?.droneSlots ?: type.droneSlots

    /**
     * If this ship is a boss, this points to its manager. Null otherwise.
     */
    var boss: BossManager? = boss
        private set // Mutable only for deserialisation

    val rooms: List<Room>
    val doors: List<Door>

    val offset: ConstPoint = type.roomOffset
    val floorOffset: ConstPoint = type.floorOffset
    val cloakOffset: ConstPoint = type.cloakOffset
    val hullOffset: ConstPoint

    val weaponFireDirection: Direction = when (type.isPlayerShip) {
        true -> Direction.RIGHT // Weapons fly right
        false -> Direction.UP // Weapons fly upwards
    }

    // Played when any two projectiles collide.
    private val projectileCollisionSound: FTLSound = sys.sounds.getSample("hitHull1")
    val breachSound: FTLSound = sys.sounds.getSample("hullBreach")
    val airLossSound: FTLSound = sys.sounds.getSample("airLoss")

    val floorImage: Image? = type.floorImage?.let { sys.getImg(it) }
    val hullImage: Image = type.hullImage.firstNotNullOf { sys.getImgIfExists(it) }
    val cloakImage: Image? = type.cloakImage?.let { sys.getImg(it) }
    val gibs: List<ShipGib.Instance>

    val shieldImage: Image = sys.getImg(type.shieldImage)

    val selectedShieldHalfSize: ConstPoint
    val shieldHalfSize: ConstPoint

    val shieldOrigin: ConstPoint

    // All the crew currently standing on this ship. This includes drone pawns.
    val crew: MutableList<AbstractCrew> = ArrayList()

    // The subset of the crew that are or aren't intruders. This also includes drone pawns.
    val intruders: List<AbstractCrew> = ArrayList()
    val friendlyCrew: List<AbstractCrew> = ArrayList()

    val cargoBlueprints = ArrayList<Blueprint?>(listOf(null, null, null, null))

    /**
     * The ship's augment slots. This should only be used for UI relating
     * to the augment objects as seen on the ship screen - in the future,
     * mods will be able to apply blueprint effects to the ship without
     * using an augment slot, and those show up in [augmentValues], so it
     * should be used for mod compatibility whenever possible.
     *
     * One must always call [cargoUpdated] after modifying this, so the
     * changes show up in [augmentValues].
     */
    val augments = ArrayList<AugmentBlueprint>()

    /**
     * This contains the sum of the values for each type of augment. Mods
     * will be able to apply augment effects without a regular augments,
     * and those effects will then be stored here.
     */
    private val privateAugmentValues = HashMap<AugmentBlueprint, Float>()
    val augmentValues: Map<AugmentBlueprint, Float> = Collections.unmodifiableMap(privateAugmentValues)

    val pathFinder: PathFinder

    val hardpoints: List<Hardpoint>

    // This contains all the projectiles, both incoming and outgoing.
    val projectiles: MutableList<IProjectile> = ArrayList()

    val inboundBeams: MutableList<BeamBlueprint.BeamInstance> = ArrayList()
    val animations: MutableList<FloatingAnimation> = ArrayList()

    private val damageNumbers = ArrayList<DamageNumber>()

    /**
     * A list of all the drones (friendly or otherwise) that are deployed
     * around this ship.
     */
    val externalDrones = ArrayList<AbstractExternalDrone>()

    /**
     * The AI that controls friendly (non-intruder) crew on this ship.
     *
     * It's present on all ships, including the player ship. This is because
     * it's used for mind-controlled intruders, as well as regular enemies
     * on their ships.
     */
    val crewAI = FriendlyCrewAI(this)

    val intruderAI = IntruderCrewAI(this)

    // This really is a bit horrible - if this is the enemy ship, we store the beam the
    // player is currently aiming at us to highlight the affected rooms.
    private var inboundBeamAim: SelectedTarget.BeamAim? = null

    // The number of fuel, missiles and drones this ship has. The missiles and drones
    // are set during ship loading. Player ships always seem to start with 16 fuel.
    var fuelCount: Int = 16
    var missilesCount: Int = 0
    var dronesCount: Int = 0

    // How much scrap the player has
    var scrap: Int = if (sys.difficulty == Difficulty.EASY) 30 else 10

    // How far through charging the FTL drive, 1=fully charged.
    // These only apply to the player, enemies use a fixed timer.
    var ftlChargeProgress: Float = 0f
    val isFtlCharged get() = ftlChargeProgress >= 1f
    val isFtlReady get() = isFtlCharged && engines!!.powerSelected > 0

    // This is for both the player and enemies
    val canChargeFTL: Boolean
        get() {
            // For the FTL to charge, the engines and piloting must
            // be working, and a pilot must be present.
            val hasPilot = isAutoScout || friendlyCrew.any { it.room == piloting!!.room }
            return engines!!.powerSelected > 0 && piloting!!.undamagedEnergy > 0 && hasPilot
                    && !engines!!.isHackActive && !piloting!!.isHackActive
        }

    val maxReactorPower: Int get() = 25

    // The raw amount of reactor power purchased by the player
    var purchasedReactorPower: Int = 5

    // The amount of reactor power available for use by the player, taking ion storms, events and so on into account
    val reactorPower: Int get() = purchasedReactorPower

    // The unused reactor/battery power
    val powerAvailableTypes = HashMap<EnergySource, Int>()

    val powerAvailable: Int get() = powerAvailableTypes.values.sum()

    /**
     * The amount of power that's unusable due to events or the environment (in
     * a plasma storm).
     */
    var blockedReactorPower: Int = 0
        private set

    var health = maxHealth
        set(value) {
            field = value.coerceAtLeast(0).coerceAtMost(maxHealth)
        }

    // The amount of health at which this ship will surrender and attempt to escape
    var escapeHealth: Int = 0
    var surrenderHealth: Int = 0

    /**
     * If this ship is attempting to escape, this is the time remaining until it jumps.
     */
    var escapeTimer: Float? = null

    /**
     * The maximum number of super-shield levels this ship can have.
     *
     * This is always 5, except for the flagship since it has a super-super-shield.
     */
    var maxSuperShield: Int = 5

    /**
     * The number of points of super-shield this ship currently has.
     */
    var superShield: Int = 0
        set(value) {
            field = value.coerceIn(0..maxSuperShield)
        }

    /**
     * Returns true if this ship has ran out of health
     */
    val isDead: Boolean get() = health == 0

    /**
     * Returns true if this ship is dead, and either it doesn't have any gibs or their animation has finished.
     */
    val isGone: Boolean get() = isDead && gibs.firstOrNull()?.isFinished != false

    val isCloakActive: Boolean get() = cloaking?.active ?: false

    // The amount of power currently used by the ship's systems
    val powerConsumed: Int
        get() {
            var used = 0
            for (room in rooms) {
                used += (room.system as? MainSystem ?: continue).powerSelected
            }
            return used
        }

    /**
     * Set by SlickGame, this is true if the opponent's cloak is active.
     *
     * This prevents the weapons from charging.
     */
    var opponentCloakActive: Boolean = false

    /**
     * This is the list of all the possible systems that can be installed on this ship.
     *
     * It's simply the sum of all the [Room.systemSlots] lists.
     */
    val systemSlots: List<SystemInstallConfiguration>

    var systems: List<AbstractSystem> = emptyList()
        private set

    /**
     * The list of all the [MainSystem]s in the ship, sorted into the order
     * they appear left-to-right in the power UI.
     */
    var mainSystems: List<MainSystem> = emptyList()
        private set

    /**
     * The list of all the [SubSystem]s in the ship, sorted into the order
     * they appear in the subsystem tray.
     */
    var subSystems: List<SubSystem> = emptyList()
        private set

    var weapons: Weapons? = null
        private set

    var drones: Drones? = null
        private set

    var engines: Engines? = null
        private set

    var shields: Shields? = null
        private set

    var medbay: Medbay? = null
        private set

    var clonebay: Clonebay? = null
        private set

    var cloaking: Cloaking? = null
        private set

    var teleporter: Teleporter? = null
        private set

    var hacking: Hacking? = null
        private set

    var mindControl: MindControl? = null
        private set

    var piloting: Piloting? = null
        private set

    var sensors: Sensors? = null
        private set

    var backupBattery: BackupBattery? = null
        private set

    // Can't just call this 'doors' since that's the list of all
    // the doors in the ship.
    var doorsSystem: Doors? = null
        private set

    var oxygen: Oxygen? = null
        private set

    // The flagship has multiple artillery systems.
    var artillery: List<Artillery> = emptyList()
        private set

    // The ship's evasion, in percent
    val evasion: Int
        get() {
            var evasion: Float = piloting!!.evasion + engines!!.evasion.f
            evasion *= piloting!!.evasionMultiplier
            evasion *= engines!!.evasionMultiplier

            if (isCloakActive)
                evasion += 60

            return evasion.toInt()
        }

    /**
     * From 0-1.
     */
    var averageOxygen: Float = 1f
        private set

    init {
        if (customised != null) {
            val roomIds = customised.rooms.withIndex().associate { Pair(it.value, it.index) }
            rooms = customised.rooms.withIndex().map { (idx, r) -> Room(this, idx, r.x, r.y, r.w, r.h) }

            doors = customised.doors.mapNotNull { door ->
                val firstRoom = door.findNeighbourRoom(customised, null)
                val secondRoom = door.findNeighbourRoom(customised, firstRoom)

                // Skip disconnected doors.
                if (firstRoom == null && secondRoom == null)
                    return@mapNotNull null

                val firstRoomReal = firstRoom?.let { rooms[roomIds.getValue(it)] }
                val secondRoomReal = secondRoom?.let { rooms[roomIds.getValue(it)] }

                Door(ConstPoint(door.x, door.y), firstRoomReal, secondRoomReal, door.isVertical)
            }
        } else {
            rooms = type.rooms.map { Room(this, it.id, it.pos.x, it.pos.y, it.size.x, it.size.y) }
            doors = type.doors.mapNotNull { it ->
                // Some modded ships have doors that don't connect to any rooms.
                // (eg, the circle destroyer from Dino's Hangar)
                // We should probably guess what rooms these connect to and link
                // them up, for now ignore those doors.
                if (it.left == null && it.right == null) {
                    println("[WARN] Ignoring disconnected door at ${it.pos.x},${it.pos.y}.")
                    return@mapNotNull null
                }

                Door(
                    it.pos,
                    it.left?.let { rooms[it.id] },
                    it.right?.let { rooms[it.id] },
                    it.isVertical
                )
            }
        }

        for (room in rooms) {
            val roomDoors =
                doors.stream().filter { d -> d.left == room || d.right == room }.collect(Collectors.toList())
            room.initialise(roomDoors)
        }

        val shieldOffset = type.shieldEllipse.pos.const
        selectedShieldHalfSize = type.shieldEllipse.size.const

        // The player ship uses the exact size of the shield image,
        // while enemies scale it to fit a custom size.
        shieldHalfSize = when {
            isPlayerShip -> ConstPoint(shieldImage.width / 2, shieldImage.height / 2)
            else -> selectedShieldHalfSize
        }

        if (customised != null) {
            var systemIdx = 0
            for ((idx, room) in rooms.withIndex()) {
                val editable = customised.rooms[idx]
                val system = editable.system ?: continue
                val finalised = FinalisedEditableSystem(system, systemIdx++, sys, room)
                room.systemSlots.add(SystemInstallConfiguration(finalised, sys, room))
            }
        } else {
            for (node in type.systems) {
                val room = rooms[node.room.id]

                // Load the information about the available system into the room.
                // This will be used when loading the system from a save, spawning
                // a new ship, or buying a system at a store.
                room.systemSlots.add(SystemInstallConfiguration(node, sys, room))
            }
        }

        systemSlots = rooms.flatMap { it.systemSlots }

        // The shield origin is found by drawing a box that contains all
        // the rooms, then taking its centre.
        val minX = offset.x + rooms.minOf { it.x }
        val minY = offset.y + rooms.minOf { it.y }
        val maxX = offset.x + rooms.maxOf { it.x + it.width }
        val maxY = offset.y + rooms.maxOf { it.y + it.height }
        val centreRoomX = (minX + maxX) / 2f
        val centreRoomY = (minY + maxY) / 2f
        val origin = Point(
            (ROOM_SIZE * centreRoomX).toInt(),
            (ROOM_SIZE * centreRoomY).toInt(),
        )
        origin += shieldOffset
        if (!isPlayerShip) {
            // Yes this looks silly, but it's what vanilla does
            origin.y += 110
        }
        shieldOrigin = origin.const

        hullOffset = ConstPoint(minX, minY) * ROOM_SIZE + type.hullOffset

        hardpoints = type.hardpoints.map { Hardpoint(it) }
        gibs = type.gibs.map { it.createInstance(sys) }

        // Set up the pathfinder after the layout is loaded
        pathFinder = PathFinder(this)
    }

    /**
     * Loads the default version of the ship's stuff (system, blueprints, etc) - this
     * is all stuff that the player can upgrade and needs to be saved.
     */
    fun loadDefaultContents() {
        // Load the starting reactor power
        purchasedReactorPower = type.startingReactorPower

        // Load the starting systems
        for (config in systemSlots) {
            if (!config.spec.availableByDefault)
                continue

            config.room.setSystem(config)
        }

        // Load the starting resources
        missilesCount = type.initialMissiles
        dronesCount = type.initialDroneParts

        // Load all the weapon blueprints
        for ((index, name) in type.initialWeapons.withIndex()) {
            // If there's a ship with invalid weapons, vanilla loads the weapon
            // with an empty name.
            // This empty-name weapon isn't actually set in the vanilla XML, but
            // that's fine since only mods have ships with invalid weapon names.
            var weapon = sys.blueprintManager.getOrNull(name)
            if (weapon == null) {
                println("[WARN] Invalid weapon '$name' in ship '${this.name}' initial weapons")
                weapon = sys.blueprintManager[""]
            }
            check(weapon is AbstractWeaponBlueprint)

            hardpoints[index].weapon = weapon.buildInstance(this)
        }

        // Load all the drone blueprints
        for ((idx, name) in type.initialDrones.withIndex()) {
            val drone = sys.blueprintManager.getOrNull(name) as DroneBlueprint?

            // If a ship has an invalid drone, just ignore it.
            if (drone == null) {
                println("[WARN] Invalid drone '$name' in ship '${this.name}' initial drones")
                continue
            }

            // If the ship doesn't have enough drone slots, just give it some more.
            // This is required for the flagship at least, but it makes sense
            // that if there is some fixed list of included drones then there ought
            // to be enough space for them.
            if (drones!!.drones.size <= idx) {
                drones!!.drones.add(null)
            }

            drones!!.drones[idx] = Drones.DroneInfo(drone, null)
        }

        // Load any augments
        for (name in type.initialAugments) {
            val augment = sys.blueprintManager.getOrNull(name) as AugmentBlueprint?

            // Also ignore invalid blueprints.
            if (augment == null) {
                println("[WARN] Invalid augment '$name' in ship '${this.name}' initial augments")
                continue
            }

            augment.onShipSpawn(this)
            augments.add(augment)
        }

        // If we don't have an oxygen system, the rooms start with no oxygen by default
        if (oxygen == null) {
            for (room in rooms) {
                room.oxygen = 0f
            }
        }

        // Calculate the number of each type of augment
        cargoUpdated()
    }

    fun render(g: Graphics, interiorVisible: Boolean, selected: Room?) {
        // Draw the shield
        renderShields()

        // Draw departing shots. We'll draw the rest of them later.
        for (proj in projectiles) {
            if (proj.drawUnderShip) {
                proj.render(g, this)
            }
        }

        for (system in systems)
            system.drawBackground(g)

        // If the ship is exploding, draw no further
        if (isDead) {
            if (isGone) {
                //health++ // testing
                //gibs.forEach { it.reset() }
                return
            }
            for (gib in gibs.asReversed()) {
                gib.draw(g, hullOffset)
            }
        } else {
            // Animate between cloaked and uncloaked
            val cloakFade = cloaking?.cloakFade ?: 0f

            val interiorAlpha = 1f - cloakFade / 2f
            val hullAlpha = when (cloakFade) {
                0f -> 1f
                else -> interiorAlpha * 0.75f
            }
            hullImage.draw(hullOffset.x, hullOffset.y, Colour(1f, 1f, 1f, hullAlpha))

            if (cloakFade != 0f) {
                requireNotNull(cloakImage) { "Ship '$name' cloaked, but it doesn't have a cloak image!" }

                cloakImage.draw(
                    hullOffset.x + cloakOffset.x, hullOffset.y + cloakOffset.y,
                    Colour(1f, 1f, 1f, cloakFade)
                )
            }

            if (interiorVisible) {
                drawInterior(g, selected, interiorAlpha)
            }
        }

        // Draw the debug hardpoint visuals
        if (sys.debugFlags.showHardpoints.set) {
            for ((index, hp) in hardpoints.withIndex()) {
                val pos = hp.spec.position
                g.colour = Colour.red
                g.drawLine(pos.x - 5f, pos.y - 5f, pos.x + 5f, pos.y + 5f)
                g.drawLine(pos.x + 5f, pos.y - 5f, pos.x - 5f, pos.y + 5f)

                sys.getFont("JustinFont8").drawString(
                    pos.x.f + 5f,
                    pos.y + 8f,
                    index.toString(), Colour.red
                )
            }
        }

        // Draw the projectiles, except for those we already
        // drew underneath the ship earlier.
        for (proj in projectiles) {
            if (!proj.drawUnderShip) {
                proj.render(g, this)
            }
        }

        for (beam in inboundBeams) {
            beam.renderInbound(g)
        }

        // Draw any drones flying around the ship
        for (drone in externalDrones) {
            drone.renderExternal(g)
        }

        // Draw the floating animations (eg, from projectile explosions)
        for (a in animations)
            a.render(g)

        animations.removeIf { a -> a.isFinished }

        // Draw the damage numbers that appear when your ship is hit
        for (num in damageNumbers) {
            num.draw()
        }
    }

    private fun renderShields() {
        val level = shields?.activeShields ?: 0

        // Draw the standard (non-Zoltan) shield
        val shieldMax = if (isPlayerShip) 3.5f else 4f // This is odd, but correct
        val levelFraction = level / shieldMax
        val alpha = SHIELD_OPACITY_BASE + SHIELD_OPACITY_SCALING * levelFraction

        if (level == 0) {
            // Do nothing, shields disabled
        } else {
            renderSingleShield(alpha, Colour.white)
        }

        // Draw the super shields
        if (superShield != 0) {
            var superShieldAlpha = superShield.f / maxSuperShield

            if (level == 0) {
                superShieldAlpha = SHIELD_OPACITY_BASE + SHIELD_OPACITY_SCALING * superShieldAlpha
            }

            renderSingleShield(superShieldAlpha, SYS_ENERGY_ACTIVE)

            // TODO fix the ugly darkened line around the edge of enemy super-shields
            // I suspect this is caused by the scaling - Slick has had problems
            // rendering drones while rotating with white aliasing artefacts
            // along their edges, so maybe it's the same thing?
            // Or it could possible be some kind of SRGB-related issue.
        }
    }

    private fun renderSingleShield(alpha: Float, filter: Colour) {
        val basePosX = shieldOrigin.x - shieldHalfSize.x
        val basePosY = shieldOrigin.y - shieldHalfSize.y

        // Draw the image scaled to fit the
        shieldImage.draw(
            basePosX.f, basePosY.f,
            basePosX.f + shieldHalfSize.x * 2, basePosY.f + shieldHalfSize.y * 2,
            0f, 0f,
            shieldImage.width.f, shieldImage.height.f,
            alpha, filter
        )
    }

    /**
     * Render the crosshairs, beam marker, etc that appear when a weapon
     * is targeted to a specific room. This is in Ship rather than the
     * hostile ship UI, as the player can teleport bombs to their own ship
     * and for debug purposes we may want to show where the AI is targeting
     * their shots.
     */
    fun renderTargeting(g: Graphics, targets: Weapons.TargetList) {
        for (target in targets) {
            if (target.targetShip != this)
                continue

            if (target is SelectedTarget.BeamAim)
                renderTargetingBeam(g, target)
            if (target !is SelectedTarget.RoomAim)
                continue

            val room = target.room

            val weaponNumber = min(target.weaponNumber + 1, 4)
            val img = sys.getImg("img/misc/crosshairs_placed${weaponNumber}.png")

            val pos = Point(room.pixelCentre)

            pos.x -= img.width / 2
            pos.y -= img.height / 2

            img.draw(pos)
        }

        if (targets.beamAiming?.targetShip == this) {
            inboundBeamAim = targets.beamAiming
            targets.beamAiming?.let { renderTargetingBeam(g, it) }
        } else {
            inboundBeamAim = null
        }
    }

    fun renderTargetingBeam(g: Graphics, beam: SelectedTarget.BeamAim) {
        // This is false for beams the player is currently aiming, where their
        // mouse is too close to the start of the beam.
        if (!beam.visible)
            return

        // FTL has a nice 'cap' image that squares off the ends of a beam. However
        // this seemed to cause flickering at the transition between the cap and centre
        // image when rotated, so don't bother with it. Notably the game itself
        // doesn't seem to use this asset.
        // TODO use the cap without a flicker, it really does look nicer.
        val img = sys.getImg("img/misc/beam_center.png")

        g.pushTransform()
        g.translate(beam.startShipPoint.x.f, beam.startShipPoint.y.f)
        g.rotate(0f, 0f, Math.toDegrees(beam.angle.toDouble()).toFloat())

        // Draw the stretched-out middle image
        val length = (beam.weapon.type as BeamBlueprint).length
        img.draw(
            0f, -img.height / 2f, length.f, img.height.f / 2f,
            // Stretch out the centre of the beam - if we don't do this, then
            // the ends appear faded (windows and nvidia, if that's important).
            0.5f, 0f, 0.5f, img.height.f
        )

        g.popTransform()
    }

    private fun drawInterior(g: Graphics, selected: Room?, alpha: Float) {
        floorImage?.draw(
            floorOffset.x + hullOffset.x,
            floorOffset.y + hullOffset.y,
            Colour(1f, 1f, 1f, alpha)
        )

        // Draw the rooms
        for (room in rooms) {
            var roomSelected = selected == room

            // If the user is currently aiming a beam, highlight
            // the rooms it would hit.
            if (inboundBeamAim?.hitRooms?.contains(room) == true) {
                roomSelected = true
            }

            room.render(g, roomSelected, alpha)
        }

        // Draw the doors
        for (door in doors) {
            door.render(g, alpha)
        }

        // Draw the crew
        for (crew in crew) {
            // If the crew provides vision, that works if playerHasVision
            // isn't set. This prevents flickering as the crew walks between
            // rooms without sensors.
            if (crew.room.playerHasVision || crew.providesPlayerVision) {
                crew.draw(g)
            }
        }

        // Draw the health bars on top of all the other crew, so two
        // fighting crewmembers can't block one of their health bars.
        for (crew in crew) {
            if (crew.room.playerHasVision || crew.providesPlayerVision) {
                crew.drawForeground(g)
            }
        }

        // Draw the system foregrounds
        for (system in systems)
            system.drawForeground(g)
    }

    fun screenPosToShipPos(point: Point) {
        point.x = Math.floorDiv(point.x, ROOM_SIZE)
        point.y = Math.floorDiv(point.y, ROOM_SIZE)
        point.sub(offset.x, offset.y)
    }

    fun shipToRoomPos(pos: IPoint): RoomPoint? {
        if (pos is RoomPoint)
            return pos

        for (r in rooms) {
            if (r.containsAbsolute(pos))
                return RoomPoint(r, pos - r.position)
        }

        return null
    }

    fun update(dt: Float) {
        // Update the power, in case a Zoltan has moved between rooms.
        updateAvailablePower()

        updateExterior(dt)

        if (isDead) {
            for (gib in gibs)
                gib.update(dt)
            return
        }

        for (room in rooms)
            room.update(dt)

        // Update the door open/close animations
        for (door in doors) {
            door.update(dt)
        }

        // Move oxygen around the ship
        OxygenTransfer.update(this, dt)

        averageOxygen = rooms.map { it.oxygen }.average().toFloat()

        crewAI.update()
        intruderAI.update()

        updateCrew(dt)

        // This FTL charge logic is for the player; enemies have a completely
        // separate charge time stored in ShipAI.
        if (canChargeFTL) {
            var rate = engines?.chargeRate ?: 0f

            // When you're in a dangerous environment without an enemy,
            // the charge speed is boosted.
            if (sys.getEnemyOf(this) == null) {
                rate *= 3
            }

            ftlChargeProgress += rate * dt / 68f
        }
        if (ftlChargeProgress > 1f) {
            ftlChargeProgress = 1f
        }

        for ((augment, totalValue) in augmentValues) {
            augment.update(this, dt, totalValue)
        }
    }

    private fun updateCrew(dt: Float) {
        // Duplicate the crew and drone lists, in case some are removed
        // by dying or (in the case of drones) the enemy ship blowing up.
        for (crew in crew.toTypedArray())
            crew.update(dt)

        // Update the list of intruders and friendly (non-intruder) crewmembers
        require(intruders is ArrayList)
        require(friendlyCrew is ArrayList)
        intruders.clear()
        friendlyCrew.clear()
        for (crew in crew) {
            when (crew.mode) {
                AbstractCrew.SlotType.CREW -> friendlyCrew.add(crew)
                AbstractCrew.SlotType.INTRUDER -> intruders.add(crew)
            }
        }
    }

    private fun updateExterior(dt: Float) {
        // Walk backwards, since projectiles may remove themselves
        for (i in projectiles.size - 1 downTo 0) {
            projectiles[i].update(dt, this)
        }

        // Check for collisions between any two projectiles. To avoid
        // checking a pair twice (but in a swapped order), only check
        // a projectile against those that come after it in the list.
        // By iterating down, we can again ignore the problem of changing
        // indices when projectiles are removed.
        for (lowerI in projectiles.size - 1 downTo 0) {
            val first = projectiles[lowerI]
            if (!first.collisionsEnabled)
                continue

            for (upperI in projectiles.size - 1 downTo lowerI + 1) {
                val second = projectiles[upperI]

                if (!second.collisionsEnabled)
                    continue

                // Make sure the projectiles are targeting different ships - otherwise
                // you could shoot down your own projectiles, and in particular flak
                // projectiles would collide with each other.
                if (first !is AbstractProjectile || second !is AbstractProjectile) {
                    continue
                }

                // A projectile that's not fired at a ship is assumed to be friendly,
                // like a defence drone laser.
                val isFirstFriendly = first.targetShip != this
                val isSecondFriendly = second.targetShip != this
                if (isFirstFriendly == isSecondFriendly) {
                    continue
                }

                // Run a CCD check to see if the projectiles have collided
                // at any point between the last frame and this frame.
                if (!IProjectile.checkCollision(
                        dt,
                        first.position, second.position,
                        first.velocity, second.velocity,
                        first.hitboxRadius.f, second.hitboxRadius.f
                    )
                ) {
                    continue
                }

                first.hitOtherProjectile(this)
                second.hitOtherProjectile(this)

                // Play a single hit sound. It seems that FTL doesn't
                // play this missile explode sound when a drone shoots
                // down a missile, so always using one is probably
                // close to correct.
                // It also means we don't play two sounds for the same
                // thing, which would be the case if hitOtherProjectile
                // played a sound effect.
                projectileCollisionSound.play()

                projectiles.removeAt(upperI)
                projectiles.removeAt(lowerI)

                // Don't process any more projectiles in relation to the
                // one from the outer loop, as we removed it.
                break
            }
        }

        // Check for collisions between projectiles and drones
        for (droneI in externalDrones.size - 1 downTo 0) {
            val drone = externalDrones[droneI]

            for (projectileI in projectiles.size - 1 downTo 0) {
                val proj = projectiles[projectileI]

                if (!proj.collisionsEnabled)
                    continue

                if (!IProjectile.checkCollision(
                        dt,
                        drone.flightController.position, proj.position,
                        drone.flightController.velocity, proj.velocity,
                        drone.hitboxRadius.f, proj.hitboxRadius.f
                    )
                ) {
                    continue
                }

                val distSq = drone.flightController.position.distToSq(proj.position)
                val maxDist = drone.hitboxRadius + proj.hitboxRadius

                // No overlap?
                if (distSq > maxDist * maxDist)
                    continue

                if (!drone.canCollideWith(proj))
                    continue

                proj.hitOtherProjectile(this)
                projectiles.removeAt(projectileI)

                // The drone is expected to play a sound.
                drone.hitProjectile(proj)

                break
            }
        }


        // Update the animations
        for (a in animations) {
            a.update(dt)
        }

        // update returns false if the number should be removed
        damageNumbers.removeIf { !it.update(dt) }
    }

    fun updateAvailablePower() {
        // Don't update our power during saving/loading, since different types
        // of power that systems can use (notably batteries, but other mods
        // might add similar power types) need all the systems to be loaded
        // first, and this would thus cause a system to pull different types
        // of power as a replacement during deserialisation.
        if (sys.isCurrentlyLoadingSave)
            return

        powerAvailableTypes.clear()

        for (type in EnergySource.GLOBAL_TYPES) {
            type.adjustShipPower(this, powerAvailableTypes)
        }

        val preEnvPower = powerAvailableTypes.values.sum()

        // Let the environment (for ion storms) deduct power
        sys.currentBeacon.getEnvironment(sys).adjustShipPower(this, powerAvailableTypes)

        // Remember how much power is unavailable, as it's shown in the UI.
        val postEnvPower = powerAvailableTypes.values.sum()
        blockedReactorPower = max(0, preEnvPower - postEnvPower)

        // First make the systems use the power they're already using
        for (system in systems) {
            if (system !is MainSystem)
                continue

            system.consumePowerFirst(powerAvailableTypes)
        }

        // Then grab any extra for increasing power
        for (system in systems) {
            if (system !is MainSystem)
                continue

            system.consumePowerSecond(powerAvailableTypes)
        }
    }

    /**
     * Attack the shields, either damaging the super-shield or popping
     * a regular shield bubble.
     */
    fun attackShields(damage: Damage, damagePos: IPoint?) {
        // Break super shields first
        // The reason this function is in Ship and not Shields is to support
        // ships with a super shield, but no shield system.
        if (superShield != 0) {
            // Clamp damages to at least zero, to avoid negative values
            // repairing the super shield.
            val damageValue = max(damage.ionDamage, 0) * 2 + max(damage.hullDamage, 0)
            superShield -= damageValue

            if (damagePos != null && damageValue > 0) {
                showDamageTextAt(damagePos, damageValue, DAMAGE_COLOUR_ZOLTAN)
            }

            return
        }

        // Only projectiles with hull damage pop shields!
        if (damage.hullDamage > 0) {
            shields?.popShieldLayer()
        }

        attackShieldsIon(damage)
    }

    /**
     * Apply the ion and system damage components of a [Damage] object to the shields.
     *
     * This is the standard behaviour that should be used when attacking shields with
     * a weapon, if it can do ion damage to the ship's shield.
     */
    fun attackShieldsIon(damage: Damage) {
        if (damage.ionDamage <= 0 || shields == null)
            return

        damage(shields!!.room!!, damage.copyIonAndSys())
    }

    fun damage(target: Room, damage: Damage, textPos: IPoint? = null) {
        var hullMult = 1
        if (target.system == null) {
            // TODO does this properly apply hullBust values other than 1 (vanilla doesn't do that)
            hullMult += damage.emptyRoomBonus
        }

        val hullDamage = damage.hullDamage * hullMult

        showDamageText(target, hullDamage, damage.effectiveSysDamage, damage.ionDamage, textPos)
        crewWeaponDamage(target, damage.effectiveCrewDamage.f, damage)

        if (sys.debugFlags.noDmg.set)
            return

        health -= hullDamage
        target.system?.dealDamage(damage.effectiveSysDamage, damage.ionDamage)

        // Fire and breach are mutually exclusive, if a fire spawns then a breach cannot.
        if (Random.rollChance(damage.fireChance)) {
            // Spawns two fires (or possibly only one, if they both roll on the same cell).
            target.spawnFire()
            target.spawnFire()
        } else if (Random.rollChance(damage.breachChance)) {
            // Spawns two fires (or possibly only one, if they both roll on the same cell).
            target.spawnBreach()
        }
    }

    fun playDamageEffect(type: AbstractWeaponBlueprint, position: IPoint): FloatingAnimation {
        val animation = sys.animations[type.explosion ?: error("Default explosion not set")]
        return playCentredAnimation(animation, position)
    }

    private fun showDamageText(target: Room, damage: Int, systemDamage: Int, ionDamage: Int, pos: IPoint?) {
        // Don't show the popup for system damage in a system-less room.
        if (target.system == null && damage == 0)
            return

        // Damage numbers split based on their type, also huge thanks to Gabriel Cooper
        // for helpfully providing data on the order of damage numbers!
        if (ionDamage > 0 && target.system != null) {
            target.showDamageText(ionDamage, DAMAGE_COLOUR_ION, pos)
        }
        val extraSystemDamage = systemDamage - damage.coerceAtLeast(0)
        if (extraSystemDamage > 0 && target.system != null) {
            // because the specific sys damage shown is that that exceeds the hull damage
            target.showDamageText(extraSystemDamage, DAMAGE_COLOUR_SYSTEM, pos)
        }
        if (damage > 0) {
            target.showDamageText(damage, Colour.white, pos)
        }
    }

    /**
     * Show the damage text, as it relates to a projectile or impact at a given position.
     *
     * This doesn't draw the text exactly at said position - it's shown a bit above.
     */
    fun showDamageTextAt(damagePos: IPoint, imageName: String, colour: Colour) {
        val image = sys.getImg("img/numbers/$imageName.png")
        val floatingNum = DamageNumber(damagePos.x.f, damagePos.y.f, image, colour)
        floatingNum.y -= 30f
        floatingNum.x += VisualRandom.nextInt(-10, 10).f
        damageNumbers.add(floatingNum)
    }

    fun showDamageTextAt(damagePos: IPoint, number: Int, colour: Colour) {
        val clamped = number.coerceIn(0..9)
        val imageName = "Text_${clamped}_L"
        showDamageTextAt(damagePos, imageName, colour)
    }

    fun crewWeaponDamage(target: Room, damage: Float, dmg: Damage) {
        for (crew in target.crew) {
            crew.dealDamage(ShipDamage(damage, dmg))
        }
    }

    fun resetAfterJump() {
        // Recharge the shields
        shields?.let { it.activeShields = it.selectedShieldBars }

        // And reset the FTL drive
        ftlChargeProgress = 0f

        // Jumping clears the super shield (if the augment is
        // present, it'll add it back in afterwards).
        superShield = 0

        // Remove all incoming and outgoing projectiles
        projectiles.clear()

        // Get rid of any drones orbiting us
        externalDrones.clear()

        // Reset the weapon charge times
        for (hp in hardpoints) {
            hp.weapon?.timeCharged = 0f

            // Reset the chain/charge count
            hp.weapon?.chainCount = 0
            hp.weapon?.extraCharges = 0
        }

        // Clear any previously-set scripted power limits, and re-apply
        // any that were previously set at this beacon.
        updateScriptedPowerLimits()

        for (augment in augmentValues.keys) {
            augment.onJump(this)
        }

        for (system in systems) {
            system.onJump()
        }

        // Jumping resets the door health. I haven't checked that FTL does
        // this, but the wiki says it does and it's also convenient as it
        // reduces the size of the savefile.
        for (door in doors) {
            door.resetHealth()
        }
    }

    /**
     * This updates the power limits imposed on systems by scripted events.
     *
     * This must only be called on the player ship - on the enemy ship, systems
     * should be limited directly. This is because the player can jump between
     * beacons, and the effects need to be cleared and re-applied as appropriate.
     */
    fun updateScriptedPowerLimits() {
        for (system in systems) {
            val limit = sys.currentBeacon.powerLimitEffects[system.codename]
            system.scriptedPowerLimit = limit
        }
    }

    fun addCrewMember(info: LivingCrewInfo, initial: Boolean, isIntruder: Boolean = false): LivingCrew {
        var freeSpace: RoomPoint? = null
        val mode = if (isIntruder) AbstractCrew.SlotType.INTRUDER else AbstractCrew.SlotType.CREW

        // If this crewmember is being created with the ship, put them
        // in a system that makes sense.
        if (initial && !isIntruder) {
            val systems = listOf(piloting, engines, weapons, shields)
            for (system in systems) {
                if (system == null)
                    continue

                val room = system.room!!

                // If someone's already at the computer, don't put another person there.
                val firstSlot = system.configuration.computerPoint ?: ConstPoint.ZERO
                if (!room.isSlotFree(firstSlot, mode))
                    continue

                // Stand by the computer, if possible
                freeSpace = RoomPoint(room, firstSlot)
                break
            }
        }

        // Otherwise just put them wherever they'll fit.
        if (freeSpace == null) {
            freeSpace = findSpaceForCrew(null, mode)
        }

        val crewMember = info.race.spawn(freeSpace.room, mode)
        crewMember.info = info
        crew.add(crewMember)
        crewMember.jumpTo(freeSpace)

        // If this is an intruder, make sure they don't count as being
        // 'owned' by the player. This means they can't use the player's
        // augments, for example.
        if (isIntruder) {
            crewMember.ownerShip = null
        }

        sys.updatePlayerCrew()

        return crewMember
    }

    /**
     * Find a slot somewhere in the ship (accessible from [startingRoom],
     * if this ship has some disconnected rooms) that's empty.
     *
     * This works with the pathfinding slots - the points that a crewmember
     * can be sent to by right-clicking. Thus a room that has no crew in
     * it would be considered full if crew are walking towards every spot.
     *
     * Similarly, a room full of crew would be considered empty if they're
     * all walking elsewhere.
     */
    fun findSpaceForCrew(startingRoom: Room?, type: AbstractCrew.SlotType): RoomPoint {
        val checked = HashSet<Room>()

        // Checks a single room for free space
        fun checkRoom(room: Room): RoomPoint? {
            if (checked.contains(room))
                return null
            checked.add(room)

            val freeSpot = room.firstFreeSlot(type)
            if (freeSpot != null) {
                return RoomPoint(room, freeSpot)
            }

            for (door in room.doors) {
                val neighbour = door.other(room) ?: continue
                checkRoom(neighbour)?.let { return it }
            }

            return null
        }

        // If no room has been specified, start at the piloting room.
        // This is the behaviour used for newly-spawned crew.
        // This might be called very early on before the systems
        // are added, so don't require that piloting exists.
        val firstRoom = startingRoom ?: piloting?.room ?: rooms.first()

        // Check every room via recursion. This will prefer the first
        // room if it's empty, and will only return a room if every room
        // in a path from it to piloting is connected.
        checkRoom(firstRoom)?.let { return it }

        // No free space!
        error("Couldn't find any free space in ship $name, starting at room $firstRoom")
    }

    fun updateAvailableSystems() {
        systems = rooms.mapNotNull { it.system }
        mainSystems = systems.mapNotNull { it as? MainSystem }.sortedBy { it.sortingType }
        subSystems = systems.mapNotNull { it as? SubSystem }.sortedBy { it.sortingType }

        weapons = systems.mapNotNull { it as? Weapons }.firstOrNull()
        drones = systems.mapNotNull { it as? Drones }.firstOrNull()
        engines = systems.mapNotNull { it as? Engines }.firstOrNull()
        shields = systems.mapNotNull { it as? Shields }.firstOrNull()
        medbay = systems.mapNotNull { it as? Medbay }.firstOrNull()
        clonebay = systems.mapNotNull { it as? Clonebay }.firstOrNull()
        cloaking = systems.mapNotNull { it as? Cloaking }.firstOrNull()
        teleporter = systems.mapNotNull { it as? Teleporter }.firstOrNull()
        hacking = systems.mapNotNull { it as? Hacking }.firstOrNull()
        mindControl = systems.mapNotNull { it as? MindControl }.firstOrNull()
        piloting = systems.mapNotNull { it as? Piloting }.firstOrNull()
        sensors = systems.mapNotNull { it as? Sensors }.firstOrNull()
        backupBattery = systems.mapNotNull { it as? BackupBattery }.firstOrNull()
        doorsSystem = systems.mapNotNull { it as? Doors }.firstOrNull()
        oxygen = systems.mapNotNull { it as? Oxygen }.firstOrNull()

        // If we have multiple artillery systems, sort them by the order
        // they appear in the XML. This probably isn't necessary, but if
        // we're using this order to display them in the UI or anything
        // like that it's nice to have this order.
        artillery = systems.mapNotNull { it as? Artillery }.sortedBy { it.configuration.spec.systemIndex }

        // The UI will need to change to reflect this
        // Note that this function is called very early on, before shipUI
        // is initialised, right after ship initialisation.
        sys.shipUI?.shipModified()
    }

    fun cargoUpdated() {
        // Pack the weapons and drones so all the empty slots are at the end.
        var weaponDest = 0
        for (hp in hardpoints) {
            val weapon = hp.weapon ?: continue
            hardpoints[weaponDest++].weapon = weapon
        }
        for (i in weaponDest until hardpoints.size) {
            hardpoints[i].weapon = null
        }

        // Avoid power artefacts from Zoltan power interactions, as how that
        // power is distributed needs to be updated when the weapons change.
        weapons?.powerStateChanged()

        drones?.drones?.let { droneList ->
            var droneDest = 0
            for (drone in droneList) {
                if (drone == null)
                    continue
                droneList[droneDest++] = drone
            }
            for (i in droneDest until droneList.size) {
                droneList[i] = null
            }
        }

        // Update all the buttons that have to change to reflect
        // the cargo being modified.
        // This is called very early, before the ship UI is created.
        sys.shipUI?.shipModified()

        // Update the number of each type of augment, for fast lookups.
        privateAugmentValues.clear()
        for (aug in augments) {
            privateAugmentValues[aug] = aug.value + (augmentValues[aug] ?: 0f)
        }
    }

    /**
     * Place an item in this ship's inventory.
     *
     * If [forced] is true and the ship's inventory is full, the item is placed
     * into the left-behind-when-you-jump window. Otherwise, nothing happens.
     *
     * @return True if the item fits in the ship, false otherwise.
     */
    fun addBlueprint(item: Blueprint, forced: Boolean): Boolean {
        // Augments can only go in their special area
        if (item is AugmentBlueprint) {
            // Break down non-stackable duplicated augments
            // to scrap, using the AUGMENT_FULL event.
            // Note this goes before the max augments check, so the player
            // gets paid for duplicate augments when they're full.
            if (!item.stackable && augments.any { it == item }) {
                val realEvent = sys.eventManager["AUGMENT_FULL"].resolve()
                val synthetic = DialogueWindow.SyntheticEvent(realEvent.text!!.resolve(Random))
                synthetic.resources.scrap = 25
                sys.shipUI.showSyntheticDialogue(synthetic)
                return true
            }

            if (augments.size < MAX_AUGMENTS) {
                augments.add(item)
                cargoUpdated()
                return true
            }

            // TODO handle forced=true.
            return false
        }

        if (item is AbstractWeaponBlueprint) {
            val weaponSlotCount = weaponSlots ?: hardpoints.size

            for (slot in 0 until weaponSlotCount) {
                if (hardpoints[slot].weapon != null)
                    continue

                hardpoints[slot].weapon = item.buildInstance(this)
                cargoUpdated()
                return true
            }
        }

        val drones = drones
        if (item is DroneBlueprint && drones != null) {
            for (slot in 0 until drones.drones.size) {
                if (drones.drones[slot] != null)
                    continue

                drones.drones[slot] = Drones.DroneInfo(item, null)
                cargoUpdated()
                return true
            }
        }

        for ((slot, current) in cargoBlueprints.withIndex()) {
            if (current != null)
                continue

            cargoBlueprints[slot] = item
            cargoUpdated()
            return true
        }

        // TODO handle forced=true.
        // TODO if the overflow box is also full, use the EQUIP_FULL event
        //  and award 25 scrap.

        return false
    }

    /**
     * Remove an item from the ship's inventory, including it's weapons and
     * drones areas.
     *
     * Returns true if the item was removed, false otherwise.
     */
    fun removeBlueprint(item: Blueprint): Boolean {
        // Check the cargo storage first, so the player doesn't have to swap
        // the item in again if they have multiple.
        for ((index, cargo) in cargoBlueprints.withIndex()) {
            if (cargo != item)
                continue
            cargoBlueprints[index] = null

            cargoUpdated()
            return true
        }

        if (augments.remove(item)) {
            cargoUpdated()
            return true
        }

        drones?.let { drones ->
            for ((index, drone) in drones.drones.withIndex()) {
                if (drone?.type != item)
                    continue

                drone.instance?.removeInstance()
                drones.drones[index] = null

                cargoUpdated()
                return true
            }
        }

        for (hp in hardpoints) {
            if (hp.weapon?.type != item)
                continue

            hp.weapon = null

            cargoUpdated()
            return true
        }

        return false
    }

    /**
     * Check if the ship has an augment by name.
     *
     * This throws an exception if the named blueprint doesn't exist.
     */
    fun hasAugment(name: String): Boolean {
        // Multiverse removes the O2_MASKS blueprint, so we need to account
        // for augments not existing.
        val blueprint = sys.blueprintManager.getOrNull(name) as AugmentBlueprint? ?: return false
        return augmentValues.containsKey(blueprint)
    }

    /**
     * Get the value for the given augment. If multiple of an augment
     * are installed, their values are summed together.
     *
     * Returns 0 if the ship has no such augments.
     */
    fun getAugmentValue(name: String): Float {
        return getAugmentValueOrNull(name) ?: 0f
    }

    /**
     * Get the value for the given augment. If multiple of an augment
     * are installed, their values are summed together.
     *
     * Returns null if the ship has no such augments, or if the specified
     * augment doesn't exist.
     */
    fun getAugmentValueOrNull(name: String): Float? {
        val blueprint = sys.blueprintManager.getOrNull(name) as AugmentBlueprint? ?: return null
        return augmentValues[blueprint]
    }

    /**
     * Update [Room.reservedPlayerSlots] and [Room.reservedEnemySlots]
     * for every room on the ship.
     *
     * This should mainly be called by [AbstractCrew], whenever a change
     * to their pathfinding target is made. It can also be called whenever
     * the crew on the ship are changed, such as from deaths or new crew.
     */
    fun updateCrewReservedSlots() {
        val conflicts = ArrayList<AbstractCrew>()

        for (room in rooms) {
            room.updateCrewReservedSlots(conflicts)
        }

        // If two crewmembers are in, or set to be in, the same location then
        // one of them will be in the conflicts list. Make them find a new
        // free slot.
        for (conflict in conflicts) {
            val freeSpot = findSpaceForCrew(conflict.room, conflict.mode)
            require(conflict.setTargetRoom(freeSpot.room)) { "Failed to set target of empty room!" }
        }
    }

    /**
     * Perform the projectile missed test.
     *
     * This credits piloting/engine skills if it succeeds.
     *
     * [deltaEvasion] is added to the ship's evasion for this calculation. Note
     * this is the opposite of [AbstractWeaponBlueprint.accuracyModifier], which
     * when positive reduces the effective evasion.
     *
     * If [missDeadEnemies] is true, this will always return true if this ship
     * is an enemy ship that's been crew-killed. This should be set so that you
     * can't kill an enemy ship with your in-flight volley after they surrender,
     * however environmental stuff like asteroids should set this to false.
     */
    fun pickMissed(deltaEvasion: Int = 0, missDeadEnemies: Boolean = true): Boolean {
        // Weapons fired at enemy ships before they surrender always
        // miss after a surrender.
        if (!isPlayerShip && sys.getEnemyOf(this) == null && missDeadEnemies)
            return true

        val missed = Random.rollChance(evasion + deltaEvasion)
        if (missed) {
            piloting?.addSkillPoint(Skill.PILOTING)
            engines?.addSkillPoint(Skill.ENGINES)
        }
        return missed
    }

    fun playCentredAnimation(animation: AnimationSpec, centre: IPoint): FloatingAnimation {
        val firstFrame = animation.spriteAt(sys, 0)
        val offsetPos = ConstPoint(centre.x - firstFrame.width / 2, centre.y - firstFrame.height / 2)
        val floating = FloatingAnimation(sys, animation, offsetPos)
        animations += floating
        return floating
    }

    fun enemyShipUpdated() {
        drones?.enemyShipUpdated()

        val enemy = sys.getEnemyOf(this)

        // If our weapons are currently targeted on the now-neutral enemy,
        // un-target them.
        val targets = weapons?.selectedTargets
        if (targets != null) {
            val toUnTarget = ArrayList<Int>()
            for (target in targets.iterator()) {
                if (target.targetShip == this || target.targetShip == enemy)
                    continue

                toUnTarget.add(target.weaponNumber)
            }

            for (id in toUnTarget) {
                targets.unTarget(id)
            }
        }
    }

    fun saveCrewPositions() {
        for (member in crew) {
            if (member.mode != AbstractCrew.SlotType.CREW)
                continue

            if (member !is LivingCrew)
                continue

            if (member.ownerShip != this)
                continue

            val currentPos = member.pathingTarget ?: member.standingPosition ?: continue
            member.savedPosition = currentPos.shipPoint
        }
    }

    fun loadCrewPositions() {
        // Note that since we change the crew target positions one at a time,
        // if crew need to swap positions then we can end up not being able
        // to do that, even if it should be valid.
        for (member in crew) {
            if (member.mode != AbstractCrew.SlotType.CREW)
                continue

            if (member !is LivingCrew)
                continue

            if (member.ownerShip != this)
                continue

            val saved = member.savedPosition ?: continue
            val roomPos = shipToRoomPos(saved) ?: continue

            if (!member.setTargetRoom(roomPos.room, roomPos)) {
                // If our spot is taken, go somewhere else in the room.
                member.setTargetRoom(roomPos.room)
            }
        }
    }


    /**
     * Serialise this ship and everything related to it (projectiles,
     * crew, etc) into an XML element.
     */
    fun saveToXML(elem: Element, globalRefs: ObjectRefs) {
        // Create IDs for all the objects that might reference each other
        // Note that events can reference crewmembers, so register them
        // into the global refs.
        val refs = ObjectRefs(globalRefs)
        for (crew in this.crew) {
            globalRefs.register(crew, crew.codename)
        }

        // Build the XML
        SaveUtil.addObjectId(elem, refs, this)

        // These two are read by InGameState, as they're needed for constructor arguments.
        SaveUtil.addAttr(elem, "shipId", name)
        SaveUtil.addAttr(elem, "specId", spec?.name ?: "null")

        val resources = ResourceSet()
        resources.fuel = fuelCount
        resources.missiles = missilesCount
        resources.droneParts = dronesCount
        resources.scrap = scrap

        val resourcesElem = Element("resources")
        resources.saveToXML(resourcesElem, refs)
        elem.addContent(resourcesElem)

        SaveUtil.addAttrFloat(elem, "ftlProgress", ftlChargeProgress)
        SaveUtil.addAttrInt(elem, "reactorPower", purchasedReactorPower)
        SaveUtil.addAttrInt(elem, "health", health)

        SaveUtil.addTagInt(elem, "escapeHealth", escapeHealth, 0)
        SaveUtil.addTagInt(elem, "surrenderHealth", surrenderHealth, 0)
        SaveUtil.addTagFloat(elem, "escapeTimer", escapeTimer, null)
        SaveUtil.addTagInt(elem, "maxSuperShield", maxSuperShield, 5)
        SaveUtil.addTagInt(elem, "superShield", superShield, 0)

        SaveUtil.addAttrRef(elem, "bossManager", refs, boss)

        val crewListElem = Element("crew")
        for (crew in this.crew) {
            // We save drones separately
            if (crew is AbstractIndoorsDrone.Pawn)
                continue

            val crewElem = Element("crewMember")
            crew.saveToXML(crewElem, refs)
            crewListElem.addContent(crewElem)
        }
        elem.addContent(crewListElem)

        val systemListElem = Element("systems")
        for (system in systems) {
            val systemElem = Element("system")
            system.saveToXML(systemElem, refs)

            // Save the room this system is in, as we'll need it to match up
            // artillery weapons to the correct room on the flagship.
            SaveUtil.addAttrInt(systemElem, "room", system.room!!.id)

            systemListElem.addContent(systemElem)
        }
        elem.addContent(systemListElem)

        // Serialise the weapons
        val weaponsElem = Element("weapons")
        for ((index, hardpoint) in hardpoints.withIndex()) {
            val weapon = hardpoint.weapon ?: continue

            val weaponElem = Element("weapon")
            SaveUtil.addAttrInt(weaponElem, "hardpoint", index)
            weapon.saveToXML(weaponElem, refs)
            weaponsElem.addContent(weaponElem)
        }
        elem.addContent(weaponsElem)

        // Serialise the drones. If a drone is deployed to another ship, it's
        // serialised on that ship instead of this one.
        val dronesElem = Element("drones")
        for (drone in externalDrones) {
            // Skip drones that are left over from a now-destroyed fight
            if (!sys.isShipPresent(drone.ownerShip))
                continue

            val droneElem = Element("drone")
            drone.saveToXML(droneElem, refs)
            dronesElem.addContent(droneElem)
        }
        for (pawn in crew.mapNotNull { it as? AbstractIndoorsDrone.Pawn }) {
            val droneElem = Element("drone")
            pawn.drone.saveToXML(droneElem, refs)
            dronesElem.addContent(droneElem)
        }
        for (flyingDrone in projectiles.mapNotNull { it as? FlyingDroneProjectile }) {
            val droneElem = Element("drone")
            flyingDrone.drone.saveToXML(droneElem, refs)
            dronesElem.addContent(droneElem)
        }
        elem.addContent(dronesElem)

        // Serialise the augments. Since these are only the blueprint name,
        // they don't need a new parent element line drones/weapons/etc do.
        for (aug in augments) {
            val augElem = Element("augment")
            SaveUtil.addAttr(augElem, "name", aug.name)
            elem.addContent(augElem)
        }

        // Serialise loose cargo
        for ((index, item) in cargoBlueprints.withIndex()) {
            if (item == null)
                continue

            val cargoElem = Element("cargoBlueprint")
            SaveUtil.addAttr(cargoElem, "name", item.name)
            SaveUtil.addAttrInt(cargoElem, "index", index)
            elem.addContent(cargoElem)
        }

        // Serialise the doors. Most of the time there's nothing interesting
        // about them other than whether they're open or closed, but occasionally
        // they have more information (like the damage they've taken from
        // being attacked).
        val doorsElem = Element("doors")
        val doorStateString = StringBuilder()
        for ((index, door) in doors.withIndex()) {
            val thisDoorElem = door.saveToXML()
            thisDoorElem?.setAttribute("doorIndex", index.toString())
            thisDoorElem?.let { doorsElem.addContent(it) }

            doorStateString.append(if (door.open) 'Y' else 'n')
        }
        val doorStateElem = Element("isOpen")
        doorStateElem.addContent(doorStateString.toString())
        doorsElem.addContent(doorStateElem)
        elem.addContent(doorsElem)

        // Do the same thing for rooms, they won't emit an element unless
        // there's a fire or breach or something like that.
        val roomsElem = Element("rooms")
        for (room in rooms) {
            val thisRoomElem = room.saveToXML()
            thisRoomElem?.setAttribute("id", room.id.toString())
            thisRoomElem?.let { roomsElem.addContent(it) }
        }
        elem.addContent(roomsElem)

        // Serialise the room oxygen levels. Since there's a lot of rooms,
        // don't serialise them all individually.
        val oxygenLevelString = StringBuilder()
        for (room in rooms) {
            oxygenLevelString.append(room.oxygen)
            oxygenLevelString.append(' ')
        }
        val oxygenLevelElem = Element("oxygenLevels")
        oxygenLevelElem.addContent(oxygenLevelString.toString().trim())
        elem.addContent(oxygenLevelElem)

        // Serialise any floating animations, like the animations from a weapon
        // hitting a room or the shields.
        val animationsElem = Element("animations")
        for (animation in animations) {
            val animElem = Element("animation")
            animation.saveToXML(animElem)
            animationsElem.addContent(animElem)
        }
        elem.addContent(animationsElem)

        // Serialise in-flight projectile
        val projectilesElem = Element("projectiles")
        for (projectile in projectiles) {
            // A flying drone is saved as a drone rather than a projectile,
            // though really it's kinda both.
            if (projectile is FlyingDroneProjectile)
                continue

            val projectileElem = Element("projectile")
            SaveUtil.addAttr(projectileElem, "loadType", projectile.serialisationType)
            projectile.saveToXML(projectileElem, refs)
            projectilesElem.addContent(projectileElem)
        }
        elem.addContent(projectilesElem)
    }

    fun loadFromXml(rootElem: Element, refs: RefLoader) {
        SaveUtil.registerObjectId(rootElem, refs, this)

        val resources = ResourceSet(rootElem.getChild("resources"), refs, sys)
        fuelCount = resources.fuel
        missilesCount = resources.missiles
        dronesCount = resources.droneParts
        scrap = resources.scrap

        ftlChargeProgress = SaveUtil.getAttrFloat(rootElem, "ftlProgress")
        purchasedReactorPower = SaveUtil.getAttrInt(rootElem, "reactorPower")
        health = SaveUtil.getAttrInt(rootElem, "health")

        escapeHealth = SaveUtil.getOptionalTagInt(rootElem, "escapeHealth") ?: 0
        surrenderHealth = SaveUtil.getOptionalTagInt(rootElem, "surrenderHealth") ?: 0
        escapeTimer = SaveUtil.getOptionalTagFloat(rootElem, "escapeTimer")
        maxSuperShield = SaveUtil.getOptionalTagInt(rootElem, "maxSuperShield") ?: 5

        // This must be set after maxSuperShield to avoid it being wrongly clamped
        superShield = SaveUtil.getOptionalTagInt(rootElem, "superShield") ?: 0

        SaveUtil.getAttrRef(rootElem, "bossManager", refs, BossManager::class.java) { boss = it }

        // Load the systems
        for (elem in rootElem.getChild("systems").getChildren("system")) {
            // Spawn in the system
            val name: String = elem.getAttributeValue("name")
            val blueprint = sys.blueprintManager[name] as SystemBlueprint

            // Find the slot this system is in.
            // This isn't as simple as it might sound, since for artillery
            // we can have multiple systems installed. Thus we need to use
            // the serialised room ID to make sure we load this into the
            // correct instance of the system.
            val roomId = SaveUtil.getAttrInt(elem, "room")
            val room = rooms[roomId]

            val slot = room.systemSlots.first { it.system == blueprint }
            room.setSystem(slot)

            // Re-load the system's properties
            room.system!!.loadFromXML(elem, refs)
        }

        // Load the crew
        for (crewElem in rootElem.getChild("crew").getChildren("crewMember")) {
            val raceName = crewElem.getAttributeValue("type")
            val race = sys.blueprintManager[raceName] as CrewBlueprint
            val info = LivingCrewInfo.generateRandom(race, sys)
            val crewMember = addCrewMember(info, false)
            crewMember.loadFromXML(crewElem, refs)
        }

        // Deserialise the doors.
        val doorsElem = rootElem.getChild("doors")
        val doorsWithXML = HashSet<Door>()
        for (doorElem in doorsElem.getChildren("door")) {
            val index = SaveUtil.getAttrInt(doorElem, "doorIndex")
            doors[index].loadFromXML(doorElem)
            doorsWithXML.add(doors[index])
        }
        for (door in doors) {
            if (door !in doorsWithXML) {
                door.loadWithoutXML()
            }
        }
        val doorStateString = doorsElem.getChildTextTrim("isOpen")
        for ((index, door) in doors.withIndex()) {
            val open = doorStateString[index] == 'Y'
            door.loadSavedOpen(open)
        }

        // Deserialise rooms, without their oxygen levels which are stored separately below.
        val roomsElem = rootElem.getChild("rooms")
        for (elem in roomsElem.getChildren("room")) {
            val id = elem.getAttributeValue("id").toInt()
            rooms[id].loadFromXML(elem)
        }

        // Deserialise the oxygen levels, which are just numbers for each room.
        val oxygenLevels = rootElem.getChildTextTrim("oxygenLevels").split(' ', '\t')
        for ((index, levelStr) in oxygenLevels.withIndex()) {
            val room = rooms[index]
            room.oxygen = levelStr.toFloat()
        }

        // Deserialise the weapons
        for (weaponElem in rootElem.getChild("weapons").getChildren("weapon")) {
            val hardpointIndex = SaveUtil.getAttrInt(weaponElem, "hardpoint")
            val type = SaveUtil.getAttr(weaponElem, "type")
            val blueprint = sys.blueprintManager[type] as AbstractWeaponBlueprint

            val weapon = blueprint.buildInstance(this)
            weapon.loadFromXML(weaponElem, refs)
            hardpoints[hardpointIndex].weapon = weapon
        }

        // Deserialise the drones, both friendly and not.
        for (droneElem in rootElem.getChild("drones").getChildren("drone")) {
            val type = SaveUtil.getAttr(droneElem, "type")
            val blueprint = sys.blueprintManager[type] as DroneBlueprint

            val drone = blueprint.makeInstance()
            drone.loadFromXML(droneElem, refs, this)

            // The drone will add itself to the externalDrones or crew list.
        }

        // Deserialise the augments.
        for (augElem in rootElem.getChildren("augment")) {
            val name = SaveUtil.getAttr(augElem, "name")
            augments.add(sys.blueprintManager[name] as AugmentBlueprint)
        }

        // Deserialise loose cargo
        for (cargoElem in rootElem.getChildren("cargoBlueprint")) {
            val name = SaveUtil.getAttr(cargoElem, "name")
            val index = SaveUtil.getAttrInt(cargoElem, "index")
            cargoBlueprints[index] = sys.blueprintManager[name] as Blueprint
        }

        // Deserialise explosion (and similar) animations
        for (animElem in rootElem.getChild("animations").getChildren("animation")) {
            animations += FloatingAnimation.loadFromXML(animElem, sys)
        }

        // Deserialise the in-flight projectiles
        for (projectileElem in rootElem.getChild("projectiles").getChildren("projectile")) {
            val serialisationType = SaveUtil.getAttr(projectileElem, "loadType")
            IProjectile.loadFromXML(sys, projectileElem, refs, serialisationType) { projectiles += it }
        }

        updateCrewReservedSlots()
        updateAvailableSystems()
        cargoUpdated()
    }

    fun hasCrewOwnedByShip(otherShip: Ship): Boolean {
        return crew.filterIsInstance(LivingCrew::class.java)
            .any { it.ownerShip == otherShip }
    }

    /**
     * Checks if there's any crew onboard that don't belong to the player.
     *
     * This is needed to check for the enemy crew dead events, as FTL
     * doesn't seem to care where the enemy crew ultimately came from.
     */
    fun hasCrewOwnedByAnyOtherShip(): Boolean {
        return crew.filterIsInstance(LivingCrew::class.java)
            .any { it.ownerShip != this }
    }

    companion object {
        const val MAX_AUGMENTS: Int = 3
    }

    class Hardpoint(val spec: ShipBlueprint.ParsedHardpoint) {
        var weapon: AbstractWeaponInstance? = null
    }

    class FloatingAnimation(game: InGameState, val spec: AnimationSpec, val pos: ConstPoint, val scaling: Float = 1f) {
        private val animation = spec.startSingle(game)

        val isFinished get() = animation.isStopped

        fun render(g: Graphics) {
            val frame = animation.currentFrame

            if (scaling == 1f) {
                frame.draw(pos.x.f, pos.y.f)
                return
            }

            // This is used for flak
            g.pushTransform()
            g.translate(pos.x.f, pos.y.f)
            g.scale(scaling, scaling)
            frame.draw()
            g.popTransform()
        }

        fun update(dt: Float) {
            animation.update(dt)
        }

        fun saveToXML(elem: Element) {
            SaveUtil.addAttr(elem, "name", spec.name)
            SaveUtil.addAttrInt(elem, "x", pos.x)
            SaveUtil.addAttrInt(elem, "y", pos.y)
            SaveUtil.addAttrFloat(elem, "timer", animation.timer)
            SaveUtil.addAttrFloat(elem, "scaling", scaling)
        }

        companion object {
            fun loadFromXML(elem: Element, game: InGameState): FloatingAnimation {
                val spec = when (val name = SaveUtil.getAttr(elem, "name")) {
                    // Fires don't have an animation set in the XML,
                    // so we have to grab the proper animation here.
                    FireInstance.SMOKE_ANIMATION.name -> FireInstance.SMOKE_ANIMATION

                    else -> game.animations[name]
                }

                val x = SaveUtil.getAttrInt(elem, "x")
                val y = SaveUtil.getAttrInt(elem, "y")
                val scaling = SaveUtil.getAttrFloat(elem, "scaling")

                val anim = FloatingAnimation(game, spec, ConstPoint(x, y), scaling)
                anim.animation.timer = SaveUtil.getAttrFloat(elem, "timer")

                return anim
            }
        }
    }

    inner class DamageNumber(var x: Float, var y: Float, val image: Image, val colour: Colour) {
        var timer: Float = 0f

        private val LIFETIME = 1.2f

        fun draw() {
            image.drawAlignedCentred(x.roundToInt(), y.roundToInt(), colour)
        }

        fun update(dt: Float): Boolean {
            timer += dt
            y -= dt / LIFETIME * 10

            return timer < LIFETIME
        }
    }
}

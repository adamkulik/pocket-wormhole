package xyz.znix.xftl.layout

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.Constants.*
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.game.UIUtils
import xyz.znix.xftl.math.*
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.Oxygen
import java.util.*
import kotlin.math.PI
import kotlin.math.max

data class Room(val ship: Ship, val id: Int, val x: Int, val y: Int, val width: Int, val height: Int) {

    init {
        require(x >= 0) { "Negative x room coordinates are not allowed for ship ${ship.name}" }
        require(y >= 0) { "Negative y room coordinates are not allowed for ship ${ship.name}" }
    }

    var system: AbstractSystem? = null
        private set

    /**
     * This specifies what systems can be installed into this room,
     * and how it's computer should be placed.
     *
     * This is set in the ship layout.
     *
     * This is only used for more than one system (in Vanilla) when
     * a ship has both a medbay and a clonebay in the same slot, which they all do.
     */
    var systemSlots = ArrayList<SystemInstallConfiguration>()

    private var _doors: List<Door>? = null
    val doors: List<Door>
        get() {
            return _doors ?: error("Doors are not yet initialised")
        }

    /**
     * A list of all the crew (friendly or intruders, including drones)
     * currently inside this room.
     */
    private val _crew = ArrayList<AbstractCrew>()
    val crew: List<AbstractCrew> get() = _crew

    /**
     * Oxygen level from 1-0.
     */
    var oxygen: Float = 1f
        set(value) {
            field = value.coerceIn(0f..1f)
        }

    /**
     * True if the player can see the contents of the room.
     */
    var playerHasVision: Boolean = false
        private set

    /**
     * Is the oxygen level so low the crew will suffocate?
     */
    val isOxygenCritical: Boolean get() = oxygen < Oxygen.OXYGEN_CRITICAL_LEVEL

    // Offset of this room from the ship's 0,0 screen position
    val offsetX get() = ROOM_SIZE * (x + ship.offset.x)
    val offsetY get() = ROOM_SIZE * (y + ship.offset.y)

    val pixelCentre: ConstPoint by lazy { ConstPoint(offsetX + pixelWidth / 2, offsetY + pixelHeight / 2) }

    val position = ConstPoint(x, y)

    val pixelWidth = width * ROOM_SIZE
    val pixelHeight = height * ROOM_SIZE

    val cellCount = width * height

    val fires: Array<FireInstance?> = Array(cellCount) { null }
    val breaches: Array<BreachInstance?> = Array(cellCount) { null }
    private val fireSpreadTimers: Array<Float?> = Array(cellCount) { null }
    private val fireSpreadUpdated: Array<Boolean> = Array(cellCount) { false }

    private val reservedPlayerSlots: Array<AbstractCrew?> = Array(cellCount) { null }
    private val reservedEnemySlots: Array<AbstractCrew?> = Array(cellCount) { null }

    val obstructions = HashSet<ConstPoint>()
    private val obstructionSlots = HashSet<Int>()

    private var computerHackAnimation: FTLAnimation? = null
    private var bigSparksHackAnimation: FTLAnimation? = null
    private var bigSparksRotation: Float = 0f
    private var bigSparksMaskX: Int = 0

    // For flashing the light at the bottom of a room that comes on
    // when a fire/breach is present.
    private var fireLightTimer: Float = 0f

    fun initialise(doors: List<Door>) {
        check(_doors == null) { "Cannot reinitialise room" }

        _doors = doors
    }

    fun update(dt: Float) {
        // Always update who's in the room before updating the system.
        // This is to make deserialisation easier - some things (eg repair
        // progress or queued teleports) are cancelled if there's no one
        // in the room, so be sure this is always up-to-date.
        updateCrewInRoom()

        system?.update(dt)

        // Check whether the player can see inside this room
        playerHasVision = crew.any { it.providesPlayerVision }

        val playerSensors = ship.sys.player?.sensors
        if (playerSensors != null && playerSensors.providesEnemyVision) {
            // View the enemy ship via L2 sensors
            playerHasVision = true
        }
        if (playerSensors != null && playerSensors == ship.sensors && playerSensors.providesPlayerVision) {
            // View the player's own ship via L1 sensors
            playerHasVision = true
        }

        if (ship.sys.debugFlags.showEverything.set) {
            playerHasVision = true
        }

        // This is used by bombs
        if (ship.projectiles.any { it.providesPlayerVision(this) }) {
            playerHasVision = true
        }

        // A powered hacking probe provides vision
        val hacking = system?.hackedBy
        if (hacking != null && hacking.room!!.ship.isPlayerShip && hacking.isPoweredUp) {
            playerHasVision = true
        }

        // Ships loose 1.2% oxygen per second, but gain whatever the oxygen
        // system is providing.
        val refillRate = (ship.oxygen?.refillRate ?: 0f) - Oxygen.ROOM_DRAIN_RATE
        oxygen += refillRate * dt

        // Note that transferring oxygen through open doors is handled
        // in the Door update function.

        computerHackAnimation?.update(dt)
        bigSparksHackAnimation?.update(dt)

        for (idx in fires.indices) {
            fires[idx]?.update(dt)

            // Clear the spread progress if the tile is already
            // on fire, or it wasn't updated in the last update.
            if (!fireSpreadUpdated[idx] || fires[idx] != null) {
                fireSpreadTimers[idx] = null
            }
            fireSpreadUpdated[idx] = false
        }

        // Flash the fire warning light
        fireLightTimer += dt
    }

    fun updateCrewInRoom() {
        // Update the crew standing in this room
        _crew.clear()
        for (crew in ship.crew) {
            if (crew.room == this)
                _crew.add(crew)
        }
    }

    fun render(g: Graphics, selected: Boolean, alpha: Float) {
        val x = offsetX
        val y = offsetY

        drawFloor(g, alpha)

        renderSystemStuff(g)

        // Breaches are drawn on top of the room image
        if (playerHasVision) {
            for (breach in breaches) {
                breach?.draw()
            }
        }

        // Draw the pathing-to boxes, if required
        reservedPlayerSlots.forEachIndexed draw@{ i, crew ->
            if (crew == null)
                return@draw

            val slot = slotToPoint(i)

            // If the crewmember is in their assigned position, don't draw the box
            if (crew.standingPosition?.posEq(slot) == true && crew.room == this)
                return@draw

            val point = Point(slot)
            point *= ROOM_SIZE
            point.x += offsetX
            point.y += offsetY

            ship.sys.getImg("img/people/green_destination.png").draw(point.x.f, point.y.f)
        }

        // Draw any fires
        if (playerHasVision) {
            for (fire in fires) {
                fire?.draw()
            }
        }

        // Draw the flashing fire warning light, as required.
        if (playerHasVision) {
            drawFireLight(g)
        }

        // Draw the highlight on the room if it's being selected for weapon targeting.
        if (selected) {
            for (i in 2..5) {
                g.colour = when {
                    i < 5 -> ROOM_BORDER_COLOUR_SELECTED
                    else -> ROOM_BORDER_COLOUR_SELECTED_INNER
                }

                g.drawRect(
                    (x + i).f, (y + i).f,
                    (pixelWidth - 1 - i * 2).f,
                    (pixelHeight - 1 - i * 2).f
                )
            }
        }

        // Draw the walls, leaving gaps for the doors.
        // Note that when drawing right-hand or bottom
        // walls, we need to subtract two from the x/y
        // in order to keep the lines internal to the room.
        g.colour = Colour(ROOM_BORDER_COLOUR).apply { a = alpha }
        g.lineWidth = 2f
        for (cellX in 0 until width) {
            drawWall(g, x, y, cellX, 0, Direction.UP)
            drawWall(g, x, y - 2, cellX, height - 1, Direction.DOWN)
        }
        for (cellY in 0 until height) {
            drawWall(g, x, y, 0, cellY, Direction.LEFT)
            drawWall(g, x - 2, y, width - 1, cellY, Direction.RIGHT)
        }
        g.lineWidth = 1f

        drawDebugRoomNumber()
        drawDebugFires(g)
    }

    private fun drawFloor(g: Graphics, alpha: Float) {
        val x = offsetX
        val y = offsetY

        if (!playerHasVision) {
            g.colour = Colour(FLOOR_COLOUR_NO_VISION).apply { a = alpha }
            g.fillRect(
                x.f,
                y.f,
                pixelWidth.f,
                pixelHeight.f
            )
            return
        }

        g.colour = Colour(FLOOR_COLOUR_NO_OXYGEN.lerp(FLOOR_COLOUR, oxygen)).apply { a = alpha }
        g.fillRect(
            x.f,
            y.f,
            pixelWidth.f,
            pixelHeight.f
        )

        if (isOxygenCritical) {
            val img = ship.sys.getImg("img/effects/low_o2_stripes_${width}x${height}.png")
            img.draw(x.f, y.f, 0.5f)
        }

        g.colour = FLOOR_GRID_COLOUR
        for (i in 1 until width) {
            g.drawLine(
                (x + i * ROOM_SIZE - 1).f,
                y.f,
                (x + i * ROOM_SIZE - 1).f,
                (y + pixelHeight - 1).f
            )
        }

        for (i in 1 until height) {
            g.drawLine(
                x.f,
                (y + ROOM_SIZE * i - 1).f,
                (x + pixelWidth - 1).f,
                (y + ROOM_SIZE * i - 1).f
            )
        }
    }

    private fun renderSystemStuff(g: Graphics) {
        val system = system ?: return
        val config = system.configuration

        // Draw only the system icon for non-visible rooms
        if (!playerHasVision) {
            system.drawRoom(g)
            return
        }

        val x = offsetX
        val y = offsetY

        if (config.spec.interiorImage != null) {
            // Render the interior decals
            val bg = ship.sys.getImg(config.spec.interiorImage)
            bg.draw(x, y, 0.75f)
        } else if (config.computerPoint != null) {
            // AI ships rarely (never?) use proper room textures. For systems like
            // engines and piloting that can be manned, draw a standard computer image
            // on instead.
            val comp = ship.sys.getImg("img/ship/interior/computer1.png")
            val imgX = x + config.computerPoint.x * ROOM_SIZE
            val imgY = y + config.computerPoint.y * ROOM_SIZE
            g.pushTransform()
            g.rotate(imgX.f + ROOM_SIZE / 2, imgY.f + ROOM_SIZE / 2, config.computerDirection!!.angle.f)
            comp.draw(imgX, imgY)
            g.popTransform()
        }

        // Draw the system icon
        system.drawRoom(g)

        // Render the hacking sparks - both those on the console (if one is present),
        // and the big ones on the floor while the hacking is active.
        if (config.computerPoint != null) {
            // If we have a not-disabled hacking system, show the
            // hacking effect on the computer. This runs constantly,
            // unless the hacking system is powered down.
            if (system.hackedBy?.isPoweredUp == true) {
                if (computerHackAnimation == null)
                    computerHackAnimation = ship.sys.animations["hacked_console"].startLooping(ship.sys)

                // This can't be merged with drawing the computer above, as
                // some decals have computers drawn into them.
                val imgX = x.f + config.computerPoint.x * ROOM_SIZE
                val imgY = y.f + config.computerPoint.y * ROOM_SIZE
                g.pushTransform()
                g.rotate(imgX + ROOM_SIZE / 2, imgY + ROOM_SIZE / 2, config.computerDirection!!.angle.f)
                computerHackAnimation!!.draw(imgX, imgY)
                g.popTransform()
            } else {
                computerHackAnimation = null
            }
        }

        if (system.isHackActive) {
            if (bigSparksHackAnimation == null || bigSparksHackAnimation?.isStopped == true) {
                bigSparksHackAnimation = ship.sys.animations["stun_spark_big"].startSingle(ship.sys)

                // See doc/hacking for details about this
                if (width == 1 || height == 1) {
                    bigSparksMaskX = VisualRandom.nextInt(ROOM_SIZE)
                    bigSparksRotation = 0f
                } else {
                    bigSparksMaskX = -1
                    bigSparksRotation = PI.toFloat() / 2f * VisualRandom.nextInt(4)
                }
            }

            g.pushTransform()
            g.translate(x.f, y.f)
            if (height == 1) {
                // Horizontal room, thus we need to rotate the vertical slicee
                // of the image into place.
                g.rotate(ROOM_SIZE / 2f, ROOM_SIZE / 2f, -90f)
            }
            g.rotate(pixelWidth / 2f, pixelHeight / 2f, Math.toDegrees(bigSparksRotation.toDouble()).toFloat())

            if (bigSparksMaskX == -1) {
                bigSparksHackAnimation!!.draw(0f, 0f)
            } else {
                bigSparksHackAnimation!!.currentFrame.draw(
                    0f, 0f, 35f, 70f,
                    bigSparksMaskX.f, 0f, bigSparksMaskX + 35f, 70f
                )
            }

            g.popTransform()
        } else {
            bigSparksHackAnimation = null
        }
    }

    private fun drawFireLight(g: Graphics) {
        if (fires.all { it == null } && breaches.all { it == null }) {
            fireLightTimer = 0f
            return
        }

        val baseImg = ship.sys.getImg("img/effects/light_base.png")
        val glowImg = ship.sys.getImg("img/effects/light_glow.png")

        // Goes on the centre of the bottom of the room
        val bottomCentreX = offsetX + pixelWidth / 2
        val bottomCentreY = offsetY + pixelHeight

        val imgX = bottomCentreX - baseImg.width / 2
        val imgY = bottomCentreY - baseImg.height - 2

        baseImg.draw(imgX, imgY)

        // The glow flashes at 1Hz
        if (fireLightTimer > 1f) {
            fireLightTimer -= 1f
        }
        val alpha = when {
            fireLightTimer < 0.5f -> fireLightTimer * 2f
            else -> 1f - (fireLightTimer - 0.5f) * 2f
        }
        glowImg.draw(imgX, imgY, alpha)
    }

    private fun drawWall(g: Graphics, baseX: Int, baseY: Int, x: Int, y: Int, side: Direction) {
        // When we're drawing horizontal surfaces (the top and bottom
        // of a room) we get a vertical (up/down respectively) direction.
        // Thus we need to flip the isVertical flag.
        val vertical = !side.isVertical

        val hasDoor = doors.any {
            if (it.dirFor(this) != side)
                return@any false

            if (it.left == this && it.leftPos!!.posEq(x, y))
                return@any true

            if (it.right == this && it.rightPos!!.posEq(x, y))
                return@any true

            return@any false
        }

        // The .5 offset is required since we're drawing a two-pixel-thick line
        val inWorldX = baseX + (x + max(0, side.x)) * ROOM_SIZE + 0.5f
        val inWorldY = baseY + (y + max(0, side.y)) * ROOM_SIZE + 0.5f

        // The end position of the line, relative to the start
        var lineX = 0f
        var lineY = 0f

        // Use -2 to avoid overshooting, given we already added
        // a 0.5 offset.
        if (vertical) {
            lineY += ROOM_SIZE - 2
        } else {
            lineX += ROOM_SIZE - 2
        }

        if (hasDoor) {
            val doorEndFraction = 0.2f

            val doorStartX = inWorldX + lineX * doorEndFraction
            val doorStartY = inWorldY + lineY * doorEndFraction

            val doorEndX = inWorldX + lineX * (1 - doorEndFraction)
            val doorEndY = inWorldY + lineY * (1 - doorEndFraction)

            g.drawLine(
                inWorldX, inWorldY,
                doorStartX, doorStartY
            )

            g.drawLine(
                doorEndX, doorEndY,
                inWorldX + lineX, inWorldY + lineY
            )
        } else {
            g.drawLine(
                inWorldX, inWorldY,
                inWorldX + lineX, inWorldY + lineY
            )
        }
    }

    private fun drawDebugRoomNumber() {
        if (!ship.sys.debugFlags.showRoomNumbers.set)
            return

        ship.sys.getFont("JustinFont8").drawString(
            offsetX + 4f, offsetY + 12f,
            id.toString(),
            Colour.blue
        )
    }

    private fun drawDebugFires(g: Graphics) {
        if (!ship.sys.debugFlags.showFireTimers.set)
            return

        for (slot in fires.indices) {
            fires[slot]?.drawDebug(g)

            // Draw the fire spread timer.
            val spread = fireSpreadTimers[slot] ?: continue

            val pos = RoomPoint(this, slotToPoint(slot))
            val x = pos.offsetX
            val y = pos.offsetY

            // Use 50 as the max, not the random initial starting
            // value since we'd also have to store that.
            val progress = 1 - spread / 50f
            val fillColour = Colour(200, 200, 0) // Dark yellow
            UIUtils.drawDebugBar(g, x + 5, y + 5, 5, 20, progress, Colour.black, fillColour)
        }
    }

    fun setSystem(config: SystemInstallConfiguration) {
        system = config.system.createInstance()

        system?.configuration = config
        system?.energyLevels = config.spec.startingPower
        system?.room = this
        system?.initialise(ship)

        obstructions.clear()
        obstructionSlots.clear()
        if (config.obstructionPoint != null) {
            obstructions.add(config.obstructionPoint)
            obstructionSlots.add(pointToSlot(config.obstructionPoint))
        }

        ship.updateAvailableSystems()
    }

    // Check if a point (relative to this room's origin) is inside this room
    fun containsRelative(target: IPoint): Boolean {
        if (target.x < 0 || target.y < 0)
            return false

        if (target.x >= width || target.y >= height)
            return false

        return true
    }

    // Check if a point (relative to the ship's origin) is inside this room
    fun containsAbsolute(target: IPoint): Boolean {
        if (target.x < x || target.y < y)
            return false

        if (target.x >= x + width || target.y >= y + height)
            return false

        return true
    }

    /**
     * Check if a point (relative to the ship's origin) is inside this room.
     *
     * Unlike [containsAbsolute], this works in pixels rather than cells.
     */
    fun containsShipSpace(target: IPoint): Boolean {
        if (target.x !in offsetX until offsetX + pixelWidth)
            return false

        return target.y in offsetY until offsetY + pixelHeight
    }


    fun slotToPoint(slot: Int): IPoint {
        if (slot !in 0 until cellCount)
            throw ArrayIndexOutOfBoundsException("Invalid slot $slot for $width*$height room - range is 0 to ${width * height}")

        return ConstPoint(slot % width, slot / width)
    }

    fun pointToSlot(point: IPoint): Int {
        check(containsRelative(point))

        return point.x + point.y * width
    }

    /**
     * Check if a given cell in a room is not currently occupied by another
     * crewmember, whether they're standing there or walking towards there.
     *
     * This also checks if there's an obstruction (eg, the medbay or clonebay stuff)
     * in the given slot.
     *
     * If the [allow] argument is non-null, the slot is still considered free
     * if that crewmember is occupying the slot. This is intended for crew to check
     * if someone else is in the slot.
     */
    fun isSlotFree(point: IPoint, type: AbstractCrew.SlotType, allow: AbstractCrew? = null): Boolean {
        // Skip obstructed cells - eg, healer in the medbay
        if (obstructions.contains(point))
            return false

        val slots = slotsFor(type)
        val occupier = slots[pointToSlot(point)]
        return occupier == null || occupier == allow
    }

    /**
     * Update all of this rooms crew and intruder slots.
     *
     * If two crew are set to the same location, one of them (the one
     * not put in the slot) will be added to the [conflicts] list.
     */
    fun updateCrewReservedSlots(conflicts: ArrayList<AbstractCrew>) {
        // Clear out all the pathfinding slots, and fill them back in.
        // This is much better than manually updating the slots, since
        // they can't get out-of-sync with the current crew.
        Arrays.fill(reservedPlayerSlots, null)
        Arrays.fill(reservedEnemySlots, null)

        // Update all the crew and intruder slots. Note we can't
        // use ship.friendlyCrew or ship.intruders, as they might
        // not have updated yet (eg four crew teleporting to
        // a two-peron room; we need to update the slots between
        // each crew reserving a slot).
        for (crew in ship.crew) {
            addCrewReservedSlot(conflicts, crew.mode, crew)
        }
    }

    private fun addCrewReservedSlot(
        conflicts: ArrayList<AbstractCrew>,
        type: AbstractCrew.SlotType,
        crew: AbstractCrew
    ) {
        val slots = slotsFor(type)

        // Crew standing in one of the slots
        val crewPos = crew.standingPosition
        if (crewPos != null && crewPos.room == this) {
            val slot = pointToSlot(crewPos)

            if (slots[slot] == null) {
                slots[slot] = crew
            } else {
                conflicts.add(crew)
            }

            // This shouldn't be required, as standingPosition should
            // be null if pathingTarget isn't, but leave it here just in case.
            return
        }

        // Crew walking towards one of the slots
        val target = crew.pathingTarget
        if (target != null && target.room == this) {
            val slot = pointToSlot(target)

            if (slots[slot] == null) {
                slots[slot] = crew
            } else {
                conflicts.add(crew)
            }
        }
    }

    /**
     * Find the first free slot in this room that another crewmember
     * isn't already standing on or pathing to.
     *
     * This defines the order in which crew are placed into a room,
     * and using this ensures crew are always sent to the computer
     * position first.
     */
    fun firstFreeSlot(type: AbstractCrew.SlotType): IPoint? {
        system?.configuration?.computerPoint?.let { computer ->
            if (isSlotFree(computer, type) && type == AbstractCrew.SlotType.CREW) {
                return computer
            }
        }

        val slots = slotsFor(type)

        for (i in slots.indices) {
            val point = slotToPoint(i)
            if (isSlotFree(point, type))
                return point
        }

        return null
    }

    /**
     * Check if this room has any free slots.
     *
     * If [crew] is specified, it's checked from their perspective - if they
     * already have a slot, then this returns true even if all the slots
     * are full.
     */
    fun anySlotsFree(type: AbstractCrew.SlotType, crew: AbstractCrew? = null): Boolean {
        val slots = slotsFor(type)

        for ((index, slotCrew) in slots.withIndex()) {
            if (obstructionSlots.contains(index))
                continue

            if (slotCrew == crew || slotCrew == null)
                return true
        }

        return false
    }

    /**
     * Returns true if the specified crewmember is assigned to walk to a slot in this room.
     */
    fun isCrewAssigned(crew: AbstractCrew): Boolean {
        return reservedPlayerSlots.contains(crew) || reservedEnemySlots.contains(crew)
    }

    private fun slotsFor(type: AbstractCrew.SlotType): Array<AbstractCrew?> = when (type) {
        AbstractCrew.SlotType.CREW -> reservedPlayerSlots
        AbstractCrew.SlotType.INTRUDER -> reservedEnemySlots
    }

    fun spawnFire() {
        // Pick a random slot, and spawn a fire in it (or fix the existing fire's health).
        // This seems to be why fire bombs sometimes only spawn one fire - they're both
        // spawned on the same tile in a 1/4 chance.
        val slot = fires.indices.random()

        spawnFireAt(slot)
    }

    fun spawnFireAt(slot: Int) {
        val existingFire = fires[slot]
        if (existingFire != null) {
            existingFire.health = 1f
        } else {
            fires[slot] = FireInstance(this, slot)
        }
    }

    fun spawnBreach() {
        spawnBreachAt(breaches.indices.random())
    }

    fun spawnBreachAt(slot: Int) {
        // Same mechanics as spawning a fire.

        val existingBreach = breaches[slot]
        if (existingBreach != null) {
            existingBreach.health = 1f
        } else {
            breaches[slot] = BreachInstance(this, slot)
        }

        // The breach and air loss sounds are effectively combined
        ship.breachSound.play()

        // It seems there's some buggy-ness with vanilla not playing this
        // if the same room is breached repeatedly?
        // Note that any minimal amount of oxygen is enough to enable this
        // sound, even for nearly-drained rooms. This matches vanilla.
        if (oxygen > 0f) {
            ship.airLossSound.play()
        }
    }

    /**
     * Called by [FireInstance] to spread itself to adjacent tiles.
     *
     * If there's a door in the way, [door] is set.
     */
    fun spreadFire(dt: Float, slot: Int, door: Door?) {
        fireSpreadUpdated[slot] = true

        val speed = when {
            door == null || door.open || door.isHacked -> 1.6f
            door.level <= 1 -> 32f / 35f
            else -> 0.16f // Blast doors
        }

        var current = fireSpreadTimers[slot]
        if (current == null) {
            current = (10 until 50).random().f
        }

        current -= speed * dt

        if (current <= 0) {
            fires[slot] = FireInstance(this, slot)
            return
        }

        fireSpreadTimers[slot] = current
    }

    fun showDamageText(imageName: String, colour: Colour, pos: IPoint? = null) {
        ship.showDamageTextAt(pos ?: pixelCentre, imageName, colour)
    }

    fun showDamageText(damage: Int, colour: Colour, pos: IPoint? = null) {
        ship.showDamageTextAt(pos ?: pixelCentre, damage, colour)
    }

    /**
     * Serialise the door, or return null if there's nothing to save.
     */
    fun saveToXML(): Element? {
        // The oxygen level is handled separately.

        if (fires.all { it == null } && fireSpreadTimers.all { it == null } && breaches.all { it == null }) {
            return null
        }

        val elem = Element("room")

        for (slot in fires.indices) {
            val fire = fires[slot]
            val spread = fireSpreadTimers[slot]

            if (fire != null) {
                val fireElem = Element("fire")
                SaveUtil.addAttrInt(fireElem, "slot", slot)
                fire.saveToXML(fireElem)
                elem.addContent(fireElem)
            }

            if (spread != null) {
                val spreadElem = Element("fireSpread")
                SaveUtil.addAttrInt(spreadElem, "slot", slot)
                SaveUtil.addAttrFloat(spreadElem, "timer", spread)

                // fireSpreadUpdated won't be set unless spread also is, so we can
                // safely only set this here.
                SaveUtil.addAttrBool(spreadElem, "updated", fireSpreadUpdated[slot])

                elem.addContent(spreadElem)
            }
        }

        for ((slot, breach) in breaches.withIndex()) {
            if (breach == null)
                continue

            val breachElem = Element("breach")
            SaveUtil.addAttrInt(breachElem, "slot", slot)
            breach.saveToXML(breachElem)
            elem.addContent(breachElem)
        }

        return elem
    }

    fun loadFromXML(elem: Element) {
        for (fireElem in elem.getChildren("fire")) {
            val slot = SaveUtil.getAttrInt(fireElem, "slot")
            val fire = FireInstance(this, slot)
            fire.loadFromXML(fireElem)
            fires[slot] = fire
        }

        for (spreadElem in elem.getChildren("fireSpread")) {
            val slot = SaveUtil.getAttrInt(spreadElem, "slot")
            fireSpreadTimers[slot] = SaveUtil.getAttrFloat(spreadElem, "timer")
            fireSpreadUpdated[slot] = SaveUtil.getAttrBool(spreadElem, "updated")
        }

        for (breachElem in elem.getChildren("breach")) {
            val slot = SaveUtil.getAttrInt(breachElem, "slot")
            val breach = BreachInstance(this, slot)
            breach.loadFromXML(breachElem)
            breaches[slot] = breach
        }
    }

    /**
     * True if this room has a door to the given other room.
     */
    fun connectedTo(room: Room): Boolean {
        return doors.any { it.other(this) == room }
    }
}

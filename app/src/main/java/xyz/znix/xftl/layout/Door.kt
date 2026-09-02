package xyz.znix.xftl.layout

import org.jdom2.Element
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Constants.ROOM_SIZE
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.RoomPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.abs
import kotlin.math.max

data class Door(val position: ConstPoint, val left: Room?, val right: Room?, val isVertical: Boolean) {
    init {
        check(left != null || right != null) { "Cannot have a door detached from any room" }
    }

    val ship: Ship = left?.ship ?: right!!.ship

    // Offset of this room from the ship's 0,0 screen position
    val offsetX get() = ROOM_SIZE * (position.x + ship.offset.x)
    val offsetY get() = ROOM_SIZE * (position.y + ship.offset.y)

    val pixelCentre: ConstPoint by lazy {
        if (isVertical) {
            ConstPoint(offsetX, offsetY + ROOM_SIZE / 2)
        } else {
            ConstPoint(offsetX + ROOM_SIZE / 2, offsetY)
        }
    }

    val leftPos: RoomPoint? = if (left == null) null else RoomPoint(left, position - left.position + offsetFor(left))
    val rightPos: RoomPoint? =
        if (right == null) null else RoomPoint(right, position - right.position + offsetFor(right))

    val isAirlock: Boolean get() = left == null || right == null

    val maxHealth: Int
        get() {
            val healths = BASE_HEALTHS.getValue(ship.sys.difficulty)

            if (isHacked) {
                return healths[2] // Level 3 equivalent
            }

            val baseLevel = ship.doorsSystem?.effectivePower ?: 0
            if (baseLevel < 2) {
                // Low-level doors have four health for whatever reason,
                // but don't actually stop boarders.
                return 4
            }

            return healths[baseLevel - 2]
        }

    /**
     * True if this door is considered to be open for the purposes
     * of letting air and/or intruders through.
     *
     * The door will animate open when a friendly crewmember is walking
     * through, but that won't let intruders or air through.
     */
    var open: Boolean = false
        set(value) {
            if (isBroken) {
                field = true
            } else {
                field = value
            }
        }

    /**
     * The amount of damage this door has taken. Once this is equal to
     * [maxHealth], the door is stuck open for a few seconds.
     *
     * Storing damage is better than storing health, because if the doors
     * are upgraded or damaged then the change applies to the doors
     * immediately, rather than when the door health is next reset.
     */
    var damage: Int = 0
        private set

    // When a door is broken by intruders, it's locked open for a few seconds.
    // This tracks how much longer that is.
    private var brokenOpenTimer: Float? = null

    val isBroken: Boolean get() = brokenOpenTimer != null

    // True if the player is hovering over this door
    private var hovered: Boolean = false

    // Set during the update cycle by crew walking through this door.
    // This is how we know if a door should appear to open for
    // a crewmember or not.
    private var crewOpenDemand: Boolean = false

    // How open this door is. This is updated to produce the animation.
    private var stateAnimation: Float = 0f

    /**
     * True if this door is controlled by a hacking system, either by hacking
     * one of the adjacent rooms or by actively hacking the door system.
     */
    var isHacked = false
        private set

    /**
     * The level of this door.
     */
    val level: Int
        get() {
            when {
                isHacked -> return 5
                else -> return ship.doorsSystem?.effectivePower ?: 0
            }
        }

    /**
     * The other room this door connects to
     */
    fun other(room: Room): Room? {
        return when (room) {
            left -> right
            right -> left
            else -> throw IllegalArgumentException("Supplied room '$room' is not part of the door")
        }
    }

    /**
     * The position (relative to the given room) for one side of the door
     */
    fun roomPos(room: Room): RoomPoint {
        return when (room) {
            left -> leftPos!!
            right -> rightPos!!
            else -> throw IllegalArgumentException("Supplied room '$room' is not part of the door")
        }
    }

    /**
     * The position of the other side of the door, relative to the other room
     *
     * This is essentially roomPos(other(room)) but with null handling
     */
    fun otherPos(room: Room): IPoint? {
        return roomPos(other(room) ?: return null)
    }

    private fun offsetFor(room: Room): ConstPoint {
        return if (isVertical)
            if (room.x == position.x) ConstPoint.ZERO else ConstPoint(-1, 0)
        else
            if (room.y == position.y) ConstPoint.ZERO else ConstPoint(0, -1)
    }

    /**
     * Find the direction you would have to walk to go through this door, if standing inside [room].
     */
    fun dirFor(room: Room): Direction {
        return if (isVertical)
            if (room.x == position.x) Direction.LEFT else Direction.RIGHT
        else
            if (room.y == position.y) Direction.UP else Direction.DOWN
    }

    /**
     * Called by crewmembers walking through this door to request that it opens visually.
     */
    fun crewRequestOpen(crew: AbstractCrew) {
        // We're obviously not going to open the door for boarders
        if (isLockedFor(crew))
            return

        crewOpenDemand = true
    }

    fun update(dt: Float) {
        // If a crewmember is walking through this door, they'll
        // set crewOpenDemand every update.
        crewOpenDemand = false

        // If this is an airlock, instantly drain the air from this room.
        // Air transfer between non-airlock doors is handled by OxygenTransfer
        if (open && (left == null || right == null)) {
            // If the door has only just opened, and we have more than 10% oxygen
            // (a number pulled from vanilla) then play the whoosh sound.
            val maxOxygen = max(left?.oxygen ?: 0f, right?.oxygen ?: 0f)
            if (maxOxygen > 0.1f) {
                ship.airLossSound.play()
            }

            left?.oxygen = 0f
            right?.oxygen = 0f
        }

        // Run the timer for doors that were broken open.
        if (brokenOpenTimer != null) {
            val newTime = brokenOpenTimer!! - dt
            if (newTime <= 0f) {
                brokenOpenTimer = null
            } else {
                brokenOpenTimer = newTime
            }
        } else if (isHacked) {
            // Hacked doors automatically shut themselves
            open = false
        }

        // The door counts as hacked if the system on either side of it is hacked
        // and the hacking system is at least powered on, or (for any door) if
        // the doors system is hacked with a hacking pulse.
        isHacked = ship.doorsSystem?.isHackActive == true ||
                left?.system?.hackedBy?.isPoweredUp == true ||
                right?.system?.hackedBy?.isPoweredUp == true
    }

    fun updateMouseHover(x: Int, y: Int) {
        // Broken and hacked doors can't be controlled by the player.
        if (isBroken || isHacked) {
            hovered = false
            return
        }

        var centreX = offsetX
        var centreY = offsetY

        if (isVertical) {
            centreY += ROOM_SIZE / 2
        } else {
            centreX += ROOM_SIZE / 2
        }

        hovered = false

        val hoverRange = 10

        if (x !in centreX - hoverRange..centreX + hoverRange)
            return
        if (y !in centreY - hoverRange..centreY + hoverRange)
            return

        hovered = true
    }

    fun click(x: Int, y: Int): Boolean {
        // Update our hovered status, since we use that
        // to determine if we're being clicked.
        updateMouseHover(x, y)

        if (!hovered) {
            return false
        }

        open = !open

        return true
    }

    fun render(g: Graphics, alpha: Float) {
        val animationDir = when {
            open || crewOpenDemand -> 1
            else -> -1
        }
        stateAnimation = (stateAnimation + animationDir * ship.sys.renderingDeltaTime / ANIMATION_TIME).coerceIn(0f..1f)

        val doorSheet = ship.sys.getImg("img/effects/door_sheet.png")
        val highlight = ship.sys.getImg("img/effects/door_highlight.png")

        // Broken and level 1 doors use the same sprite, but broken doors
        // have a colour filter applied to them.
        val sheetY = ROOM_SIZE * (level - 1).coerceAtLeast(0)

        // This is used to animate the door opening and closing, selecting the
        // correct frame for its motion.
        val sheetX = ROOM_SIZE * (stateAnimation * 4).toInt()

        val filter = if (level == 0) Constants.DOOR_BROKEN_FILTER else Colour.white

        // Note that isVertical refers to whether the door is vertical
        // or not, NOT whether the two rooms it joins are placed one
        // above the other.
        g.pushTransform()
        if (isVertical) {
            g.translate(offsetX.f - ROOM_SIZE / 2, offsetY.f)
        } else {
            g.translate(offsetX.f + ROOM_SIZE, offsetY.f - ROOM_SIZE / 2)
            g.rotate(0f, 0f, 90f)
        }

        // Draw the mouse hover highlight
        if (hovered) {
            val highlightFilter = Colour(1f, 1f, 1f, 0.75f)
            highlight.draw(0f, 0f, highlightFilter)
        }

        doorSheet.draw(
            0f, 0f, ROOM_SIZE.f, ROOM_SIZE.f,

            sheetX.f, sheetY.f,
            sheetX.f + ROOM_SIZE, sheetY.f + ROOM_SIZE,
            alpha, filter
        )

        g.popTransform()
    }

    fun attackDoor() {
        damage++

        // When we're broken, start a timer that keeps the doors
        // open until it's elapsed.
        if (damage >= maxHealth) {
            open = true
            damage = 0
            brokenOpenTimer = 7f
        }
    }

    fun resetHealth() {
        damage = 0
    }

    fun saveToXML(): Element? {
        // Currently we have nothing to serialise. Returning null means there
        // isn't a blank element in the savefile.
        // Note that whether the door is open or closed is serialised separately.

        if (damage == 0 && brokenOpenTimer == null) {
            return null
        }

        val elem = Element("door")

        SaveUtil.addAttrInt(elem, "damage", damage)
        SaveUtil.addAttrFloat(elem, "brokenOpen", brokenOpenTimer)

        return elem
    }

    fun loadFromXML(elem: Element) {
        damage = SaveUtil.getAttrInt(elem, "damage")
        brokenOpenTimer = SaveUtil.getAttrFloatOrNull(elem, "brokenOpen")
    }

    // Called if we returned null from saveToXML.
    fun loadWithoutXML() {
        damage = 0
        brokenOpenTimer = null
    }

    fun loadSavedOpen(open: Boolean) {
        // Called when this door's open/closed state is loaded.
        this.open = open

        // Skip the animation when the door is first opened.
        // Note the crew gets a few updates before this is called, so crew
        // requests will be handled properly.
        val visualOpen = open || crewOpenDemand
        stateAnimation = if (visualOpen) 1f else 0f
    }

    /**
     * Check if a player is walking through this door, based on their
     * top-left position.
     */
    fun checkPlayerPos(room: Room, pos: IPoint): Boolean {
        val x = pos.x
        val y = pos.y

        // We define the doorway as being the 35-pixel-wide box centred
        // on the door. This means we can walk with the edge of our sprite's
        // frame overhanging the room, but it shouldn't be an issue since
        // the sprites certainly aren't drawn all the way up to their edges.

        // Find the position of the cell we neighbour in the player's room.
        val posInRoom = roomPos(room)
        val ourX = posInRoom.offsetX
        val ourY = posInRoom.offsetY

        // The player must be reasonably well aligned, noting that the players
        // are displaced upto eight pixels when shooting at the door.
        if (isVertical && abs(y - ourY) > 8) {
            return false
        }
        if (!isVertical && abs(x - ourX) > 8) {
            return false
        }

        // Check if the player isn't within the rooms bounds, which implies
        // they're walking through the doorway.
        val pxInRoomX = x - room.offsetX
        val pxInRoomY = y - room.offsetY

        return when (isVertical) {
            true -> pxInRoomX !in 0..room.pixelWidth - ROOM_SIZE
            false -> pxInRoomY !in 0..room.pixelHeight - ROOM_SIZE
        }
    }

    /**
     * Check if a given position matches either of the cells we connect.
     */
    fun hasRoomPos(point: RoomPoint): Boolean {
        return point == leftPos || point == rightPos
    }

    /**
     * Check if the given crewmember is prevented from walking through the
     * door because it's locked closed.
     */
    fun isLockedFor(crew: AbstractCrew): Boolean {
        if (open) {
            return false
        }

        if (isHacked) {
            return crew.mode == AbstractCrew.SlotType.CREW
        }

        // If there isn't a doors system, the door isn't locked.
        val baseLevel = ship.doorsSystem?.effectivePower ?: 0
        if (baseLevel < 2) {
            return false
        }

        return crew.mode == AbstractCrew.SlotType.INTRUDER
    }

    companion object {
        private const val ANIMATION_TIME: Float = 0.2f

        // Door healths depend on the difficulty.
        // These values are the number of attacks required to break
        // a level 2/3/4 door.
        private val BASE_HEALTHS: Map<Difficulty, List<Int>> = mapOf(
            Pair(Difficulty.EASY, listOf(12, 16, 20)),
            Pair(Difficulty.NORMAL, listOf(8, 12, 18)),
            Pair(Difficulty.HARD, listOf(6, 10, 15))
        )
    }
}

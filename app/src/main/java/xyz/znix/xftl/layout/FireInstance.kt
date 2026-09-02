package xyz.znix.xftl.layout

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.game.LoopHandle
import xyz.znix.xftl.game.UIUtils
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.RoomPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.SaveUtil

/**
 * This represents one cell of fire in a room.
 */
class FireInstance(val room: Room, val slot: Int) {
    val pos: RoomPoint = RoomPoint(room, room.slotToPoint(slot))
    private val ship: Ship = room.ship

    // Health against fire-fighting crew.
    var health: Float = 1f
        set(value) {
            field = value.coerceIn(0f..1f)
        }

    private var burnoutTimer: Float? = null

    private val animation: FTLAnimation = ship.sys.animations["fire_large"].startLooping(ship.sys)
    private val sound: LoopHandle = ship.sys.sounds.getLoop("fire")

    init {
        // Start at a random point in the animation, to make sure we don't
        // have multiple fires that are in-sync with each other.
        animation.timer = (0f..animation.duration).random(VisualRandom)
    }

    fun update(dt: Float) {
        sound.continueLoopPlayerOnly(ship)
        animation.update(dt)

        // If we were put out by a crewmember.
        if (health <= 0f) {
            extinguish()
            return
        }

        // Same damage as crew
        room.system?.attack(dt * 0.08f)

        // Drains 0.96% oxygen per second, per fire.
        room.oxygen -= dt * 0.0096f

        val lowO2 = room.oxygen < OXYGEN_CUTOFF

        // Spread to adjacent cells, and also count how many adjacent fires there are.
        var adjacentFires = 0
        for (dir in Direction.CARDINALS) {
            val checkPos = pos.shipPoint + dir

            // Check if there's a wall in the way
            val (connected, door) = checkConnection(checkPos, pos.shipPoint)
            if (!connected)
                continue

            // Check if there's a fire there. We know there must be a room
            // at this point, since otherwise we wouldn't have had a connection.
            val otherRoom = ship.rooms.first { it.containsAbsolute(checkPos) }
            if (otherRoom.fires.any { it?.pos?.shipPoint == checkPos }) {
                // This makes us go out slower when we run out of oxygen
                adjacentFires++
            } else {
                val slot = otherRoom.pointToSlot(checkPos - otherRoom.position)
                otherRoom.spreadFire(dt, slot, door)
            }
        }

        // If there's no oxygen, start our timer to burning out. Note that
        // this continues running after a room is drained of oxygen!
        // See doc/fires for more information.
        if (lowO2) {
            if (burnoutTimer == null) {
                burnoutTimer = (5 until 15).random().f
            }
            if (burnoutTimer!! <= 0) {
                extinguish()
            }
        }
        if (burnoutTimer != null && burnoutTimer!! > 0f) {
            val speed = (5 - adjacentFires) * 0.48f
            burnoutTimer = (burnoutTimer!! - dt * speed).coerceAtLeast(0f)
        }
    }

    fun draw() {
        val screenX = pos.offsetX
        val screenY = pos.offsetY

        animation.draw(screenX.f, screenY.f)
    }

    private fun extinguish() {
        room.fires[slot] = null

        // Play the smoke animation
        val centreOffset = ConstPoint(
            pos.offsetX + Constants.ROOM_SIZE / 2,
            pos.offsetY + Constants.ROOM_SIZE / 2
        )
        ship.playCentredAnimation(SMOKE_ANIMATION, centreOffset)
    }

    private fun checkConnection(from: IPoint, to: IPoint): Pair<Boolean, Door?> {
        // The fires must be adjacent
        if (from.distToSq(to) > 1) {
            return Pair(false, null)
        }

        // Check if there's a wall (but not door) in the way
        val fromRoom = ship.rooms.firstOrNull { it.containsAbsolute(from) } ?: return Pair(false, null)
        val toRoom = ship.rooms.firstOrNull { it.containsAbsolute(to) } ?: return Pair(false, null)

        if (fromRoom == toRoom) {
            return Pair(true, null)
        }

        // Check if there's a suitable door
        for (door in ship.doors) {
            // Find doors that connect both rooms
            if (door.left != fromRoom && door.left != toRoom)
                continue
            if (door.right != fromRoom && door.right != toRoom)
                continue

            if (door.roomPos(fromRoom).shipPoint posEq from && door.roomPos(toRoom).shipPoint posEq to) {
                return Pair(true, door)
            }
        }

        // No connecting door
        return Pair(false, null)
    }

    fun drawDebug(g: Graphics) {
        val x = pos.offsetX
        val y = pos.offsetY

        if (burnoutTimer != null) {
            // Use the maximum initial value to avoid storing
            // the actual random value we picked.
            val progress = 1 - burnoutTimer!! / 15f
            UIUtils.drawDebugBar(g, x + 8, y + 5, 5, 20, progress, Colour.black, Colour.blue)
        }

        if (health != 1f) {
            UIUtils.drawDebugBar(g, x + 15, y + 5, 5, 20, health, Colour.black, Colour.red)
        }
    }

    fun saveToXML(elem: Element) {
        SaveUtil.addAttrFloat(elem, "health", health)
        SaveUtil.addAttrFloat(elem, "burnoutTimer", burnoutTimer)
        SaveUtil.addAttrFloat(elem, "animation", animation.timer)
    }

    fun loadFromXML(elem: Element) {
        health = SaveUtil.getAttrFloat(elem, "health")
        burnoutTimer = SaveUtil.getAttrFloatOrNull(elem, "burnoutTimer")
        animation.timer = SaveUtil.getAttrFloat(elem, "animation")
    }

    companion object {
        const val OXYGEN_CUTOFF = 0.1f // Starts burning out at 10% o2

        // The smoke animation is hardcoded
        private val SMOKE_SPRITE_SHEET = Animations.SpriteSheetSpec(
            "img/effects/fire_smoke.png",
            34, 34, 238, 34
        )
        val SMOKE_ANIMATION = AnimationSpec(
            SMOKE_SPRITE_SHEET, "xftl_hardcoded_smoke",
            0, 0,
            SMOKE_SPRITE_SHEET.horizontalCount,
            1f / SMOKE_SPRITE_SHEET.horizontalCount
        )
    }
}

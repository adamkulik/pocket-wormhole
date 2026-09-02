package xyz.znix.xftl.layout

import org.jdom2.Element
import xyz.znix.xftl.AnimationSpec
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.math.RoomPoint
import xyz.znix.xftl.savegame.SaveUtil

class BreachInstance(val room: Room, val slot: Int) {
    val pos: RoomPoint = RoomPoint(room, room.slotToPoint(slot))
    private val ship: Ship = room.ship

    // Health against crew repairs.
    var health: Float = 1f
        set(value) {
            field = value.coerceIn(0f..1f)
        }

    private val animation: AnimationSpec = ship.sys.animations["breach"]

    fun draw() {
        val screenX = pos.offsetX
        val screenY = pos.offsetY

        // Frame 0 is most repaired, and later frames are for a less and
        // less repaired breach.
        // Health reaches from 0-1, so by multiplying with length we get
        // a value in 0-length. The largest valid value is length-1, so
        // clamp it. This means all the frames show for an equal period
        // while the breach is being repaired.
        val length = animation.length
        val frame = (health * length).toInt().coerceAtMost(length - 1)
        val image = animation.spriteAt(ship.sys, frame)

        // Centre the image
        val offsetX = (Constants.ROOM_SIZE - image.width) / 2
        val offsetY = (Constants.ROOM_SIZE - image.height) / 2

        image.draw(screenX + offsetX, screenY + offsetY)
    }

    fun saveToXML(elem: Element) {
        SaveUtil.addAttrFloat(elem, "health", health)
    }

    fun loadFromXML(elem: Element) {
        health = SaveUtil.getAttrFloat(elem, "health")
    }
}

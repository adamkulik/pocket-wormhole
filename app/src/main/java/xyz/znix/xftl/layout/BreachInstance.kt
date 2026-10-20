package xyz.znix.xftl.layout

import org.jdom2.Element
import xyz.znix.xftl.AnimationSpec
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
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

    companion object {
        // Crew repair a breach at 8% per second (12.5s total). AugSlugGel
        // scales this by the augment's value.
        const val CREW_REPAIR_RATE = 0.08f
    }

    private val animation: AnimationSpec = ship.sys.animations["breach"]

    /**
     * Whether the Slug Repair Gel layer is drawn over this breach. Vanilla
     * checks the SLUG_GEL augment value against zero (exe FUN_0063f220),
     * which presence-only [Ship.hasAugment] wouldn't capture for modded
     * augments with a zero value. Exposed for the test kit.
     */
    val hasGelOverlay: Boolean
        get() = ship.getAugmentValue(AugmentBlueprint.SLUG_GEL) != 0f

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

        // Slug Repair Gel (SLUG_GEL): vanilla draws a second, magenta-tinted
        // copy of the same breach frame for ships with this augment. It's
        // translated (-7,-7) from the normal sprite position (i.e. to the
        // room cell's origin), so it reads as a purple gel layer over the
        // breach that shrinks along with it while the augment seals it.
        // FTLGame.exe 1.6.14 FUN_0063f220.
        if (hasGelOverlay) {
            image.draw(screenX + offsetX - 7, screenY + offsetY - 7, Constants.BREACH_GEL_OVERLAY)
        }
    }

    fun saveToXML(elem: Element) {
        SaveUtil.addAttrFloat(elem, "health", health)
    }

    fun loadFromXML(elem: Element) {
        health = SaveUtil.getAttrFloat(elem, "health")
    }
}

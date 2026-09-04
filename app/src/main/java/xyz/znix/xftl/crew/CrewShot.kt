package xyz.znix.xftl.crew

import xyz.znix.xftl.f
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A cosmetic laser bolt fired between two fighting crewmembers.
 *
 * This is purely visual - it cannot be shot down, ignores shields, deals no
 * damage (the damage was already applied by the attack that spawned it) and
 * isn't serialised. Positions are ship-relative pixel coordinates, like
 * [AbstractCrew.getPixelPositionCentre].
 */
class CrewShot(val room: Room, start: IPoint, end: IPoint) {
    // Snapshot both endpoints at spawn time. The target may keep moving for
    // a fraction of a second, but over a ~0.2s flight the error is a few
    // pixels - not worth tracking live positions (which would need care
    // around dying/dead crew).
    private val startX = start.x.f
    private val startY = start.y.f
    private val distX = end.x.f - startX
    private val distY = end.y.f - startY
    private val totalDist = hypot(distX, distY)
    private val angleDeg = Math.toDegrees(atan2(distY, distX).toDouble()).toFloat()

    private var travelled = 0f

    /** Advance the bolt; returns false once it has landed. */
    fun update(dt: Float): Boolean {
        travelled += SPEED * dt
        return travelled < totalDist
    }

    fun render(g: Graphics) {
        val progress = (travelled / totalDist).coerceIn(0f, 1f)
        val x = startX + distX * progress
        val y = startY + distY * progress

        val oldColour = g.colour

        g.pushTransform()
        g.translate(x, y)
        // The trail extends backwards from the origin, the core forwards.
        g.rotate(0f, 0f, angleDeg)
        g.colour = TRAIL_COLOUR
        g.fillRect(-TRAIL_LENGTH, -TRAIL_WIDTH / 2f, TRAIL_LENGTH, TRAIL_WIDTH)
        g.colour = CORE_COLOUR
        g.fillRect(0f, -CORE_WIDTH / 2f, CORE_LENGTH, CORE_WIDTH)
        g.popTransform()

        g.colour = oldColour
    }

    companion object {
        // Tuned by eye against vanilla gameplay footage; adjust freely.
        const val SPEED = 600f // px/s

        const val CORE_LENGTH = 7f
        const val CORE_WIDTH = 2f
        const val TRAIL_LENGTH = 14f
        const val TRAIL_WIDTH = 4f

        val CORE_COLOUR = Colour(1f, 1f, 0.75f)      // warm white
        val TRAIL_COLOUR = Colour(1f, 0.6f, 0.2f, 0.45f) // orange, translucent
    }
}

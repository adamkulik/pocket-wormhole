package xyz.znix.xftl.game

import xyz.znix.xftl.VisualRandom
import xyz.znix.xftl.f
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

/**
 * Displays a message with an optional line pointing to it.
 */
class WarningFlasher(
    val game: InGameState,
    var centre: IPoint,
    key: String,
    val warningWarning: Boolean = true,
    val linePoints: List<IPoint> = emptyList(),
    val colour: GlowColour = GlowColour.RED,
    val animated: Boolean = true,
    val alwaysOn: Boolean = false
) {
    private val textLines: List<String> = game.translator[key].split("\n")
    private val warningText: String = game.translator["warning_warning"]

    private val font = game.getFont("HL1", 2f)

    private var stopTime: Long = 0

    // Stop all the warnings flashing in sync.
    private val flashOffset = VisualRandom.nextLong()

    var timeRemaining: Float = 0f
        private set

    val isRunning: Boolean get() = alwaysOn || timeRemaining > 0f

    /**
     * If this flasher is flashing, this checks if it's at it's stronger intensity.
     *
     * This is used for flashing other components when this warning running.
     */
    var isFlashingHigh: Boolean = false
        private set

    fun draw(g: Graphics) {
        update()

        if (!isRunning)
            return

        val alpha = when {
            !animated -> 1f // Not flashing
            isFlashingHigh -> 0.9f
            else -> 0.4f
        }

        val lineSpacing = 18
        val topY = centre.y - lineSpacing * (textLines.size - 1)

        for ((index, line) in textLines.withIndex()) {
            val x = centre.x - font.getWidth(line) / 2
            UIUtils.drawStringWithGlow(game, font, line, x, topY + lineSpacing * index, colour, alpha)
        }

        if (warningWarning) {
            val spacing = if (linePoints.isEmpty()) 24 else 23
            val x = centre.x - font.getWidth(warningText) / 2

            UIUtils.drawStringWithGlow(game, font, warningText, x, centre.y - spacing, colour, alpha)
        }

        g.colour = Colour(this.colour.colour).also { it.a = alpha }
        for (i in 0 until linePoints.size - 1) {
            val a = linePoints[i]
            val b = linePoints[i + 1]

            // TODO make the line thickness properly match FTL - on angles
            //  ours doesn't look as good.

            g.lineWidth = 2f
            g.drawLine(a.x.f, a.y.f, b.x.f, b.y.f)
            g.lineWidth = 1f
        }
    }

    private fun update() {
        val current = System.nanoTime()
        val remainingNS = stopTime - current

        timeRemaining = when {
            remainingNS < 0 -> 0f

            // Guard against overflow, however unlikely that might be.
            remainingNS > (1L shl 50) -> 0f

            else -> remainingNS / 1_000_000_000f
        }

        if (!isRunning) {
            isFlashingHigh = false
        } else {
            // Calculate our flash timer.
            // Don't use the time remaining, as that might be constantly reset.
            val period = 1_000_000_000
            var timeNS = (current + flashOffset) % period
            if (timeNS < 0)
                timeNS += period
            val time = timeNS.toFloat() / period

            isFlashingHigh = time > 0.5f
        }
    }

    fun startFor(time: Float) {
        if (alwaysOn) {
            throw IllegalStateException("Cannot start always-on warning")
        }

        val current = System.nanoTime()
        stopTime = current + (time * 1_000_000_000f).toLong()
    }

    fun stop() {
        if (alwaysOn) {
            throw IllegalStateException("Cannot stop always-on warning")
        }

        stopTime = 0
    }
}

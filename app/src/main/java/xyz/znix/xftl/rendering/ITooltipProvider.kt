package xyz.znix.xftl.rendering

import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState

/**
 * Represents something that can draw a tooltip.
 *
 * This is part of the rendering API, since it's convenient to track what
 * the cursor is on top of there.
 */
interface ITooltipProvider {
    fun drawTooltip(
        g: Graphics,
        mouseX: Int, mouseY: Int,
        firstFrame: Boolean,
        screenWidth: Int, screenHeight: Int
    )
}

abstract class StandardTooltip(game: InGameState) : ITooltipProvider {
    private val font = game.getFont("JustinFont11Bold")
    protected open val customMaxWidth: Int? = null

    abstract fun getText(): String

    override fun drawTooltip(
        g: Graphics,
        mouseX: Int, mouseY: Int,
        firstFrame: Boolean,
        screenWidth: Int, screenHeight: Int
    ) {
        // From base game: 350 with title, 275 without.
        // (note this might be swapped, you may want to double-check. However,
        //  the 350px doesn't seem to match the jump button)
        val lines = font.wrapString(getText(), customMaxWidth ?: 275)

        val height = TooltipConstants.heightOf(lines)
        val width = TooltipConstants.widthOf(lines, font)

        // Find the X/Y that keeps the tooltip inside the window
        val x = mouseX.coerceAtMost(screenWidth - 40 - width)
        var y = mouseY + 20

        if (y + height >= screenHeight - 30) {
            y = mouseY - height - 10
        }

        TooltipConstants.drawTooltip(g, font, x, y, width, height, lines)
    }
}

/**
 * A tooltip that only shows up if the user stops moving the mouse for a period.
 *
 * TODO: Don't use InGameState
 */
abstract class DelayedTooltip(private val game: InGameState) : StandardTooltip(game) {
    open val delayPeriod: Float = 0.25f // TODO check the actual value?

    private var showing: Boolean = false
    private var timer: Float = 0f
    private var lastX: Int = 0
    private var lastY: Int = 0

    override fun drawTooltip(
        g: Graphics,
        mouseX: Int, mouseY: Int,
        firstFrame: Boolean,
        screenWidth: Int, screenHeight: Int
    ) {
        // If the tooltip was un-hovered, reset the timer before it shows up again.
        if (firstFrame) {
            showing = false
            timer = 0f
        }

        // Implement the delay before the tooltip appears
        if (!showing) {
            timer += game.renderingDeltaTime

            if (mouseX != lastX || mouseY != lastY) {
                timer = 0f
            } else if (timer >= delayPeriod) {
                showing = true
            }
            lastX = mouseX
            lastY = mouseY
            return
        }

        super.drawTooltip(g, mouseX, mouseY, false, screenWidth, screenHeight)
    }
}

/**
 * A [DelayedTooltip] instance that shows a translated string.
 */
class FixedDelayedTooltip(private val game: InGameState, val textKey: String) : DelayedTooltip(game) {
    override fun getText(): String {
        return game.translator[textKey]
    }
}

/**
 * A [StandardTooltip] instance that shows a translated string.
 */
class FixedStandardTooltip(private val game: InGameState, val textKey: String) : StandardTooltip(game) {
    override fun getText(): String {
        return game.translator[textKey]
    }
}

/**
 * Similar to [FixedDelayedTooltip], this shows a translated string but with
 * the button bound to a hotkey inserted in place of a '\1' template.
 *
 * TODO pass in the hotkey ID
 */
class HotkeyDelayedTooltip(private val game: InGameState, val textKey: String) : DelayedTooltip(game) {
    override fun getText(): String {
        return game.translator[textKey]
    }
}

object TooltipConstants {
    const val LINE_SPACING = 20 // px between lines
    const val BOX_TOP_OFFSET = 23
    const val BOX_BOTTOM_OFFSET = 15
    const val BOX_LEFT_OFFSET = 15
    const val BOX_RIGHT_OFFSET = 12

    fun widthOf(lines: List<String>, font: SILFontLoader): Int {
        return BOX_LEFT_OFFSET + lines.maxOf(font::getWidth) + BOX_RIGHT_OFFSET
    }

    fun heightOf(lines: List<String>): Int {
        return BOX_TOP_OFFSET + (lines.size - 1) * LINE_SPACING + BOX_BOTTOM_OFFSET
    }

    fun drawTooltip(g: Graphics, font: SILFontLoader, x: Int, y: Int, width: Int, height: Int, lines: List<String>) {
        g.colour = Colour.white
        g.fillRect(x, y, width, height)
        g.colour = Colour.black
        g.fillRect(x + 2, y + 2, width - 4, height - 4)

        for ((i, line) in lines.withIndex()) {
            font.drawString(x.f + BOX_LEFT_OFFSET, y.f + BOX_TOP_OFFSET + i * LINE_SPACING, line, Colour.white)
        }
    }
}

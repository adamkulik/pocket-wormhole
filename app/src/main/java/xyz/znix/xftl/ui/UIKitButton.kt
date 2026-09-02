package xyz.znix.xftl.ui

import org.jdom2.Element
import xyz.znix.xftl.Constants
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.Window
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.Input
import kotlin.math.max

class UIKitButton(
    provider: UIProvider,
    val normalColour: Colour,
    val highlightColour: Colour,
    val disabledColour: Colour
) : Widget(provider) {
    override val size: Point = Point(0, 0)

    /**
     * True if the button should show it's disabled version.
     */
    var disabled: Boolean = false

    /**
     * True if the button should always show it's selected version.
     */
    var forceSelected: Boolean = false

    /**
     * The button on-click listener.
     *
     * This is only used while in-game.
     */
    var clickListener: (() -> Unit)? = null

    private var hovered: Boolean = false

    override fun draw(g: Graphics) {
        g.colour = when {
            forceSelected -> highlightColour
            disabled -> disabledColour
            hovered -> highlightColour
            else -> normalColour
        }
        Buttons.drawRounded(g, position.x, position.y, size.x, size.y, 4)

        postDraw(g)
    }

    override fun attemptStretch(availableWidth: Int, availableHeight: Int) {
        size.x = max(size.x, availableWidth)
        size.y = max(size.y, availableHeight)
    }

    override fun updateSizes() {
        // Find the child sizes
        super.updateSizes()

        // And make sure they all fit inside this image
        stretchToFitChildren()
    }

    override fun createGameButtons(game: InGameState, window: Window, offset: IPoint): List<Button> {
        return listOf(InGameButton(game, position + offset))
    }

    override fun updateMouse(x: Int, y: Int) {
        super.updateMouse(x, y)

        hovered = x - position.x in 0 until size.x && y - position.y in 0 until size.y
    }

    override fun mouseClicked(button: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        if (!hovered || disabled)
            return

        clickListener?.let { it() }
    }

    companion object {
        fun fromXML(provider: UIProvider, elem: Element): UIKitButton {
            val colour = SpecDeserialiser.parseColour(elem, "colour", Constants.SECTOR_CUTOUT_TEXT)
            val highlight = SpecDeserialiser.parseColour(elem, "colour", Constants.UI_BUTTON_HOVER)
            val disabled = SpecDeserialiser.parseColour(elem, "colour", Constants.JUMP_DISABLED)

            val view = UIKitButton(provider, colour, highlight, disabled)

            elem.getAttributeValue("w")?.let { view.size.x = it.toInt() }
            elem.getAttributeValue("h")?.let { view.size.y = it.toInt() }

            view.loadXML(elem)
            return view
        }
    }

    /**
     * If we're running in-game (as opposed to the UI editor), an invisible
     * in-game button is created, which handles stuff like hover detection
     * and audio cues.
     *
     * We still render it as part of the widget though, to get the z-order
     * correct with other components.
     */
    private inner class InGameButton(game: InGameState, pos: IPoint) : Button(game, pos, size) {
        override fun draw(g: Graphics) {
            // Do nothing, the widget does all the drawing.
        }

        override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            clickListener?.let { it() }
        }

        override fun update(x: Int, y: Int, blockHover: Boolean) {
            super.update(x, y, blockHover)

            this@UIKitButton.hovered = hovered
        }
    }
}

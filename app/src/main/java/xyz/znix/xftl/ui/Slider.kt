package xyz.znix.xftl.ui

import org.jdom2.Element
import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.Window
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.max
import kotlin.math.roundToInt

class Slider(provider: UIProvider) : Widget(provider) {
    override val size = Point(0, 20)

    /**
     * The value of this slider, from 0-1
     */
    var value: Float = 0f

    var changeListener: (() -> Unit)? = null

    private val handleMinX: Int get() = position.x + END_STOP
    private val handleMaxX: Int get() = position.x + size.x - END_STOP - HANDLE_SIZE.x
    private val handleX: Int
        get() {
            val min = handleMinX
            val range = handleMaxX - min
            return min + (range * value).roundToInt()
        }

    private val handleY: Int get() = position.y + (size.y - HANDLE_SIZE.y) / 2

    private var gameButton: SliderButton? = null

    override fun draw(g: Graphics) {
        g.colour = Colour(0f, 0f, 0f, Constants.CREW_BOX_BG_ALPHA)
        g.fillRect(position.x, position.y, size.x, size.y)

        g.colour = gameButton?.getColour() ?: Colour.white
        g.drawRect(position.x, position.y, size.x - 1, size.y - 1)
        g.drawRect(position.x + 1, position.y + 1, size.x - 3, size.y - 3)

        g.fillRect(handleX, handleY, HANDLE_SIZE.x, HANDLE_SIZE.y)

        postDraw(g)
    }

    override fun attemptStretch(availableWidth: Int, availableHeight: Int) {
        size.x = max(size.x, availableWidth)
        size.y = max(size.y, availableHeight)
    }

    override fun createGameButtons(game: InGameState, window: Window, offset: IPoint): List<Button> {
        gameButton = SliderButton(game, window)
        return listOf(gameButton!!)
    }

    private inner class SliderButton(game: InGameState, val window: Window) : Button(game, position, size) {
        fun getColour(): Colour {
            return when {
                disabled -> Constants.JUMP_DISABLED
                hovered -> Constants.UI_BUTTON_HOVER
                else -> Constants.SECTOR_CUTOUT_TEXT
            }
        }

        override fun draw(g: Graphics) {
            // Do nothing, the widget does all the drawing.
        }

        override fun mouseDown(button: Int, x: Int, y: Int): Boolean {
            if (!contains(x, y)) return false
            if (mouseObstructed) return false

            // Assume the UI is positioned at the window's origin.
            // It's not very nice, but it works.
            val uiX = x - window.position.x

            // We want to centre the handle on the mouse
            // We can thus find the target left-hand side of the handle
            val targetX = uiX - HANDLE_SIZE.x / 2
            val minX = handleMinX
            val newValue = (targetX - minX) / (handleMaxX - minX).f

            value = newValue.coerceIn(0f, 1f)
            changeListener?.let { it() }

            return true
        }

        override fun click(button: Int) {
            // Our custom mouseDown implementation never calls this
        }

        // TODO make dragging work
    }

    companion object {
        /**
         * How far away the slider is from one of the ends of the widget when it's at min/max.
         */
        private const val END_STOP = 5

        private val HANDLE_SIZE = ConstPoint(15, 30)

        fun fromXML(provider: UIProvider, elem: Element): Slider {
            val view = Slider(provider)
            elem.getAttributeValue("w")?.let { view.size.x = it.toInt() }
            view.loadXML(elem)
            return view
        }
    }
}

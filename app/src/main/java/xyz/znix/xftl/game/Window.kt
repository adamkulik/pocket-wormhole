package xyz.znix.xftl.game

import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.roundToInt

abstract class Window {
    var position: IPoint = ConstPoint.ZERO
        set(value) {
            // Don't run positionUpdated if nothing changed
            if (value == field)
                return

            field = value
            positionUpdated()
        }

    /**
     * This is added to the window's position, after it's position
     * to make it centred was calculated.
     */
    open val windowCentreOffset: IPoint get() = ConstPoint.ZERO

    /**
     * Touch ergonomics: a scale factor the window is drawn at, anchored
     * at the window's centre. renderSingleMenu places the SCALED window
     * centrally, then scales the drawing about the unscaled window's
     * centre - which maps it exactly onto that rectangle. Hit-testing
     * overrides receiving mouse coordinates convert them with
     * [scaleWindowPoint]; the base [mouseClick]/[updateUI] already do.
     * 1 on vanilla layouts.
     */
    open val renderScale: Float get() = 1f

    /**
     * Convert a screen-space point into this window's unscaled
     * coordinate space (still in absolute screen coordinates, like
     * [position]) - the inverse of drawing at [renderScale] about the
     * window's centre. Identity when [renderScale] is 1.
     */
    fun scaleWindowPoint(x: Int, y: Int): Point {
        if (renderScale == 1f)
            return Point(x, y)

        val centreX = position.x + size.x / 2f
        val centreY = position.y + size.y / 2f
        return Point(
            (centreX + (x - centreX) / renderScale).roundToInt(),
            (centreY + (y - centreY) / renderScale).roundToInt()
        )
    }

    /**
     * If true, the window applies the background grey-out tint itself.
     *
     * (this is what makes everything in-game darker when a window is open)
     *
     * If not, the tint is applied by [PlayerShipUI] before the window is drawn.
     */
    open val appliesSelfTint: Boolean = false

    abstract val size: IPoint

    val buttons = ArrayList<Button>()

    abstract fun draw(g: Graphics)

    open fun mouseClick(button: Int, x: Int, y: Int) {
        val p = scaleWindowPoint(x, y)

        // Mouse clicking may change the buttons array (eg in the store
        // window when switching tabs), so copy it.
        for (btn in ArrayList(buttons)) {
            btn.mouseDown(button, p.x, p.y)
        }
    }

    open fun mouseReleased(button: Int, x: Int, y: Int) {
    }

    open fun mouseScroll(change: Int) {
    }

    /**
     * Called whenever the escape key is pressed.
     *
     * Subclasses should either close the window, or call
     * [PlayerShipUI.showPauseWindow] to overlay the pause menu.
     */
    abstract fun escapePressed()

    protected open fun positionUpdated() {
        for (button in buttons) {
            button.windowOffset = position
        }
    }

    open fun updateUI(x: Int, y: Int) {
        val p = scaleWindowPoint(x, y)

        // Start at the end of the array, so if buttons overlap we only
        // hover the one that's rendered last.
        var anyHovered = false
        for (i in buttons.size - 1 downTo 0) {
            buttons[i].update(p.x, p.y, anyHovered)
            if (buttons[i].hovered)
                anyHovered = true
        }
    }

    /**
     * Called whenever the ship is changed in some way - for example,
     * weapons are added or moved around.
     */
    open fun shipModified() {}

    /**
     * Called when some text is inputted.
     *
     * For actual text input only - don't use it for hotkeys!
     *
     * @return True if the window consumed the key input.
     */
    open fun onTextInput(key: Int, c: Char): Boolean {
        return false
    }

    open fun hotkeyPressed(key: Hotkey) {
    }
}

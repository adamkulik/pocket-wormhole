package xyz.znix.xftl.ui

import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.Window
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics

class WidgetContainer(val root: Widget) {
    private val sortedWidgets = ArrayList<Widget>()

    val byId: Map<String, Widget>

    val allWidgets: Collection<Widget> get() = sortedWidgets

    init {
        fun recurse(widget: Widget) {
            sortedWidgets.add(widget)
            for (child in widget.children) {
                recurse(child)
            }
        }
        recurse(root)

        // Draw in z (depth) order
        sortedWidgets.sortBy { it.zOrder }

        byId = HashMap()
        for (widget in sortedWidgets) {
            val id = widget.id ?: continue

            if (byId.containsKey(id)) {
                throw IllegalUISpecException("Duplicate widget id: '$id'")
            }

            byId[id] = widget
        }
    }

    fun draw(g: Graphics) {
        for (widget in sortedWidgets) {
            if (!widget.isVisible)
                continue

            widget.draw(g)
        }
    }

    /**
     * Update the widgets based on the mouse position.
     *
     * This should not be called for any UI used in the in-game state, as that
     * uses proper [Button] objects instead.
     */
    fun updateMouse(x: Int, y: Int) {
        for (widget in sortedWidgets) {
            widget.updateMouse(x, y)
        }
    }

    /**
     * Alert all widgets that a mouse button was clicked.
     *
     * This should not be called for any UI used in the in-game state, as that
     * uses proper [Button] objects instead.
     */
    fun mouseClicked(button: Int) {
        for (widget in sortedWidgets) {
            widget.mouseClicked(button)
        }
    }

    fun addButtonListener(id: String, listener: () -> Unit) {
        val button = byId[id] as UIKitButton
        button.clickListener = listener
    }

    fun updateLayout() {
        root.updateSizes()
        root.expandToParent(ConstPoint.ZERO)
        root.updateLayout()
    }

    /**
     * Builds in-game buttons, to make the UI interactive.
     *
     * [offset] is the difference between the origin of the root widget
     * and the origin of the parent window.
     */
    fun buildButtons(game: InGameState, window: Window, offset: IPoint): List<Button> {
        return sortedWidgets.flatMap { it.createGameButtons(game, window, offset) }
    }
}

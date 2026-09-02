package xyz.znix.xftl.ui

import org.jdom2.Element
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.Window
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.max

abstract class Widget(val provider: UIProvider) {
    val position: Point = Point(ConstPoint.ZERO)
    abstract val size: IPoint

    /**
     * All widgets can contain children, not just special container widgets.
     */
    val children = ArrayList<Widget>()

    /**
     * The value of the 'id' attribute set in XML.
     */
    var id: String? = null
        private set

    /**
     * Whether or not this widget is visible.
     *
     * This is not inherited: setting this to false for a widget won't
     * hide its children.
     */
    var isVisible: Boolean = true

    // If there's some excess space, it's split between widgets based on this.
    // Zero means the widget doesn't resize.
    var xStretch: Float = 0f
    var yStretch: Float = 0f

    /**
     * The z-order of this component, higher means it draws later.
     *
     * [Int.MIN_VALUE] means this field is uninitialised.
     */
    var zOrder = Int.MIN_VALUE

    // The distances between the edges of this element and our parent.
    // This will both position this widget, and expand either the parent
    // or us if both values in an axis are set.
    var parentLeft: Int? = null
    var parentRight: Int? = null
    var parentTop: Int? = null
    var parentBottom: Int? = null

    open fun init(parent: Widget?) {
        // Don't overwrite the depth if it's manually set
        if (zOrder == Int.MIN_VALUE) {
            zOrder = parent?.let { it.zOrder + 10 } ?: 0
        }

        for (child in children) {
            child.init(this)
        }
    }

    abstract fun draw(g: Graphics)

    fun postDraw(g: Graphics) {
        val colour = provider.getDebugOutlineColour(this)
        if (colour != null) {
            g.colour = colour
            g.drawRect(position.x.f, position.y.f, size.x - 1f, size.y - 1f)
        }
    }

    open fun updateSizes() {
        for (child in children) {
            child.updateSizes()
        }
    }

    open fun expandToParent(parentSize: IPoint) {
        // A separate pass after the minimum sizes have been set, to expand
        // any widgets that are linked to both sides of an axis of their
        // parent. We can't solve these in updateSizes, since it can only
        // be called on children after expanding the parent (the opposite
        // of updateSizes).

        // Stretch to fit our parent
        val left = parentLeft
        val right = parentRight
        val top = parentTop
        val bottom = parentBottom

        var newWidth = 0
        var newHeight = 0

        if (left != null && right != null) {
            newWidth = parentSize.x - left - right
        }
        if (top != null && bottom != null) {
            newHeight = parentSize.y - top - bottom
        }

        if (newWidth > 0 || newHeight > 0) {
            attemptStretch(newWidth, newHeight)
        }

        // Now we've increased our size, let our children do that too.
        for (child in children) {
            child.expandToParent(size)
        }
    }

    open fun updateLayout() {
        // Position the children that manually set a position
        for (child in children) {
            val left = child.parentLeft
            val right = child.parentRight
            val top = child.parentTop
            val bottom = child.parentBottom

            if (left != null) {
                child.position.x = position.x + left
            } else if (right != null) {
                child.position.x = position.x + size.x - right - child.size.x
            }

            if (top != null) {
                child.position.y = position.y + top
            } else if (bottom != null) {
                child.position.y = position.y + size.y - bottom - child.size.y
            }
        }

        for (child in children) {
            child.updateLayout()
        }
    }

    /**
     * Find the minimum size this widget needs to be to properly fit
     * all the child widgets which attach to both the left/right or the
     * top/bottom edges of this widget.
     */
    fun getChildRequestedSize(): IPoint {
        val minSize = Point(0, 0)

        for (child in children) {
            val left = child.parentLeft
            val right = child.parentRight
            val top = child.parentTop
            val bottom = child.parentBottom

            if (left != null && right != null) {
                minSize.x = max(minSize.x, left + child.size.x + right)
            }

            if (top != null && bottom != null) {
                minSize.y = max(minSize.y, top + child.size.y + bottom)
            }
        }

        return minSize
    }

    fun stretchToFitChildren() {
        val crs = getChildRequestedSize()
        attemptStretch(crs.x, crs.y)
    }

    open fun attemptStretch(availableWidth: Int, availableHeight: Int) {
        // Do nothing, subclasses can implement
    }

    /**
     * The widget system itself doesn't have any kind of interactivity.
     *
     * For this, in-game buttons can be created, but they only work with
     * the in-game state.
     *
     * [offset] is the offset from the in-game window coordinate system
     * to the widget coordinate system.
     */
    open fun createGameButtons(game: InGameState, window: Window, offset: IPoint): List<Button> {
        return emptyList()
    }

    /**
     * Update this component, based on where the mouse is.
     *
     * This is not called in-game; there, use [createGameButtons] for interactivity.
     * Since those buttons can only exist in-game, this is used elsewhere.
     */
    open fun updateMouse(x: Int, y: Int) {
    }

    /**
     * Called when a mouse button is clicked.
     *
     * This is not called in-game; there, use [createGameButtons] for interactivity.
     * Since those buttons can only exist in-game, this is used elsewhere.
     */
    open fun mouseClicked(button: Int) {
    }

    protected fun loadXML(elem: Element) {
        id = elem.getAttributeValue("id")

        elem.getAttributeValue("xStretch")?.let { xStretch = it.toFloat() }
        elem.getAttributeValue("yStretch")?.let { yStretch = it.toFloat() }

        elem.getAttributeValue("pLeft")?.let { parentLeft = it.toInt() }
        elem.getAttributeValue("pRight")?.let { parentRight = it.toInt() }
        elem.getAttributeValue("pTop")?.let { parentTop = it.toInt() }
        elem.getAttributeValue("pBottom")?.let { parentBottom = it.toInt() }

        elem.getAttributeValue("zOrder")?.let { zOrder = it.toInt() }
    }
}

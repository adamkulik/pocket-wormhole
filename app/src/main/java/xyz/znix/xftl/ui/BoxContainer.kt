package xyz.znix.xftl.ui

import org.jdom2.Element
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.max

/**
 * A container that lays out all it's children in a line, packed together.
 */
class BoxContainer(provider: UIProvider, val layoutDirection: Direction) : Widget(provider) {
    private val mutableSize = Point(10, 10)
    override val size: IPoint get() = mutableSize

    // Store the sizes we want the children to be, after applying stretching
    private val childSizes = ArrayList<Int>()

    init {
        require(layoutDirection == Direction.RIGHT || layoutDirection == Direction.DOWN)
    }

    override fun draw(g: Graphics) {
        postDraw(g)
    }

    override fun updateSizes() {
        super.updateSizes()

        mutableSize.set(0, 0)
        childSizes.clear()
        for (child in children) {
            val cSize = child.size
            if (layoutDirection == Direction.RIGHT) {
                mutableSize.x += cSize.x
                childSizes.add(cSize.x)
                mutableSize.y = max(mutableSize.y, cSize.y)
            } else {
                mutableSize.x = max(mutableSize.x, cSize.x)
                mutableSize.y += cSize.y
                childSizes.add(cSize.y)
            }
        }

        // Try and stretch the children in the direction we're not laying them out in
        for (child in children) {
            if (layoutDirection == Direction.RIGHT) {
                child.attemptStretch(0, size.y)
            } else {
                child.attemptStretch(size.x, 0)
            }
        }
    }

    override fun updateLayout() {
        var offset = 0
        for ((index, child) in children.withIndex()) {
            if (layoutDirection == Direction.RIGHT) {
                child.position.set(position.x + offset, position.y)
            } else {
                child.position.set(position.x, position.y + offset)
            }

            offset += childSizes[index]
        }

        // Update the layout of our children once they're positioned, so their
        // children can inherit the position we just set.
        super.updateLayout()
    }

    override fun attemptStretch(availableWidth: Int, availableHeight: Int) {
        val extraX = availableWidth - size.x
        if (extraX > 0 && layoutDirection.isHorizontal) {
            computeStretches(extraX, { it.xStretch }) { child, newWidth ->
                child.attemptStretch(newWidth, 0)
            }

            mutableSize.x = availableWidth
        }

        // Copy-paste of the above, but for Y
        val extraY = availableHeight - size.y
        if (extraY > 0 && layoutDirection.isVertical) {
            computeStretches(extraY, { it.yStretch }) { child, newHeight ->
                child.attemptStretch(0, newHeight)
            }

            mutableSize.y = availableHeight
        }
    }

    private fun computeStretches(extraSpace: Int, getStretch: (Widget) -> Float, apply: (Widget, Int) -> Unit) {
        val totalStretch = children.sumOf { getStretch(it).toDouble() }.toFloat()

        // Find out how much space each child would receive
        // Note this rounds down, so there's probably going to be a bit
        // of extra space left over.
        // If we have a column of hbox widgets or vice-versa, it probably needs
        // to be the exact width so the right-hand elements line up.
        // Thus we'll find an error, and add that back in later.
        val regularExtras = children.map {
            val stretch = getStretch(it)
            if (stretch == 0f)
                return@map null

            val fraction = stretch / totalStretch
            (extraSpace * fraction).toInt()
        }

        var error = extraSpace - regularExtras.filterNotNull().sum()

        for ((i, child) in children.withIndex()) {
            var extra = regularExtras[i] ?: continue

            // Spread the aforementioned integer round-off error among the first
            // lot of stretchable components.
            if (error > 0) {
                extra += 1
                error--
            }

            val newSize = childSizes[i] + extra
            childSizes[i] = newSize

            apply(child, newSize)
        }
    }

    companion object {
        fun fromXML(provider: UIProvider, elem: Element, vertical: Boolean): BoxContainer {
            val dir = when (vertical) {
                true -> Direction.DOWN
                false -> Direction.RIGHT
            }
            val box = BoxContainer(provider, dir)
            box.loadXML(elem)
            return box
        }
    }
}

package xyz.znix.xftl.ui

import org.jdom2.Element
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.max

/**
 * A container that doesn't impose any layout on its children, so it can
 * be used to represent stuff like image padding.
 */
class FreeContainer(provider: UIProvider, size: IPoint) : Widget(provider) {
    override val size = Point(size)

    override fun draw(g: Graphics) {
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

    companion object {
        fun fromXML(provider: UIProvider, elem: Element): FreeContainer {
            val size = Point(0, 0)
            elem.getAttributeValue("w")?.let { size.x = it.toInt() }
            elem.getAttributeValue("h")?.let { size.y = it.toInt() }

            val view = FreeContainer(provider, size)
            view.loadXML(elem)
            return view
        }
    }
}

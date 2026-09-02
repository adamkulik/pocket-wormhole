package xyz.znix.xftl.ui

import org.jdom2.Element
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.max

/**
 * Draws an event-dialogue-style window, with an optional inner title.
 */
class StyledWindowView(provider: UIProvider, size: IPoint) : Widget(provider) {
    override val size = Point(size)

    /**
     * Re-use a label to parse all the title settings, but it's not drawn
     * as a regular label.
     */
    var title: Label? = null

    override fun draw(g: Graphics) {
        val title = title // Allow smart-casting

        if (title == null) {
            provider.getWindowRenderer().render(position.x, position.y, size.x, size.y)
        } else {
            val titleTab = provider.getImg("img/map/side_beaconmap.png")
            provider.getWindowRenderer().renderWithTitleTab(
                g, titleTab, title.font,
                position.x, position.y, size.x, size.y,
                title.text, title.colour
            )
        }

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
        fun fromXML(provider: UIProvider, elem: Element): StyledWindowView {
            val size = Point(0, 0)
            elem.getAttributeValue("w")?.let { size.x = it.toInt() }
            elem.getAttributeValue("h")?.let { size.y = it.toInt() }

            val view = StyledWindowView(provider, size)
            view.loadXML(elem)

            view.title = elem.getChild("title")?.let { Label.fromXML(provider, it) }

            return view
        }
    }
}

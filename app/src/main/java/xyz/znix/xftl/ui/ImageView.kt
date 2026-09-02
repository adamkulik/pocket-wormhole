package xyz.znix.xftl.ui

import org.jdom2.Element
import xyz.znix.xftl.f
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.requireAttributeValue
import kotlin.math.max

class ImageView(
    provider: UIProvider,
    var image: Image,
    val offset: IPoint,
    val baseSize: IPoint,
    val tile: Boolean
) : Widget(provider) {
    override val size: Point = Point(baseSize)

    override fun draw(g: Graphics) {
        val xTileCount = if (tile) size.x / baseSize.x else 1
        val yTileCount = if (tile) size.y / baseSize.y else 1

        val tileWidth = size.x.f / xTileCount
        val tileHeight = size.y.f / yTileCount

        for (tileX in 0 until xTileCount) {
            for (tileY in 0 until yTileCount) {
                val x = position.x.f + tileWidth * tileX
                val y = position.y.f + tileHeight * tileY

                image.draw(
                    x, y,
                    x + tileWidth, y + tileHeight,
                    offset.x.f, offset.y.f,
                    offset.x.f + baseSize.x.f, offset.y.f + baseSize.y.f
                )
            }
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
        fun fromXML(provider: UIProvider, elem: Element): ImageView {
            val image = provider.getImg(elem.requireAttributeValue("img"))

            // The top-left pixel in the image to render
            val offset = Point(0, 0)
            elem.getAttributeValue("ix")?.let { offset.x = it.toInt() }
            elem.getAttributeValue("iy")?.let { offset.y = it.toInt() }

            // Negative image positions wrap around
            if (offset.x < 0)
                offset.x += image.width
            if (offset.y < 0)
                offset.y += image.width

            val size = Point(image.imageSize - offset)
            elem.getAttributeValue("w")?.let { size.x = it.toInt() }
            elem.getAttributeValue("h")?.let { size.y = it.toInt() }

            var tile = true
            elem.getAttributeValue("tile")?.let { tile = it.toBoolean() }

            val view = ImageView(provider, image, offset, size, tile)
            view.loadXML(elem)
            return view
        }
    }
}

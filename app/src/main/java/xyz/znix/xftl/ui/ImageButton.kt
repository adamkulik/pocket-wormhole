package xyz.znix.xftl.ui

import org.jdom2.Element
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.Window
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.requireAttributeValue
import xyz.znix.xftl.sys.Input

class ImageButton(
    provider: UIProvider,
    val normalImage: Image,
    val hoverImage: Image,
    val disabledImage: Image
) : Widget(provider) {
    override val size: IPoint get() = normalImage.imageSize

    /**
     * True if the button should show it's disabled version.
     */
    var disabled: Boolean = false

    /**
     * Horizontally flip the image.
     */
    var mirror: Boolean = false

    /**
     * The button on-click listener.
     *
     * This is only used while in-game.
     */
    var clickListener: (() -> Unit)? = null

    private var hovered: Boolean = false

    override fun draw(g: Graphics) {
        val image = when {
            disabled -> disabledImage
            hovered -> hoverImage
            else -> normalImage
        }

        if (mirror) {
            image.draw(
                position.x.f + size.x, position.y.f + size.y,
                position.x.f, position.y.f,

                0f, 0f,
                size.x.f, size.y.f
            )
        } else {
            image.draw(position)
        }

        postDraw(g)
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
        fun fromXML(provider: UIProvider, elem: Element): ImageButton {
            val normal: Image
            val hover: Image
            val disabled: Image

            // Shortcut for _on.png, _off.png, _select2.png
            val select2 = elem.getAttributeValue("select2")

            if (select2 != null) {
                normal = provider.getImg("${select2}_on.png")
                disabled = provider.getImg("${select2}_off.png")
                hover = provider.getImg("${select2}_select2.png")
            } else {
                normal = provider.getImg(elem.requireAttributeValue("normal"))
                hover = elem.getAttributeValue("hover")?.let(provider::getImg) ?: normal
                disabled = elem.getAttributeValue("disabled")?.let(provider::getImg) ?: normal
            }

            val view = ImageButton(provider, normal, hover, disabled)
            view.mirror = elem.getAttributeValue("mirror")?.toBoolean() ?: false
            view.loadXML(elem)
            return view
        }
    }

    private inner class InGameButton(game: InGameState, pos: IPoint) : Button(game, pos, size) {
        override val disabled: Boolean get() = this@ImageButton.disabled

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

            this@ImageButton.hovered = hovered
        }
    }
}

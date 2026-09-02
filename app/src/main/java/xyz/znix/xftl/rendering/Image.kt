package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL11
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import kotlin.math.floor

/**
 * A custom image class, whose functions generally match that of [org.newdawn.slick.Image].
 *
 * See it's JavaDoc for descriptions of these methods.
 */
class Image(
    // This is in pixels, relative to the texture pixel size.
    val textureOffsetX: Int,
    val textureOffsetY: Int,

    val width: Int,
    val height: Int,

    val texture: Texture
) : Renderable {

    val imageSize: IPoint get() = ConstPoint(width, height)

    fun draw() {
        draw(0, 0)
    }

    fun draw(pos: IPoint, filter: Colour = Colour.white) {
        draw(pos.x, pos.y, filter)
    }

    @JvmOverloads
    fun draw(x: Int, y: Int, filter: Colour = Colour.white) {
        draw(x.f, y.f, filter)
    }

    fun draw(x: Int, y: Int, alpha: Float) {
        draw(x.f, y.f, alpha)
    }

    override fun draw(x: Float, y: Float) {
        draw(x, y, 1f)
    }

    override fun draw(x: Float, y: Float, filter: Colour) {
        draw(
            x, y,
            x + width, y + height,

            0f, 0f,
            width.f, height.f,

            1f, filter
        )
    }

    fun draw(x: Float, y: Float, alpha: Float) {
        draw(
            x, y,
            x + width, y + height,

            0f, 0f,
            width.f, height.f,

            alpha, Colour.white
        )
    }

    override fun draw(x: Float, y: Float, width: Float, height: Float) {
        draw(x, y, width, height, Colour.white)
    }

    override fun draw(x: Float, y: Float, width: Float, height: Float, filter: Colour) {
        draw(
            x, y,
            x + width, y + height,

            0f, 0f,
            this.width.f, this.height.f,

            1f, filter
        )
    }

    fun draw(x: Float, y: Float, x2: Float, y2: Float, srcx: Float, srcy: Float, srcx2: Float, srcy2: Float) {
        draw(x, y, x2, y2, srcx, srcy, srcx2, srcy2, 1f, Colour.white)
    }

    fun draw(
        x: Float, y: Float,
        x2: Float, y2: Float,
        srcX1: Float, srcY1: Float,
        srcX2: Float, srcY2: Float,
        alpha: Float,
        filter: Colour
    ) {
        val finalAlpha = alpha * filter.a

        Graphics.internalDrawImage(
            this,
            x, y, x2, y2,
            srcX1, srcY1, srcX2, srcY2,
            filter, finalAlpha
        )
    }

    fun drawAlignedCentred(centreX: Int, centreY: Int, filter: Colour = Colour.white) {
        drawAlignedCentred(centreX, centreY, width.f, height.f, filter)
    }

    fun drawAlignedCentred(centreX: Int, centreY: Int, width: Float, height: Float, filter: Colour = Colour.white) {
        draw(
            centreX - floor(width / 2f), centreY - floor(height / 2f),
            width, height,
            filter
        )
    }

    fun drawSection(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        offsetX: Int = 0,
        offsetY: Int = 0,
        stretchX: Int? = null,
        stretchY: Int? = null,
        colour: Colour? = null
    ) {
        val screenWidth = stretchX ?: width
        val screenHeight = stretchY ?: height

        draw(
            x.f, y.f, x.f + screenWidth, y.f + screenHeight,
            offsetX.f, offsetY.f, offsetX + width.f, offsetY + height.f,
            1f, colour ?: Colour.white
        )
    }

    fun getSubImage(x: Int, y: Int, width: Int, height: Int): Image {
        return Image(
            textureOffsetX + x,
            textureOffsetY + y,
            width, height,
            texture
        )
    }

    companion object {
        // While nearest filtering kinda sucks for rotation, it's what vanilla
        // uses for that - anything else looks terrible because the images have
        // arbitrary colours on transparent regions, so artefacts appear around
        // edges.
        // Linear scaling also looks bad on high-DPI displays, as the game goes
        // from being pixel art to a blurry mess.
        const val DEFAULT_TEXTURE_FILTERING = GL11.GL_NEAREST
    }
}

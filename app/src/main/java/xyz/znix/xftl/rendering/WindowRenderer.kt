package xyz.znix.xftl.rendering

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.Utils
import xyz.znix.xftl.f
import xyz.znix.xftl.game.UIUtils

/**
 * This class can efficiently render dynamically-sized windows by tiling
 * the window_base texture.
 */
class WindowRenderer(
    private val backgroundImage: Image,
    private val outlineImage: Image,
    private val maskImage: Image
) {
    private val baseImage = BulkImageRenderer()

    fun render(x: Int, y: Int, width: Int, height: Int) {
        // Tile in the background area. We have to mask it, since the tiles
        // will clip the corners of the image.
        Utils.drawStenciled(Utils.StencilMode.MASKING, {
            drawOutlineOrMask(maskImage, x, y, width, height)
        }, {
            drawTiled(x, y, width, height, 0, 0)
        })

        // Draw the outline image
        drawOutlineOrMask(outlineImage, x, y, width, height)

        // For testing, this will draw an outline around the specified area.
        // g.colour = Colour(1f, 0f, 0f, 0.2f)
        // g.drawRect(x.f, y.f, width - 1f, height - 1f)
    }

    /**
     * Draw a window, but with a stencil applied when drawing the outline image.
     *
     * This allows the consumer to mask off areas where they're drawing their own
     * stuff, such as title bars.
     *
     * The stencil is run in blocking mode - any pixel you draw into won't be
     * drawn by the outline.
     *
     * This also accepts a second function, which is rendered on the inside of
     * the while, masked by the mask image. This is an easy way to draw stuff
     * inside the window, making sure it doesn't leak out.
     *
     * [bgOffsetX] and [bgOffsetY] shift the background texture, and are intended
     * for windows with scrolling content. They're wrapped to the texture size, so
     * even large values won't leave gaps around the edges of the background.
     */
    fun renderMasked(
        x: Int, y: Int,
        width: Int, height: Int,
        bgOffsetX: Int, bgOffsetY: Int,
        maskFn: () -> Unit,
        drawFn: () -> Unit
    ) {
        Utils.drawStenciled(Utils.StencilMode.MASKING, {
            drawOutlineOrMask(maskImage, x, y, width, height)
        }, {
            drawTiled(x, y, width, height, bgOffsetX, bgOffsetY)
            drawFn()
        })

        // Draw the outline image
        Utils.drawStenciled(Utils.StencilMode.BLOCKING, maskFn) {
            drawOutlineOrMask(outlineImage, x, y, width, height)
        }
    }

    fun renderWithTitleTab(
        g: Graphics, tabImage: Image, font: SILFontLoader,
        x: Int, y: Int, width: Int, height: Int,
        text: String, textColour: Colour = Constants.JUMP_DISABLED_TEXT
    ) {
        val startWidth = 20
        val endWidth = 38
        val textWidth = font.getWidth(text)
        val tabWidth = startWidth + textWidth + endWidth

        renderMasked(x, y, width, height, 0, 0, {
            g.colour = Colour.red // Anything non-transparent will do
            g.fillRect(x.f - GLOW, y.f - GLOW, tabWidth.f, tabImage.height.f)
        }, {})

        UIUtils.drawTab(font, text, tabImage, x.f - GLOW, y.f - GLOW, startWidth.f, endWidth.f)
        font.drawString(x.f - GLOW + startWidth, y + 24f, text, textColour)
    }

    // This draws more than the specified width/height, so it must be masked.
    private fun drawTiled(x: Int, y: Int, width: Int, height: Int, bgOffsetX: Int, bgOffsetY: Int) {
        // These should always be <= 0 to avoid gaps
        val wrappedOffsetX = bgOffsetX.mod(TILE_X) - TILE_X
        val wrappedOffsetY = bgOffsetY.mod(TILE_Y) - TILE_Y

        var tileX = wrappedOffsetX
        while (tileX <= width) {
            var tileY = wrappedOffsetY
            while (tileY <= height) {
                baseImage.pushImage(
                    x.f + tileX,
                    y.f + tileY,

                    x.f + tileX + TILE_X,
                    y.f + tileY + TILE_Y,

                    28f, 29f,
                    28f + TILE_X, 29f + TILE_Y,

                    Colour.white
                )

                tileY += TILE_Y
            }
            tileX += TILE_X
        }
        baseImage.flush(backgroundImage)
    }

    // Note the coordinates for this include the glow!
    private fun drawOutlineOrMask(image: Image, baseX: Int, baseY: Int, baseWidth: Int, baseHeight: Int) {
        // Subtract out the size of the glow, to avoid having to do that later on.
        val x = baseX - GLOW
        val y = baseY - GLOW
        val width = baseWidth + GLOW * 2
        val height = baseHeight + GLOW * 2

        // Define some lines that run CORNER_SIZE away from the edge, since
        // we'll need these repeatedly when drawing.
        val leftLineX = x.f + CORNER_SIZE
        val rightLineX = x.f + width - CORNER_SIZE
        val topLineY = y.f + CORNER_SIZE
        val bottomLineY = y.f + height - CORNER_SIZE

        val rightX = x.f + width
        val bottomY = y.f + height

        // And the same in image-space
        val srcRightLine = image.width - CORNER_SIZE.f
        val srcBottomLine = image.height - CORNER_SIZE.f
        val srcRight = image.width.f
        val srcBottom = image.height.f

        // Top-left
        image.draw(
            x.f, y.f,
            leftLineX, topLineY,
            0f, 0f, CORNER_SIZE.f, CORNER_SIZE.f
        )

        // Top-right
        image.draw(
            rightLineX, y.f,
            rightX, topLineY,
            srcRightLine, 0f, srcRight, CORNER_SIZE.f
        )

        // Bottom-left
        image.draw(
            x.f, bottomLineY,
            leftLineX, bottomY,
            0f, srcBottomLine, CORNER_SIZE.f, srcBottom
        )

        // Bottom-right
        image.draw(
            rightLineX, bottomLineY,
            rightX, bottomY,
            srcRightLine, srcBottomLine, srcRight, srcBottom
        )

        // Draw the lines connecting the corners

        // Left side
        image.draw(
            x.f, topLineY,
            leftLineX, bottomLineY,
            0f, CORNER_SIZE.f, CORNER_SIZE.f, srcBottomLine
        )

        // Right side
        image.draw(
            rightLineX, topLineY,
            rightX, bottomLineY,
            srcRightLine, CORNER_SIZE.f, srcRight, srcBottomLine
        )

        // Top side
        image.draw(
            leftLineX, y.f,
            rightLineX, topLineY,
            CORNER_SIZE.f, 0f, srcRightLine, CORNER_SIZE.f
        )

        // Bottom side
        image.draw(
            leftLineX, bottomLineY,
            rightLineX, bottomY,
            CORNER_SIZE.f, srcBottomLine, srcRightLine, srcBottom
        )

        // In case we're drawing the mask, fill in the centre too.
        image.draw(
            leftLineX, topLineY,
            rightLineX, bottomLineY,
            CORNER_SIZE.f, CORNER_SIZE.f, srcRightLine, srcBottomLine
        )
    }

    companion object {
        private const val GLOW = 7

        private const val TILE_X = 10
        private const val TILE_Y = 17

        // This is a little larger than needed, but it doesn't hurt in case
        // there's a subtle change in the glow closer.
        private const val CORNER_SIZE = 28
    }
}

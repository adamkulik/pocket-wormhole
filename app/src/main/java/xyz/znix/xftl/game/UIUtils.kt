package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import kotlin.math.roundToInt

object UIUtils {
    /**
     * Draws a text tab. In the images these are thin tabs that get expanded to correctly fit the localised
     * string at runtime. The image consists of what you might call three regions:
     *
     * - The start region, drawn before the text
     * - The text region, which is stretched to match the width of the text (which is drawn onto it)
     * - The end region, which is drawn after the text region
     *
     * @return The end position of the tab
     */
    fun drawTab(
        font: SILFontLoader, text: String, img: Image,
        x: Float, y: Float, startWidth: Float, endWidth: Float
    ): Float {
        val textWidth = font.getWidth(text).f
        val scrBase = y + img.height

        // Screen X coordinates
        val sx1 = x + startWidth // Between the start and text areas
        val sx2 = sx1 + textWidth // Between the text and end areas
        val sx3 = sx2 + endWidth // The end X position

        img.draw(x, y, sx1, scrBase, 0f, 0f, startWidth, img.height.f)
        img.draw(sx1, y, sx2, scrBase, startWidth, 0f, img.width.f - endWidth, img.height.f)
        img.draw(sx2, y, sx3, scrBase, img.width.f - endWidth, 0f, img.width.f, img.height.f)

        return startWidth + textWidth + endWidth
    }

    /**
     * Draw a string with a glowing background, like what [WarningFlasher] does.
     */
    fun drawStringWithGlow(
        game: InGameState,
        font: SILFontLoader,
        text: String,
        x: Int, y: Int,
        colour: GlowColour,
        alpha: Float
    ) {
        val spaceWidth = font.getWidth(" ")

        // Account for the less-glowing bits of each side of the glow
        val glowMargin = 1

        val glowImage = game.getImg("img/warnings/backglow_warning_${colour.bgName}.png")

        val fontColour = Colour(colour.colour)
        fontColour.a = alpha

        // Draw on the text, with each word glowing separately.
        var mutX = x
        for (word in text.split(' ')) {
            val wordWidth = font.getWidth(word)
            font.drawString(mutX.f, y.f, word, fontColour)
            drawGlow(glowImage, alpha, mutX - glowMargin, y, wordWidth + glowMargin * 2)
            mutX += wordWidth

            // The space doesn't glow
            mutX += spaceWidth
        }
    }

    private fun drawGlow(image: Image, alpha: Float, x: Int, y: Int, width: Int) {
        // The glow image is huge, we have to scale it down.
        val scale = 1 / 9f

        // How wide the ends of the glow image are, in the glow image's
        // coordinate system.
        val endWidthImg = 100f

        val endWidthScreen = endWidthImg * scale

        val glowHeight = image.height * scale

        val textMiddle = y - 6
        val glowTopY = textMiddle - glowHeight / 2
        val glowBottomY = glowTopY + glowHeight

        // Left side
        image.draw(
            x.f, glowTopY, x.f + endWidthScreen, glowBottomY,
            0f, 0f, endWidthImg, image.height.f,
            alpha, Colour.white
        )

        // Middle
        image.draw(
            x + endWidthScreen, glowTopY, x.f + width - endWidthScreen, glowBottomY,
            100f, 0f, 200f, image.height.f,
            alpha, Colour.white
        )

        // Right side
        image.draw(
            x.f + width - endWidthScreen, glowTopY, x.f + width, glowBottomY,
            image.width.f - endWidthImg, 0f, image.width.f, image.height.f,
            alpha, Colour.white
        )
    }

    fun drawDebugBar(
        g: Graphics,
        x: Int, y: Int,
        width: Int, height: Int,
        progress: Float,
        outline: Colour, fill: Colour
    ) {
        val innerHeight = height - 1 // 1px of margin
        val fillHeight = (innerHeight * progress).roundToInt()

        g.colour = outline
        g.drawRect(
            x.f, y.f,
            width.f, height.f
        )

        g.colour = fill
        g.fillRect(
            x + 1f, y.f + height - fillHeight,
            width - 1f, fillHeight.f
        )
    }

    /**
     * Convert a fixed-point number (in multiples of 0.01) to a string,
     * removing any unnecessary trailing point or zeros.
     *
     * This matches the formatting used in the system level text.
     */
    fun formatStringFTL(hundredths: Int): String {
        val builder = StringBuilder()

        // Deal with the integer portion
        builder.append(hundredths / 100)

        var remaining = hundredths % 100

        if (remaining == 0) {
            return builder.toString()
        }

        // The 10ths
        builder.append('.')
        builder.append(remaining / 10)
        remaining %= 10
        if (remaining == 0) {
            return builder.toString()
        }

        // The 100ths
        builder.append(remaining)
        return builder.toString()
    }

    fun formatFloat(value: Float): String {
        return formatStringFTL((value * 100).roundToInt())
    }
}

/**
 * A colour that text with a background glow can use.
 *
 * The glow is based of an image, so there's only a limited selection.
 */
enum class GlowColour(val bgName: String, val colour: Colour) {
    RED("red", Constants.WARNING_COLOUR_RED),
    WHITE("white", Constants.WARNING_COLOUR_WHITE),
    GREEN("green", Constants.WARNING_COLOUR_RED), // TODO set the right colour
}

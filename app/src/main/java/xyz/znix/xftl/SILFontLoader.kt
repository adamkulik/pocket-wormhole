package xyz.znix.xftl

import org.lwjgl.opengl.GL11
import org.newdawn.slick.opengl.ImageData
import xyz.znix.xftl.rendering.BulkImageRenderer
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.TextureLoader
import xyz.znix.xftl.sys.ResourceContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SILFontLoader {
    private val chars: Map<Char, Charinfo>
    private val unknownChar: Charinfo
    private val picture: Image
    private val renderer: BulkImageRenderer

    /**
     * The vertical spacing between any two lines of text.
     */
    val lineSpacing: Int

    /**
     * How far the visual top of the line is above the baseline.
     *
     * This is set in font-data-override.xml
     */
    val baselineToTop: Int

    /**
     * An offset set in font-data-override.xml for HL2, that moves the baseline
     * down with a positive value.
     */
    val trueBaselineOffset: Int

    var scale: Float = 1f

    constructor(context: ResourceContext, df: Datafile, file: FTLFile) : this(
        context,
        FontOverrideData.fonts[file.name],
        df.read(file)
    )

    constructor(context: ResourceContext, override: FontOverrideData.FontInfo?, data: ByteArray) {
        val seeking = SeekingInputStream(data)
        val bytes = DataInputStream(seeking)

        check(bytes.readNBytes(4).contentEquals("FONT".toByteArray()))

        val version = bytes.read()
        lineSpacing = bytes.read()
        val fileBaseline = bytes.read()
        baselineToTop = override?.lineTop ?: fileBaseline
        trueBaselineOffset = override?.baselineOffset ?: 0

        // Pad
        bytes.read()

        val charinfoOffset = bytes.readInt()

        val charinfoCount = bytes.readUnsignedShort()
        val charinfoSize = bytes.readUnsignedShort()

        chars = HashMap(charinfoCount)

        val textureOffset = bytes.readInt()
        @Suppress("UNUSED_VARIABLE") val textureSize = bytes.readInt()

        check(version == 1)

        seeking.position = charinfoOffset

        for (i in 1..charinfoCount) {
            val ch = bytes.readInt()

            val x = bytes.readShort().toInt()
            val y = bytes.readShort().toInt()

            val w = bytes.readUnsignedByte()
            val h = bytes.readUnsignedByte()

            val accent = bytes.readByte().toInt()

            // Pad
            bytes.read()

            val prekern = bytes.readShort().toInt()
            val postkern = bytes.readShort().toInt()

            chars[ch.toChar()] = Charinfo(ch.toChar(), x, y, w, h, accent, prekern / 256f, postkern / 256f)
        }

        check(seeking.position == charinfoOffset + charinfoSize * charinfoCount)

        // Read the texture

        seeking.position = textureOffset
        val magic = bytes.readNBytes(4)
        check(magic.contentEquals("TEX\u000a".toByteArray()))

        val texVersion = bytes.read()
        check(texVersion == 2)

        val format = bytes.read()
        check(format == 64)

        val mipmaps = bytes.read()
        check(mipmaps == 0)

        val opaqueBitmap = bytes.read()
        check(opaqueBitmap == 0)

        val texWidth = bytes.readUnsignedShort()
        val texHeight = bytes.readUnsignedShort()

        // Throw away four bytes
        bytes.readInt()

        val pixelOffsets = bytes.readInt()
        val pixelSize = bytes.readInt()

        @Suppress("UNUSED_VARIABLE") val bitmapOffset = bytes.readInt()
        @Suppress("UNUSED_VARIABLE") val bitmapSize = bytes.readInt()

        seeking.position = pixelOffsets + textureOffset

        val data = ByteArray(pixelSize)
        bytes.read(data)

        val img = MonochromeImage(texWidth, texHeight, data)

        picture = TextureLoader.loadImage(context, img)
        renderer = BulkImageRenderer()

        // Enlarged fonts are supposed to be pixelated.
        renderer.imageFiltering = GL11.GL_NEAREST

        // Replace unknown characters with question marks
        unknownChar = chars.getValue('?')
    }

    /**
     * Create a new [SILFontLoader] representing the same font as the supplied instance. This shares
     * the image and character data (thus making clones like this is cheap), but the scale (and any other
     * later adjustable properties) are kept separate.
     */
    constructor(other: SILFontLoader) {
        chars = other.chars
        unknownChar = other.unknownChar
        picture = other.picture
        lineSpacing = other.lineSpacing
        baselineToTop = other.baselineToTop
        trueBaselineOffset = other.trueBaselineOffset
        renderer = other.renderer
    }

    fun drawString(x: Float, y: Float, text: String, col: Colour) {
        drawStringTruncated(x, y, Float.MAX_VALUE, text, col)
    }

    fun drawStringTruncated(x: Float, y: Float, width: Float, text: String, col: Colour) {
        var next = x
        for (ch in text) {
            val info = chars[ch] ?: unknownChar

            val cy = y + (scale * -info.ascent).roundToInt()
            next += (info.prekern * scale).roundToInt()
            val cx = next.roundToInt()

            // Check if this character is going to overflow the allowed area
            val charWidth = min((width + x - cx) / scale, info.w.f)

            renderer.pushImage(
                cx.f, cy,
                cx.f + charWidth * scale, cy + info.h * scale,
                info.x.f, info.y.f,
                info.x + charWidth, (info.y + info.h).f, col
            )
            next += ((info.w + info.postkern) * scale).roundToInt()

            if (next - x > width)
                return
        }

        renderer.flush(picture)
    }

    /**
     * Draw a string, with characters from another font placed at the same position.
     *
     * This is specifically intended for the HL2 font, with the HL1 font being
     * passed in. This is used for the damage/miss/resist text, which has its
     * background filled in.
     */
    fun drawStringPaired(bgFont: SILFontLoader, x: Float, y: Float, text: String, fg: Colour, bg: Colour) {
        // If we don't round off the Y, then at values about half way between
        // integers we can end up drawing the background a pixel below
        // the foreground.
        val roundedY = y.roundToInt()

        var next = x
        for (ch in text) {
            val info = chars[ch] ?: unknownChar

            val cy = roundedY + (scale * -info.ascent).roundToInt()
            next += (info.prekern * scale).roundToInt()
            val cx = next.roundToInt()

            // Push the image for the main font
            renderer.pushImage(
                cx.f, cy.f,
                cx.f + info.w * scale, cy + info.h * scale,
                info.x.f, info.y.f,
                info.x.f + info.w, (info.y + info.h).f, fg
            )

            // Push the image for the background font
            val bgInfo = bgFont.chars[ch] ?: bgFont.unknownChar
            val bgCX = cx + 1 * scale // Shift 1px for hl1/hl2 alignment
            val bgCY = cy + 1 * scale
            bgFont.renderer.pushImage(
                bgCX, bgCY,
                bgCX + bgInfo.w * scale, bgCY + bgInfo.h * scale,
                bgInfo.x.f, bgInfo.y.f,
                bgInfo.x.f + bgInfo.w, (bgInfo.y + bgInfo.h).f, bg
            )

            next += ((info.w + info.postkern) * scale).roundToInt()
        }

        bgFont.renderer.flush(bgFont.picture)
        renderer.flush(picture)
    }

    fun drawStringCentred(x: Float, y: Float, width: Float, text: String, col: Colour) {
        val textX = x + (width - getWidth(text)) / 2
        drawString(textX, y, text, col)
    }

    /**
     * Split a string into multiple lines, and draw it centred around a given Y value ([centreY]).
     *
     * [centreY] is the Y that, if the string is a single line, it will be drawn at.
     */
    fun drawStringCentredVertical(x: Int, centreY: Int, width: Int, spacing: Int, text: String, col: Colour) {
        val lines = wrapString(text, width)

        var y = centreY - (lines.size - 1) * spacing / 2

        for (line in lines) {
            drawStringCentred(x.f, y.f, width.f, line, col)
            y += spacing
        }
    }

    fun drawStringLeftAligned(x: Float, y: Float, text: String, colour: Colour) {
        drawString(x - getWidth(text), y, text, colour)
    }

    fun getWidth(str: String): Int {
        var maxLine = 0
        var current = 0
        var next = 0
        for (ch in str) {
            // If we hit a newline, keep track of this line if it's
            // the longest one we've found. Then reset the width
            // for the next line's length.
            if (ch == '\n') {
                maxLine = max(maxLine, current)
                current = 0
                next = 0
                continue
            }

            val info = chars[ch] ?: unknownChar
            next += (info.prekern * scale).roundToInt()

            current = next + (info.w * scale).roundToInt()
            next += ((info.w + info.postkern) * scale).roundToInt()
        }
        return max(current, maxLine)
    }

    fun supportsCharacter(c: Char): Boolean {
        return chars.containsKey(c)
    }

    fun wrapString(string: String, maxWidth: Int): List<String> {
        val lines = ArrayList<String>()

        // We have to round the prekern and width+postkern separately, since that's how we render it.
        val spaceInfo = chars.getValue(' ')
        val spaceWidth = (spaceInfo.prekern * scale).roundToInt() +
                ((spaceInfo.w + spaceInfo.postkern) * scale).roundToInt()

        var currentWidth = 0
        val line = StringBuilder()

        fun addWord(word: String) {
            if (line.isNotEmpty()) {
                line.append(' ')
                currentWidth += spaceWidth
            }

            var nextWidth = 0
            for (ch in word) {
                val info = chars[ch] ?: unknownChar
                nextWidth += (info.prekern * scale).roundToInt()

                // Always include the postkern, since we'll be writing
                // another word after this. And if we wrap at slightly
                // the wrong time by the distance of a postkern, who cares.
                val postKern = info.postkern
                nextWidth += ((info.w + postKern) * scale).roundToInt()
            }

            // If this word would make the line to long, split it here.
            // The exception is that if the line is currently empty, this word
            // won't ever fit - so don't leave a blank line before it.
            if (currentWidth + nextWidth > maxWidth && line.isNotEmpty()) {
                lines.add(line.toString())
                line.clear()
                currentWidth = 0
            }

            line.append(word)
            currentWidth += nextWidth
        }

        for ((lineNum, unwrappedLine) in string.split('\n').withIndex()) {
            if (lineNum != 0) {
                lines.add(line.toString())
                line.clear()
                currentWidth = 0
            }

            for (word in unwrappedLine.split(' ', '\t')) {
                addWord(word)
            }
        }

        lines.add(line.toString())

        return lines
    }

    @Suppress("unused")
    private class Charinfo(
        val ch: Char, val x: Int, val y: Int, val w: Int, val h: Int, val ascent: Int,
        val prekern: Float, val postkern: Float
    )

    private class SeekingInputStream(ba: ByteArray) : ByteArrayInputStream(ba) {
        var position: Int
            get() = pos
            set(value) {
                pos = value
            }
    }

    private class MonochromeImage(val w: Int, val h: Int, load: ByteArray) : ImageData {
        val data: ByteBuffer = ByteBuffer.allocateDirect(load.size * 4)

        init {
            // Image is stored as RGBA, and our font should be 0xfff with the alpha loaded
            // from the file. This way we can easily control the colour of the rendered
            // text by applying a filter.
            for (element in load) {
                // Java uses two's complement, so -1 is 0xff
                for (j in 1..3)
                    data.put(-1)

                // Copy over the alpha
                data.put(element)
            }

            data.position(0)
        }

        override fun getHeight(): Int = h

        override fun getTexWidth(): Int = w

        override fun getDepth(): Int = 8 * 4

        override fun getImageBufferData(): ByteBuffer = data

        override fun getWidth(): Int = w

        override fun getTexHeight(): Int = h
    }
}

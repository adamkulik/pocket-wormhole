package xyz.znix.xftl.ui

import org.jdom2.Element
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.requireAttributeValue
import xyz.znix.xftl.requireAttributeValueInt
import kotlin.math.max
import kotlin.math.roundToInt

class Label(provider: UIProvider, text: String, font: SILFontLoader) : Widget(provider) {
    var text: String = text
        set(value) {
            field = value
            updateText()
        }
    var font: SILFontLoader = font
        set(value) {
            field = value
            updateText()
        }

    var colour: Colour = Colour.black

    override val size = Point(0, 0)
    private lateinit var lines: List<String>
    private var textWidth: Int = 1

    init {
        updateText()
    }

    override fun draw(g: Graphics) {
        val y = position.y + font.baselineToTop * font.scale

        for ((id, line) in lines.withIndex()) {
            val lineWidth = font.getWidth(line)
            val x = position.x + (size.x - lineWidth) / 2
            val lineY = y + font.lineSpacing * font.scale * id
            font.drawString(x.f, lineY, line, colour)
        }

        postDraw(g)
    }

    private fun updateText() {
        lines = text.split("\n")

        textWidth = lines.maxOf { font.getWidth(it) }

        val oneLineHeight = ((font.baselineToTop + font.trueBaselineOffset) * font.scale).roundToInt()
        val height = oneLineHeight + (lines.size - 1) * (font.lineSpacing * font.scale).roundToInt()

        // We can only increase the size, as otherwise we'd shrink down even
        // if we're stretched out in XML by attaching to both sides of
        // our parent widget.
        attemptStretch(textWidth, height)
    }

    override fun attemptStretch(availableWidth: Int, availableHeight: Int) {
        size.x = max(size.x, availableWidth)
        size.y = max(size.y, availableHeight)
    }

    companion object {
        private const val NO_LOC_PREFIX = "noloc:"

        fun fromXML(provider: UIProvider, elem: Element): Label {
            val rawText = elem.requireAttributeValue("text")
            val text = when {
                rawText.startsWith(NO_LOC_PREFIX) -> rawText.removePrefix(NO_LOC_PREFIX)
                else -> provider.translate(rawText)
                    ?: throw IllegalUISpecException("Invalid label translation key '$rawText'")
            }

            val fontName = elem.requireAttributeValue("font")
            val fontScale = elem.requireAttributeValueInt("scale")

            val font = provider.getFont(fontName)
            font.scale = fontScale.f

            val label = Label(provider, text, font)
            label.loadXML(elem)

            label.colour = SpecDeserialiser.parseColour(elem, "colour")

            return label
        }
    }
}

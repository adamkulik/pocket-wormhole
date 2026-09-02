package xyz.znix.xftl.devutil

import xyz.znix.xftl.*
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.ShaderProgramme
import xyz.znix.xftl.sys.BasicGame
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.ResourceContext
import kotlin.math.max
import kotlin.math.min

object FontPalette {
    @JvmStatic
    fun main(args: Array<String>) {
        val vanilla = VanillaDatafile.createWithDefaultPath()
        val df = Datafile(vanilla, emptyList()) // No mods
        Utils.startSlick(GameImpl(df))
    }

    val FONT_NAMES = listOf(
        "c&c",
        "c&cnew",
        "HL1",
        "HL2",
        "JustinFont8",
        "JustinFont10",
        "JustinFont11Bold",
        "JustinFont12Bold",
        "num_font"
    )

    val DEFAULT_TEXT = "The quick brown fox jumps over the lazy dog"
    val HELP_MSG = "Press F1 to clear the string, F2 for sample, F3 and arrow keys to view rawfonts, F4 for" +
            " baseline, F5 to reload the override XML, type to edit, and up/down to scale the font"

    val BASELINE_COLOUR = Colour(255, 0, 0, 128)
    val OFFSET_COLOUR = Colour(0, 255, 0, 128)

    private class GameImpl(val df: Datafile) : BasicGame("Font Palette") {
        private val fonts = HashMap<String, SILFontLoader>()
        private val pictures = HashMap<String, Image>()
        private var text = DEFAULT_TEXT

        val resourceContext = ResourceContext()

        // Store the utility font separately,
        private lateinit var utilFont: SILFontLoader

        private var rawFontMode = false
        private var rawFontIdx: Int = 0
        private var fontSize: Int = 1

        private var baseline = false

        private lateinit var g: Graphics

        override fun update(container: GameContainer, delta: Float) {}

        override fun render(container: GameContainer, slickG: Graphics) {
            ShaderProgramme.SHADER_SCREEN_SIZE.set(container.width, container.height)

            g.clear(Colour.white)

            utilFont.drawString(50f, 20f, HELP_MSG, Colour.black)

            if (rawFontMode) {
                val name = FONT_NAMES[rawFontIdx]
                utilFont.drawString(50f, 35f, "Selected font: (idx $rawFontIdx) $name", Colour.black)
                pictures[name]!!.draw(50f, 50f, Colour.black)
                return
            }

            val drawStr = when {
                text.endsWith(' ') || text.startsWith(' ') -> "'$text'"
                else -> text
            }

            for ((i, name) in FONT_NAMES.withIndex()) {
                val fnt = fonts[name]!!
                val y = 25f + (10 + 15 * fontSize) * (i + 1)
                utilFont.drawString(20f, y, name, Colour.black)

                fnt.scale = fontSize.toFloat()

                fnt.drawString(150f, y, drawStr, Colour.black)

                if (baseline) {
                    g.colour = BASELINE_COLOUR
                    g.drawLine(150f, y, 150f + fnt.getWidth(drawStr), y)

                    g.colour = OFFSET_COLOUR
                    val topY = y - fnt.baselineToTop * fontSize
                    g.drawLine(150f, topY, 150f + fnt.getWidth(drawStr), topY)

                    if (fnt.trueBaselineOffset != 0) {
                        val baseY = y + fnt.trueBaselineOffset * fontSize
                        g.drawLine(150f, baseY, 150f + fnt.getWidth(drawStr), baseY)
                    }
                }
            }
        }

        override fun init(container: GameContainer) {
            g = Graphics()
            g.markCurrentImageTransformSource()

            // Hack here so we don't have to make it public, won't be used anywhere else
            val field = SILFontLoader::class.java.getDeclaredField("picture")
            field.isAccessible = true

            loadFonts()

            for ((name, font) in fonts.entries) {
                pictures[name] = field.get(font) as Image
            }

            utilFont = SILFontLoader(fonts["JustinFont8"]!!)
        }

        override fun shutdown() {
            resourceContext.freeAll()
        }

        private fun loadFonts() {
            for (name in FONT_NAMES) {
                val font = SILFontLoader(resourceContext, df, df["fonts/$name.font"])
                fonts[name] = font
            }
        }

        override fun keyPressed(key: Int, c: Char) {
            var handled = true
            when (key) {
                Input.KEY_BACK -> {
                    if (text.isNotEmpty())
                        text = text.substring(0, text.length - 1)
                }

                Input.KEY_F1 -> {
                    text = ""
                    rawFontMode = false
                }

                Input.KEY_F2 -> {
                    text = DEFAULT_TEXT
                    rawFontMode = false
                }

                Input.KEY_F3 -> rawFontMode = !rawFontMode
                Input.KEY_F4 -> baseline = !baseline
                Input.KEY_F5 -> {
                    FontOverrideData.debugReload()
                    loadFonts()
                }

                Input.KEY_UP -> fontSize = min(fontSize + 1, 5)
                Input.KEY_DOWN -> fontSize = max(fontSize - 1, 1)
                else -> handled = false
            }

            if (handled) return

            if (rawFontMode) {
                when (key) {
                    Input.KEY_LEFT -> rawFontIdx -= 1
                    Input.KEY_RIGHT -> rawFontIdx += 1
                }
                rawFontIdx = rawFontIdx.coerceAtLeast(0)
                rawFontIdx = rawFontIdx.coerceAtMost(fonts.size - 1)
                return
            }

            val isLatinChar = c in 'a'..'z' || c in 'A'..'Z'
            val isNum = c in '0'..'9'
            val isSimplePunc = ", .?!;:+-".contains(c)

            if (isLatinChar || isNum || isSimplePunc) {
                text += c
            }
        }
    }
}

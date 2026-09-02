package xyz.znix.xftl.ui

import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.WindowRenderer

/**
 * Represents an object that is responsible in some way for rendering UIs,
 * and can provide the necessary data for them (such as loading fonts
 * and images).
 */
interface UIProvider {
    /**
     * Returns a new font instance for the font file at the given path.
     */
    fun getFont(name: String): SILFontLoader

    fun getImg(path: String): Image

    fun translate(key: String): String?

    fun getDebugOutlineColour(widget: Widget): Colour?

    fun getWindowRenderer(): WindowRenderer
}

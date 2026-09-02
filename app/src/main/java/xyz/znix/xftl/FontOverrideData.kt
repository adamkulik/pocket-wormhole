package xyz.znix.xftl

import xyz.znix.xftl.sys.XftlResources

import org.jdom2.input.SAXBuilder

object FontOverrideData {
    val fonts: Map<String, FontInfo>

    init {
        fonts = load()
    }

    /**
     * FOR DEVELOPMENT ONLY!
     *
     * This reloads the XML file, making it easy to change while the game is running.
     */
    fun debugReload() {
        val newData = try {
            load()
        } catch (ex: Exception) {
            print("Failed to load new font data!")
            ex.printStackTrace()
            return
        }

        val hashMap = fonts as HashMap
        hashMap.clear()
        hashMap.putAll(newData)
    }

    private fun load(): Map<String, FontInfo> {
        val newFonts = HashMap<String, FontInfo>()

        @Suppress("VulnerableCodeUsages") // we set expandEntities
        val builder = com.pocketwormhole.android.SafeXML.builder()
        builder.expandEntities = false
        val doc = builder.build(XftlResources.open("assets/font-data-override.xml")!!)

        require(doc.rootElement.name == "fontdata")

        for (elem in doc.rootElement.getChildren("font")) {
            val name = elem.requireAttributeValue("name")
            val lineTop = elem.getChild("lineTop")?.requireAttributeValueInt("value")
            val baselineOffset = elem.getChild("lineBottom")?.requireAttributeValueInt("value")
            newFonts[name] = FontInfo(lineTop, baselineOffset)
        }

        return newFonts
    }

    class FontInfo(val lineTop: Int?, val baselineOffset: Int?)
}

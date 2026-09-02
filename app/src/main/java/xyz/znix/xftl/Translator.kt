package xyz.znix.xftl

import xyz.znix.xftl.sys.XftlResources

import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.input.SAXBuilder

class Translator {
    val translations: Map<String, String>

    constructor(df: Datafile, lang: String) {
        translations = HashMap()
        fun load(name: String) = parseFile(name, df, translations)

        when (lang) {
            "en" -> {
                load("data/text_achievements.xml")
                load("data/text_blueprints.xml")
                load("data/text_events.xml")
                load("data/text_misc.xml")
                load("data/text_sectorname.xml")
                load("data/text_tooltips.xml")
                load("data/text_tutorial.xml")
            }

            "zh" -> load("data/text-zh-Hans.xml")
            else -> load("data/text-$lang.xml")
        }

        // Put a when block here if/when our strings are translated.
        parseEmbedded("assets/lang/en.xml", translations)
    }

    /**
     * Used for the select-a-datafile screen, as we don't have a main
     * datafile to use at that point.
     */
    constructor(embeddedPath: String) {
        translations = HashMap()
        parseEmbedded(embeddedPath, translations)
    }

    private fun parseFile(filename: String, df: Datafile, tl: HashMap<String, String>) {
        val doc = df.parseXML(df[filename])
        parseDoc(filename, doc, tl)
    }

    private fun parseEmbedded(path: String, tl: HashMap<String, String>) {
        val builder = com.pocketwormhole.android.SafeXML.builder()
        builder.expandEntities = false
        val doc = builder.build(XftlResources.open(path)!!)

        parseDoc(path, doc, tl)
    }

    private fun parseDoc(fileName: String, doc: Document, tl: HashMap<String, String>) {
        for (elem in doc.rootElement.children) {
            if (elem.name != "text") {
                println("[WARN] Found invalid element '${elem.name}' in translation file '$fileName'")
                continue
            }

            tl[elem.getAttributeValue("name")] = elem.textTrim.replace("\\n", "\n")
        }
    }

    operator fun get(key: String): String {
        // Make it really obvious if a translation is missing
        return translations[key] ?: "could not find: $key"
    }

    /**
     * Convenience helper for [GameText.get].
     */
    operator fun get(text: GameText): String {
        return text.get(this)
    }
}

/**
 * Represents a string parsed from the XML files that may or may not be localised.
 */
class GameText private constructor(private val literal: String?, private val key: String?) {

    fun get(translator: Translator): String {
        if (literal != null) {
            return literal
        }

        return translator[key!!]
    }

    // These are for use in automated tests only!
    fun getLiteralForTesting(): String? = literal
    fun getKeyForTesting(): String? = key

    companion object {
        fun parse(elem: Element): GameText {
            val id: String? = elem.getAttributeValue("id")
            if (id != null) {
                return GameText(null, id)
            }

            return GameText(elem.textTrim, null)
        }

        fun localised(key: String): GameText {
            return GameText(null, key)
        }

        fun literal(string: String): GameText {
            return GameText(string, null)
        }
    }
}

fun Element.getGameTextChild(name: String): GameText? {
    val elem = this.getChild(name) ?: return null
    return GameText.parse(elem)
}

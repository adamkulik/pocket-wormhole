package xyz.znix.xftl.ui

import xyz.znix.xftl.sys.XftlResources

import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.input.SAXBuilder
import xyz.znix.xftl.Constants
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.requireAttributeValue
import java.io.InputStream

/**
 * Loads an XML-based widget tree, and deserialises it into a working UI.
 */
class SpecDeserialiser(private val provider: UIProvider) {
    fun load(name: String): LoadedUI {
        val path = "assets/ui/$name.xml"
        return XftlResources.open(path)!!.use { load(it) }
    }

    fun load(reader: InputStream): LoadedUI {
        val builder = com.pocketwormhole.android.SafeXML.builder()
        builder.expandEntities = false
        return load(builder.build(reader))
    }

    fun load(doc: Document): LoadedUI {
        val root = doc.rootElement
        if (root.name != "ui")
            throw IllegalUISpecException("Invalid root element name")

        val styleElem = root.getChild("style")
        val styleSheet = styleElem?.let { StyleSheet(styleElem) }
        styleSheet?.injectAttributes(root)

        val widgets = loadWidgets(root)
        if (widgets.isEmpty())
            throw IllegalUISpecException("No root widget!")

        for (widget in widgets) {
            widget.init(null)
        }

        val containers = widgets.map { WidgetContainer(it) }

        for (container in containers) {
            container.updateLayout()
        }

        return LoadedUI(containers.first(), emptyMap())
    }

    private fun loadWidgets(parent: Element): List<Widget> {
        return parent.getChildren("widget").map { loadWidget(it) }
    }

    private fun loadWidget(elem: Element): Widget {
        val widget = when (val type = elem.requireAttributeValue("type")) {
            "label" -> Label.fromXML(provider, elem)
            "vbox" -> BoxContainer.fromXML(provider, elem, true)
            "hbox" -> BoxContainer.fromXML(provider, elem, false)
            "free" -> FreeContainer.fromXML(provider, elem)
            "image" -> ImageView.fromXML(provider, elem)
            "button" -> UIKitButton.fromXML(provider, elem)
            "image-button" -> ImageButton.fromXML(provider, elem)
            "window" -> StyledWindowView.fromXML(provider, elem)
            "slider" -> Slider.fromXML(provider, elem)
            else -> throw IllegalUISpecException("Unknown widget type '$type'")
        }

        widget.children += loadWidgets(elem)

        return widget
    }

    class LoadedUI(val mainWidget: WidgetContainer, val extraRoots: Map<String, WidgetContainer>)

    companion object {
        fun parseColour(elem: Element, attrName: String): Colour {
            return parseColour(elem.requireAttributeValue(attrName))
        }

        fun parseColour(elem: Element, attrName: String, defaultColour: Colour): Colour {
            val value = elem.getAttributeValue(attrName) ?: return defaultColour
            return parseColour(value)
        }

        fun parseColour(value: String): Colour {
            // TODO parse CSS hex codes

            return when (value) {
                "text-dark" -> Constants.JUMP_DISABLED_TEXT
                "glow-white" -> Constants.SECTOR_CUTOUT_TEXT
                else -> throw IllegalUISpecException("Unknown colour name '$value'")
            }
        }
    }
}

class IllegalUISpecException(message: String) : Exception(message)

package xyz.znix.xftl.bxml

import org.jdom2.Document
import org.jdom2.Element
import java.io.DataInputStream
import java.io.InputStream

class BXMLReader private constructor(input: DataInputStream) {
    private val input = BXMLBufferedInputStream(input)
    private val strings = ArrayList<String>()

    private fun readElement(): Element {
        val elem = UnsafeXML.createElement(readString())

        // Write the attributes
        val attrCount = input.readVarInt()
        for (i in 0 until attrCount) {
            val name = readString()
            val value = readString()

            // Create the Attribute object ourselves, since the
            // Element.setAttribute(string, string) function first checks
            // if there's already an attribute with the same name, which
            // is slow (11% of the readElement time, when this was written).
            UnsafeXML.addAttribute(elem, UnsafeXML.createAttribute(name, value))
        }

        // Read the contents, notably including children.
        while (true) {
            when (val typeId = input.readByte()) {
                BXMLUtils.ID_EOF -> break

                BXMLUtils.ID_TEXT -> {
                    // This doesn't reconstruct a CDATA object, instead it'll
                    // probably use escaping, but we don't really care.
                    // It'll be uglier, but if a human is reading something
                    // that's gone to BXML and back then something has gone
                    // horribly wrong.
                    elem.addContent(readString())
                }

                BXMLUtils.ID_ELEM -> {
                    // Recursively read the child elements
                    elem.addContent(readElement())
                }

                else -> error("Invalid BXML type ID: $typeId")
            }
        }

        return elem
    }

    private fun readString(): String {
        val id = input.readVarInt()

        // 0 indicates a literal, anything else is a previous string
        if (id != 0) {
            // IDs are 1-indexed, to disambiguate the first string and literals.
            return strings[id - 1]
        }

        val length = input.readVarInt()
        val bytes = input.readNBytes(length)
        val string = String(bytes, Charsets.UTF_8)
        strings.add(string)
        return string
    }

    companion object {
        @JvmStatic
        fun read(inputStream: InputStream): Document {
            val rootElem = DataInputStream(inputStream).use {
                val reader = BXMLReader(it)
                reader.readElement()
            }
            return Document(rootElem)
        }
    }
}

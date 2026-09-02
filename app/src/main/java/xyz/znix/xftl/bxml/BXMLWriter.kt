package xyz.znix.xftl.bxml

import org.jdom2.*
import java.io.DataOutputStream
import java.io.OutputStream

class BXMLWriter private constructor(val out: DataOutputStream) {
    private val stringIds = HashMap<String, Int>()

    private fun writeElement(elem: Element) {
        writeString(elem.name)

        // Write the attributes
        val attributes = elem.attributes ?: emptyList()
        out.writeVarInt(attributes.size)
        for (attr in attributes) {
            // Don't bother with namespaces, we don't use them.
            // (slipstream does for its patches, but those are always applied
            //  to the game's XML before being saved)
            check(attr.namespace.prefix.isEmpty())
            writeString(attr.name)
            writeString(attr.value)
        }

        // Write the contents, notably including children.
        for (content in elem.content) {
            when (content) {
                is Text -> { // includes CDATA
                    // Don't write out whitespace, while it is technically
                    // meaningful we never use it, and even with only a couple
                    // of bytes it does have a significant impact on the output size.
                    if (content.text.isBlank())
                        continue

                    out.write(BXMLUtils.ID_TEXT)
                    writeString(content.text)
                }

                // We don't use processing instructions or entity references,
                // so just drop them too.
                // Comments are also obviously not meaningful.
                is Comment, is ProcessingInstruction, is EntityRef -> Unit

                is Element -> {
                    out.write(BXMLUtils.ID_ELEM)

                    // Recursively write out all the children
                    writeElement(content)
                }
            }
        }

        // We didn't say how many children to write since we didn't know
        // how many were things like comments or whitespace text, so add
        // a special type ID to indicate the end of this element's contents.
        out.write(BXMLUtils.ID_EOF)
    }

    /**
     * Writes a string.
     *
     * If the same string is written twice, it'll refer back to the original
     * one to save space.
     */
    private fun writeString(str: String) {
        val id = stringIds[str]

        if (id != null) {
            out.writeVarInt(id)
            return
        }

        // 0 indicates a literal
        out.writeVarInt(0)

        // While it involves a copy, for the common case of ASCII strings
        // it looks like this is the fastest way to get the data out.
        val bytes = str.toByteArray(Charsets.UTF_8)
        out.writeVarInt(bytes.size)
        out.write(bytes)

        // Add 1 so the first string doesn't use ID 0, which is the special
        // value we use for a literal.
        stringIds[str] = stringIds.size + 1
    }

    companion object {
        @JvmStatic
        fun write(doc: Document, outputStream: OutputStream) {
            DataOutputStream(outputStream).use {
                val writer = BXMLWriter(it)
                writer.writeElement(doc.rootElement)
            }
        }
    }
}

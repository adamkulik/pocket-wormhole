package xyz.znix.xftl.ui

import org.jdom2.Element

/**
 * A parser for a small subset of CSS, for applying the same attributes
 * to many independent elements.
 */
class StyleSheet(styleElem: Element) {
    private val blocks = ArrayList<Block>()

    init {
        val tokeniser = StyleTokeniser(styleElem.text)

        while (true) {
            // Have we hit EOF?
            if (tokeniser.peek() == null) {
                break
            }

            val selector = buildSelector(tokeniser)
            val block = Block(selector)
            buildPropertyBlock(tokeniser, block)
            blocks.add(block)
        }
    }

    private fun buildSelector(tok: StyleTokeniser): SelectorRule {
        val first = buildSingleSelector(tok)
        if (tok.peek() == "{") {
            return first
        }

        val parts = ArrayList<SelectorRule>()
        parts.add(first)

        // The character between selectors.
        // For example, it's '>' in:
        // .thingy > hbox > button
        var joiner = tok.peek()!!

        // Validate the joiner character now
        val finalCtor: (List<SelectorRule>) -> SelectorRule = when {
            joiner.length > 1 || !StyleTokeniser.TOKEN_CHARS.contains(joiner[0]) -> {
                joiner = ""
                ::AndChainSelector
            }

            joiner == ">" -> ::InheritChainSelector
            else -> throw IllegalUISpecException("Invalid selector joiner '$joiner'")
        }

        // Build up multiple selectors into a compound selector
        while (tok.peek() != "{") {
            if (joiner.isNotEmpty()) {
                val thisJoiner = tok.next()
                if (thisJoiner != joiner) {
                    throw IllegalUISpecException("Selector joiner mismatch: expected '$joiner', found '$thisJoiner'")
                }
            }

            parts.add(buildSingleSelector(tok))
        }

        return finalCtor(parts)
    }

    private fun buildSingleSelector(tok: StyleTokeniser): SelectorRule {
        return when (val first = tok.next()) {
            "#" -> IdSelector(tok.expectWord())
            "." -> ClassSelector(tok.expectWord())
            else -> TypeSelector(tok.checkWord(first))
        }
    }

    private fun buildPropertyBlock(tok: StyleTokeniser, block: Block) {
        tok.consume("{")

        while (tok.peek() != "}") {
            // Load a property
            val name = tok.expectWord()
            tok.consume(":")
            var value = tok.expectWord()
            tok.consume(";")

            if (value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1)
            } else if (value.toIntOrNull() == null) {
                throw IllegalUISpecException("Unquoted property '$name' value is not a number: '$value'")
            }

            block.properties[name] = value
        }

        tok.consume("}")
    }

    fun injectAttributes(root: Element) {
        for (elem in root.children) {
            injectAttributes(elem)
        }

        // Only inject attributes into widgets
        if (root.name != "widget")
            return

        for (block in blocks) {
            if (!block.selector.matches(root))
                continue

            for ((name, value) in block.properties) {
                // User-specified attributes aren't overwritten
                if (root.getAttribute(name) != null)
                    continue

                root.setAttribute(name, value)
            }
        }
    }
}

private class StyleTokeniser(text: String) {
    // Always end with whitespace, to avoid EOF handling elsewhere.
    private val text: String = "$text "

    private var position = 0
    private var stored: String? = null

    /**
     * Get a single token from this stylesheet, or null for EOF.
     */
    fun nextOrEOF(): String? {
        val result = internalNextOrEOF()

        // For debugging, uncomment this to see all the tokens:
        // println(result)

        return result
    }

    private fun internalNextOrEOF(): String? {
        // Have we already peeked at this token?
        stored?.let {
            stored = null
            return it
        }

        // Skip over any whitespace
        skipWhitespace()

        // EOF?
        if (position == text.length) {
            return null
        }

        val startPos = position
        val firstChar = text[position++]

        if (TOKEN_CHARS.contains(firstChar)) {
            return firstChar.toString()
        }

        if (firstChar == '"') {
            // String handling
            while (text[position] != '"') {
                position++
            }

            // Include the trailing quote
            position++

            return text.substring(startPos, position)
        }

        while (true) {
            val char = text[position]

            // Run until we find a whitespace or special character
            if (char.isWhitespace())
                break
            if (TOKEN_CHARS.contains(char))
                break
            if (char == '"')
                break

            position++
        }

        return text.substring(startPos, position)
    }

    private fun skipWhitespace() {
        var inComment = false

        while (true) {
            val char = text.getOrNull(position) ?: return

            if (char == '\n') {
                inComment = false
            }

            if (char.isWhitespace()) {
                position++
                continue
            }

            // Is this the start of a comment?
            if (char == '/' && text.getOrNull(position + 1) == '/') {
                inComment = true
            }
            if (inComment) {
                position++
                continue
            }

            // Not whitespace
            break
        }
    }

    fun next(): String {
        return nextOrEOF() ?: throw IllegalUISpecException("Hit unexpected EOF in stylesheet")
    }

    fun checkWord(token: String): String {
        if (token.length == 1 && TOKEN_CHARS.contains(token[0])) {
            throw IllegalUISpecException("stylesheet expecting word, found '$token'")
        }
        return token
    }

    fun expectWord(): String {
        return checkWord(next())
    }

    fun peek(): String? {
        // If we haven't already looked at the next token, do so now.
        if (stored == null) {
            stored = nextOrEOF()
        }

        return stored
    }

    fun consume(tok: String) {
        val actual = next()
        if (actual == tok)
            return

        throw IllegalUISpecException("stylesheet expecting token '$tok', found '$actual' instead")
    }

    companion object {
        val TOKEN_CHARS = setOf('.', ',', '#', '{', '}', ':', ';', '>')
    }
}

sealed interface SelectorRule {
    fun matches(elem: Element): Boolean
}

private class Block(val selector: SelectorRule) {
    val properties = HashMap<String, String>()
}

class ClassSelector(val className: String) : SelectorRule {
    override fun matches(elem: Element): Boolean {
        return elem.getAttributeValue("class") == className
    }
}

class IdSelector(val id: String) : SelectorRule {
    override fun matches(elem: Element): Boolean {
        return elem.getAttributeValue("id") == id
    }
}

class TypeSelector(val type: String) : SelectorRule {
    override fun matches(elem: Element): Boolean {
        return elem.getAttributeValue("type") == type
    }
}

class AndChainSelector(val selectors: List<SelectorRule>) : SelectorRule {
    override fun matches(elem: Element): Boolean {
        return selectors.all { it.matches(elem) }
    }
}

class InheritChainSelector(val selectors: List<SelectorRule>) : SelectorRule {
    override fun matches(elem: Element): Boolean {
        // The last selector must match exactly *this* element, to avoid
        // it applying to every child element as well.
        if (!selectors.last().matches(elem))
            return false

        // For all the other selectors, they can have other elements in between.
        // Since we already checked the last selector, start at the
        // second-to-last selector for this next phase.
        var currentSelector = selectors.size - 2

        // Each selector must match up to an element, with the right-most
        // selector matching the most deeply nested element.
        var iter: Element? = elem.parentElement
        while (iter != null) {
            val selector = selectors[currentSelector]
            if (selector.matches(iter)) {
                currentSelector--
            }
            if (currentSelector == -1) {
                break
            }

            iter = iter.parentElement
        }

        return currentSelector == -1
    }
}

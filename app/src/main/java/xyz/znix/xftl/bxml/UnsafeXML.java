package xyz.znix.xftl.bxml;

import org.jdom2.Attribute;
import org.jdom2.Element;
import org.jetbrains.annotations.NotNull;

/**
 * Android port: the sun.misc.Unsafe-based fast paths are not available, so
 * these helpers simply create XML objects normally. JDOM validates names,
 * which is slightly slower but functionally identical.
 */
public final class UnsafeXML {
    private UnsafeXML() {
    }

    @NotNull
    public static Attribute createAttribute(String name, String value) {
        return new Attribute(name, value);
    }

    @NotNull
    public static Element createElement(String name) {
        return new Element(name);
    }

    public static void addAttribute(Element elem, Attribute attr) {
        elem.setAttribute(attr);
    }
}

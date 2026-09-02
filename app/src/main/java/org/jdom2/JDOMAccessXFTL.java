package org.jdom2;

import java.util.AbstractList;

/**
 * This is a utility class to expose package-private stuff from JDOM.
 * <p>
 * This is used by the performance-sensitive BXML deserialiser.
 */
public class JDOMAccessXFTL {
    private JDOMAccessXFTL() {
    }

    public static AbstractList<Attribute> newAttributeList(Element parent) {
        return new AttributeList(parent);
    }

    public static Element newElement() {
        return new Element();
    }
}

package com.pocketwormhole.android;

import org.jdom2.input.SAXBuilder;

/**
 * Creates SAXBuilders safe for Android.
 *
 * Android's Expat SAX parser rejects the
 * http://xml.org/sax/features/external-general-entities feature that JDOM
 * sets when entity expansion is enabled (JDOM's default), so expansion is
 * always disabled here. Built-in XML entities (&amp;, &lt; ...) still work.
 */
public final class SafeXML {
    private SafeXML() {
    }

    public static SAXBuilder builder() {
        SAXBuilder b = new SAXBuilder();
        b.setExpandEntities(false);
        return b;
    }
}

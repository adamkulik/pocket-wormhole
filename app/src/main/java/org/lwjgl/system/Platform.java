package org.lwjgl.system;

/** Minimal platform enum. */
public enum Platform {
    WINDOWS,
    LINUX,
    MACOSX,
    ANDROID;

    private static final Platform CURRENT;

    static {
        CURRENT = ANDROID;
    }

    public static Platform get() {
        return CURRENT;
    }

    public static Platform get(String property) {
        return CURRENT;
    }
}

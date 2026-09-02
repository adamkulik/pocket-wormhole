package org.lwjgl.openal;

import java.nio.ByteBuffer;

public class ALC10 {
    public static final int ALC_DEVICE_SPECIFIER = 0x1005;
    public static final int ALC_DEFAULT_DEVICE_SPECIFIER = 0x1004;

    public static long alcOpenDevice(String name) {
        // The SoftAL output stream is created lazily; any non-zero handle is fine.
        return 1;
    }

    public static long alcOpenDevice(ByteBuffer deviceName) {
        return 1;
    }

    public static boolean alcCloseDevice(long device) {
        return true;
    }

    public static long alcCreateContext(long device, int[] attrList) {
        return 1;
    }

    public static boolean alcMakeContextCurrent(long context) {
        SoftAL.get().start();
        return true;
    }

    public static String alcGetString(long device, int param) {
        if (param == ALC_DEVICE_SPECIFIER || param == ALC_DEFAULT_DEVICE_SPECIFIER) {
            return "Android AudioTrack (SoftAL)";
        }
        return null;
    }
}

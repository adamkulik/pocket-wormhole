package org.newdawn.slick.opengl;

public final class InternalTextureLoader {
    private InternalTextureLoader() {
    }

    /**
     * Round a value up to the next power of two.
     */
    public static int get2Fold(int fold) {
        int ret = 2;
        while (ret < fold) {
            ret *= 2;
        }
        return ret;
    }
}

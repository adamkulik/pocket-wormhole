package org.lwjgl.opengl;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.Buffer;
import java.nio.FloatBuffer;

/**
 * Minimal GL11/20/30 shim for Android (GLES3), providing exactly the surface
 * used by the Wormhole (xftl) engine. Android port.
 */
public class GL11 {
    // ---- constants (values identical to GL / GLES) ----
    public static final int GL_FALSE = 0;
    public static final int GL_TRUE = 1;
    public static final int GL_NO_ERROR = 0;
    public static final int GL_DEPTH_BUFFER_BIT = 0x00000100;
    public static final int GL_STENCIL_BUFFER_BIT = 0x00000400;
    public static final int GL_COLOR_BUFFER_BIT = 0x00004000;
    public static final int GL_BLEND = 0x0BE2;
    public static final int GL_SRC_ALPHA = 0x0302;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    public static final int GL_DEPTH_TEST = 0x0B71;
    public static final int GL_STENCIL_TEST = 0x0B90;
    public static final int GL_NEVER = 0x0200;
    public static final int GL_EQUAL = 0x0202;
    public static final int GL_KEEP = 0x1E00;
    public static final int GL_REPLACE = 0x1E01;
    public static final int GL_ZERO = 0;
    public static final int GL_LINES = 0x0001;
    public static final int GL_TRIANGLES = 0x0004;
    public static final int GL_QUADS = 0x0007; // desktop-only mode; used only as a flag by the engine
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    public static final int GL_UNSIGNED_SHORT = 0x1403;
    public static final int GL_UNSIGNED_INT = 0x1405;
    public static final int GL_FLOAT = 0x1406;
    public static final int GL_RGB = 0x1907;
    public static final int GL_RGBA = 0x1908;
    public static final int GL_RGBA8 = 0x8058;
    public static final int GL_TEXTURE_2D = 0x0DE1;
    public static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    public static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    public static final int GL_NEAREST = 0x2600;
    public static final int GL_LINEAR = 0x2601;
    public static final int GL_MAX_TEXTURE_SIZE = 0x0D33;
    public static final int GL_ARRAY_BUFFER = 0x8892;
    public static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    public static final int GL_STREAM_DRAW = 0x88E0;
    public static final int GL_STATIC_DRAW = 0x88E4;

    // ---- state ----
    public static void glEnable(int cap) { GLES30.glEnable(cap); }

    public static void glDisable(int cap) { GLES30.glDisable(cap); }

    public static void glClearColor(float r, float g, float b, float a) { GLES30.glClearColor(r, g, b, a); }

    public static void glClear(int mask) { GLES30.glClear(mask); }

    public static void glViewport(int x, int y, int w, int h) { GLES30.glViewport(x, y, w, h); }

    public static void glBlendFunc(int sf, int df) { GLES30.glBlendFunc(sf, df); }

    public static int glGetError() { return GLES30.glGetError(); }

    public static int glGetInteger(int pname) {
        int[] out = new int[1];
        GLES30.glGetIntegerv(pname, out, 0);
        return out[0];
    }

    // ---- textures ----
    public static int glGenTextures() {
        int[] out = new int[1];
        GLES30.glGenTextures(1, out, 0);
        return out[0];
    }

    public static void glDeleteTextures(int id) {
        int[] ids = new int[]{id};
        GLES30.glDeleteTextures(1, ids, 0);
    }

    public static void glBindTexture(int target, int id) { GLES30.glBindTexture(target, id); }

    public static void glTexParameteri(int target, int pname, int param) {
        GLES30.glTexParameteri(target, pname, param);
    }

    public static void glTexImage2D(
            int target, int level, int internalformat, int width, int height, int border,
            int format, int type, ByteBuffer data) {
        Buffer b = (data != null && data.remaining() == 0) ? null : data;
        GLES30.glTexImage2D(target, level, internalformat, width, height, border, format, type, b);
    }

    // ---- stencil ----
    public static void glStencilFunc(int func, int ref, int mask) { GLES30.glStencilFunc(func, ref, mask); }

    public static void glStencilOp(int sfail, int dpfail, int dppass) { GLES30.glStencilOp(sfail, dpfail, dppass); }

    public static void glStencilMask(int mask) { GLES30.glStencilMask(mask); }

    // ---- drawing ----
    public static void glDrawArrays(int mode, int first, int count) { GLES30.glDrawArrays(mode, first, count); }
}

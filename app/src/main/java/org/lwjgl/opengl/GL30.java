package org.lwjgl.opengl;

import android.opengl.GLES30;

import java.nio.Buffer;
import java.nio.ByteBuffer;

public class GL30 extends GL20 {
    public static final int GL_VERTEX_ATTRIB_ARRAY_ENABLED = 0x8622;

    // ---- buffers & VAOs ----
    public static int glGenBuffers() {
        int[] out = new int[1];
        GLES30.glGenBuffers(1, out, 0);
        return out[0];
    }

    public static void glDeleteBuffers(int id) {
        int[] ids = new int[]{id};
        GLES30.glDeleteBuffers(1, ids, 0);
    }

    public static int glGenVertexArrays() {
        int[] out = new int[1];
        GLES30.glGenVertexArrays(1, out, 0);
        return out[0];
    }

    public static void glDeleteVertexArrays(int id) {
        int[] ids = new int[]{id};
        GLES30.glDeleteVertexArrays(1, ids, 0);
    }

    public static void glBindBuffer(int target, int id) { GLES30.glBindBuffer(target, id); }

    public static void glBindVertexArray(int id) { GLES30.glBindVertexArray(id); }

    public static void glBufferData(int target, ByteBuffer data, int usage) {
        Buffer b = (data != null && data.remaining() == 0) ? null : data;
        GLES30.glBufferData(target, data == null ? 0 : data.remaining(), b, usage);
    }

    // ---- vertex attributes ----
    public static void glEnableVertexAttribArray(int index) { GLES30.glEnableVertexAttribArray(index); }

    public static void glDisableVertexAttribArray(int index) { GLES30.glDisableVertexAttribArray(index); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalised, int stride, int offset) {
        GLES30.glVertexAttribPointer(index, size, type, normalised, stride, offset);
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalised, int stride, Buffer buffer) {
        GLES30.glVertexAttribPointer(index, size, type, normalised, stride, buffer);
    }

    // ---- indexed drawing ----
    public static void glDrawElements(int mode, int count, int type, int offset) {
        GLES30.glDrawElements(mode, count, type, offset);
    }
}

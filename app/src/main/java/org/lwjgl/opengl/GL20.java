package org.lwjgl.opengl;

import android.opengl.GLES30;

public class GL20 extends GL15 {
    public static final int GL_VERTEX_SHADER = 0x8B31;
    public static final int GL_FRAGMENT_SHADER = 0x8B30;
    public static final int GL_COMPILE_STATUS = 0x8B81;
    public static final int GL_LINK_STATUS = 0x8B82;
    public static final int GL_INFO_LOG_LENGTH = 0x8B84;

    public static int glCreateShader(int type) { return GLES30.glCreateShader(type); }

    public static void glShaderSource(int shader, String source) { GLES30.glShaderSource(shader, source); }

    public static void glCompileShader(int shader) { GLES30.glCompileShader(shader); }

    public static int glGetShaderi(int shader, int pname) {
        int[] out = new int[1];
        GLES30.glGetShaderiv(shader, pname, out, 0);
        return out[0];
    }

    public static String glGetShaderInfoLog(int shader, int maxLength) {
        return GLES30.glGetShaderInfoLog(shader);
    }

    public static void glDeleteShader(int shader) { GLES30.glDeleteShader(shader); }

    public static int glCreateProgram() { return GLES30.glCreateProgram(); }

    public static void glAttachShader(int program, int shader) { GLES30.glAttachShader(program, shader); }

    public static void glLinkProgram(int program) { GLES30.glLinkProgram(program); }

    public static int glGetProgrami(int program, int pname) {
        int[] out = new int[1];
        GLES30.glGetProgramiv(program, pname, out, 0);
        return out[0];
    }

    public static String glGetProgramInfoLog(int program, int maxLength) {
        return GLES30.glGetProgramInfoLog(program);
    }

    public static void glDeleteProgram(int program) { GLES30.glDeleteProgram(program); }

    public static int glGetUniformLocation(int program, String name) {
        return GLES30.glGetUniformLocation(program, name);
    }

    public static int glGetAttribLocation(int program, String name) {
        return GLES30.glGetAttribLocation(program, name);
    }

    public static void glUseProgram(int program) { GLES30.glUseProgram(program); }

    public static void glUniformMatrix3fv(int location, boolean transpose, java.nio.FloatBuffer matrix) {
        int count = matrix.remaining() / 9;
        GLES30.glUniformMatrix3fv(location, count, transpose, matrix);
    }

    public static void glUniform1i(int location, int v) { GLES30.glUniform1i(location, v); }
}

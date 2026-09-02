package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL30.*
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.sys.XftlResources

class ShaderProgramme(vertPath: String, fragPath: String) : AutoCloseable {
    var handle: Int = -1
        private set

    init {
        // Absorb any previous errors
        glGetError()

        var success = false

        var vert = -1
        var frag = -1
        try {
            vert = createShader(vertPath, GL_VERTEX_SHADER)
            frag = createShader(fragPath, GL_FRAGMENT_SHADER)

            handle = glCreateProgram()
            glAttachShader(handle, vert)
            glAttachShader(handle, frag)
            glLinkProgram(handle)
            checkError()

            val logLength = glGetProgrami(handle, GL_INFO_LOG_LENGTH)
            // IDK if it needs to be null-terminated, so +1 just in case
            val log = glGetProgramInfoLog(handle, logLength + 1)

            if (log.isNotEmpty()) {
                println("Message when linking shader '$vertPath'/'$fragPath':")
                println(log.trim())
            }

            val status = glGetProgrami(handle, GL_LINK_STATUS)
            if (status != 1) {
                error("Failed to link shader '$vertPath'/'$fragPath'!")
            }

            checkError()

            success = true
        } finally {
            if (vert != -1)
                glDeleteShader(vert)
            if (frag != -1)
                glDeleteShader(frag)

            if (!success)
                close()
        }

        checkError()
    }

    fun getAttributeLocation(name: String): Int {
        val location = glGetAttribLocation(handle, name)
        checkError()
        require(location != -1) { "Missing attribute location '$name'" }
        return location
    }

    fun getUniformLocation(name: String): Int {
        val location = glGetUniformLocation(handle, name)
        checkError()
        // Location may be -1, if the variable isn't used by the programme
        // Just print a warning, since throwing an exception is annoying while
        // writing the shaders, as you have to make sure you always use all
        // the variables.
        if (location == -1) {
            print("[WARN] Unknown shader uniform variable '$name'")
        }
        return location
    }

    private fun createShader(path: String, type: Int): Int {
        val bytes = XftlResources.open(path)?.use { it.readBytes() }
            ?: error("Could not find shader file '$path'")
        val content = toGlesShader(bytes.toString(Charsets.UTF_8), type)

        val shader = glCreateShader(type)
        glShaderSource(shader, content)
        glCompileShader(shader)
        checkError()

        val logLength = glGetShaderi(shader, GL_INFO_LOG_LENGTH)
        // IDK if it needs to be null-terminated, so +1 just in case
        val log = glGetShaderInfoLog(shader, logLength + 1)

        if (log.isNotEmpty()) {
            println("Message when compiling shader $path:")
            println(log.trim())
        }

        val status = glGetShaderi(shader, GL_COMPILE_STATUS)
        if (status != 1) {
            error("Failed to compile shader '$path'!")
        }

        checkError()

        return shader
    }

    override fun close() {
        if (handle != -1) {
            glDeleteProgram(handle)
            handle = -1
        }
    }

    companion object {
        /**
         * Convert desktop GLSL (#version 330 core) to GLSL ES 3.0, which is
         * what Android's GLES3 provides. The engine's shaders are otherwise
         * compatible.
         */
        private fun toGlesShader(source: String, type: Int): String {
            // Note: Kotlin's String.replaceFirst is a *literal* replacement,
            // which is exactly what we want here.
            var src = source
                .replace("#version 330 core", "#version 300 es")
                .replace("#version 330", "#version 300 es")
                // ES3 forbids float==int comparisons (desktop GLSL allows them)
                .replace("colour.a == 0)", "colour.a == 0.0)")
            if (type == GL_FRAGMENT_SHADER && !src.contains("precision ")) {
                src = src.replace(
                    "#version 300 es",
                    "#version 300 es\nprecision highp float;\nprecision highp int;"
                )
            }
            return src
        }

        private fun checkError() {
            val err = glGetError()
            if (err == GL_NO_ERROR)
                return

            error("OpenGL error: $err")
        }

        /**
         * This must be set by SlickGame, and is used to transform
         * the coordinates into pixel-space correctly.
         */
        @JvmStatic
        val SHADER_SCREEN_SIZE = Point(1, 1)
    }
}

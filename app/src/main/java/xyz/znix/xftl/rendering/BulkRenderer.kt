package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

abstract class BulkRenderer : AutoCloseable {
    private var vao: Int = 0
    private var vbo: Int = 0
    private var ebo: Int = 0

    protected var data: ByteBuffer = generateBuffer(128)
    protected var indices: ByteBuffer = generateBuffer(128)
    protected var numVerts = 0

    private var indicesAreQuads = false
    private val transformMatData = generateBuffer(4 * 9).asFloatBuffer()

    override fun close() {
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao)
            vao = 0
        }
        if (ebo != 0) {
            GL30.glDeleteBuffers(ebo)
            ebo = 0
        }
        if (vbo != 0) {
            GL30.glDeleteBuffers(vbo)
            vbo = 0
        }
    }

    protected fun getOrCreateVBO(): Int {
        if (vbo == 0) {
            vbo = GL30.glGenBuffers()
        }

        return vbo
    }

    protected fun getOrCreateEBO(): Int {
        if (ebo == 0) {
            ebo = GL30.glGenBuffers()
        }

        return ebo
    }

    protected fun getOrCreateVAO(): Int {
        if (vao == 0) {
            vao = GL30.glGenVertexArrays()
        }

        return vao
    }

    protected fun updateTransformMatrix(uniformLocation: Int) {
        // We have to transform from pixel-space to NDC (normalised
        // device coordinates), which go from -1 to 1 in both x and y.
        // Also note that positive is up, instead of down, so we have
        // to flip that around.

        transformMatData.clear()

        val size = ShaderProgramme.SHADER_SCREEN_SIZE

        transformMatData.put(2f / size.x)
        transformMatData.put(0f)
        transformMatData.put(0f)

        transformMatData.put(0f)
        transformMatData.put(-2f / size.y)
        transformMatData.put(0f)

        // Transform column, 1 is multiplied into this in the shader.
        transformMatData.put(-1f)
        transformMatData.put(1f)
        transformMatData.put(0f)

        transformMatData.flip()
        GL30.glUniformMatrix3fv(uniformLocation, false, transformMatData)
    }

    /**
     * Fills the indices array with indexes that render the [numVerts] quads.
     *
     * This re-uses the previous indices data if possible.
     */
    protected fun buildQuadIndices() {
        indices.clear()

        val sizePerQuad = 6 * 4
        val numQuads = numVerts / 4
        checkSizeIndices(numQuads * sizePerQuad)

        // Re-use the previous data, if possible.
        if (!indicesAreQuads) {
            indicesAreQuads = true

            // Completely fill the indices array with as many quads as we can.
            // That way we won't have to next time, unless it grows.
            val maxQuads = indices.remaining() / sizePerQuad
            for (i in 0 until maxQuads) {
                val firstVert = i * 4

                // Assume the vertices are added clockwise, as we build our CCW face.
                indices.putInt(firstVert + 0)
                indices.putInt(firstVert + 2)
                indices.putInt(firstVert + 1)

                indices.putInt(firstVert + 2)
                indices.putInt(firstVert + 0)
                indices.putInt(firstVert + 3)
            }
        }

        indices.position(numQuads * sizePerQuad)
    }

    /**
     * Ensures there's at least [required] bytes of space remaining in [data].
     */
    protected fun checkSize(required: Int) {
        data = checkSizeOf(data, required)
    }

    /**
     * Ensures there's at least [required] bytes of space remaining in [indices].
     */
    protected fun checkSizeIndices(required: Int) {
        if (required > indices.remaining()) {
            // If the indices were for quads, the end is now missing.
            indicesAreQuads = false
        }

        indices = checkSizeOf(indices, required)
    }

    private fun checkSizeOf(buf: ByteBuffer, required: Int): ByteBuffer {
        if (buf.remaining() >= required)
            return buf

        val current = buf.position() + buf.remaining()
        val newData = generateBuffer(max(required, current * 2))

        // Copy the old data into the new buffer
        buf.flip()
        newData.put(buf)
        return newData
    }

    protected fun generateBuffer(size: Int): ByteBuffer {
        return ByteBuffer.allocateDirect(size).apply {
            order(ByteOrder.nativeOrder())
        }
    }
}

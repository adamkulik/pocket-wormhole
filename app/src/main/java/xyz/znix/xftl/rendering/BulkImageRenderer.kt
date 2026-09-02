package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL30.*
import xyz.znix.xftl.f

/**
 * Instances of this class accumulate a bunch of stuff
 * to render, and then send it to the graphics API when flushed.
 *
 * This can be used to improve rendering performance for
 * stuff like text where there's otherwise a silly number
 * of draw calls.
 */
class BulkImageRenderer : BulkRenderer() {
    var imageFiltering: Int = Image.DEFAULT_TEXTURE_FILTERING

    private val transformMatData = generateBuffer(4 * 9).asFloatBuffer()

    init {
        getShader() // Make sure attribute locations are set up

        glBindVertexArray(getOrCreateVAO())

        // Packed as position then UV
        glEnableVertexAttribArray(posAttrib)
        glEnableVertexAttribArray(uvAttrib)
        glEnableVertexAttribArray(colourAttrib)

        glBindVertexArray(0)
    }

    fun pushImage(
        x1: Float, y1: Float, // Corner A on-screen
        x2: Float, y2: Float, // Corner B on-screen
        u1: Float, v1: Float, // Corner A in-texture
        u2: Float, v2: Float, // Corner B in-texture
        colour: Colour
    ) {
        pushImage(
            x1, y1, x2, y2,
            u1, v1, u2, v2,
            colour.r, colour.g, colour.b, colour.a
        )
    }

    fun pushImage(
        x1: Float, y1: Float, // Corner A on-screen
        x2: Float, y2: Float, // Corner B on-screen
        u1: Float, v1: Float, // Corner A in-texture
        u2: Float, v2: Float, // Corner B in-texture
        r: Float, g: Float, b: Float, a: Float // Modulate colour
    ) {
        pushVert(x1, y1, u1, v1, r, g, b, a)
        pushVert(x2, y2, u2, v2, r, g, b, a)
        pushVert(x2, y1, u2, v1, r, g, b, a)

        pushVert(x1, y1, u1, v1, r, g, b, a)
        pushVert(x1, y2, u1, v2, r, g, b, a)
        pushVert(x2, y2, u2, v2, r, g, b, a)
    }

    fun pushVert(x: Float, y: Float, u: Float, v: Float) {
        pushVert(x, y, u, v, 1f, 1f, 1f, 1f)
    }

    fun pushVert(
        x: Float, y: Float,
        u: Float, v: Float,
        r: Float, g: Float, b: Float, a: Float // Modulate colour
    ) {
        checkSize(8 * 4) // 8 32-bit floats

        // Transform our position to reflect the Graphics translate calls.
        // 3x3 matrix multiply, with (baseX,baseY,1)
        val m = Graphics.getTextureTransformMatrix()
        val transformedX = x * m.m00 + y * m.m01 + m.m02
        val transformedY = x * m.m10 + y * m.m11 + m.m12
        // Don't need to calculate a W value

        data.putFloat(transformedX)
        data.putFloat(transformedY)
        data.putFloat(u)
        data.putFloat(v)

        data.putFloat(r)
        data.putFloat(g)
        data.putFloat(b)
        data.putFloat(a)

        numVerts++
    }

    fun flush(image: Image) {
        if (numVerts == 0)
            return

        val shader = getShader()

        image.texture.bind(imageFiltering) // Binds to GL_TEXTURE_2D

        glBindVertexArray(getOrCreateVAO())

        glBindBuffer(GL_ARRAY_BUFFER, getOrCreateVBO())
        data.flip()
        glBufferData(GL_ARRAY_BUFFER, data, GL_STREAM_DRAW)

        glVertexAttribPointer(posAttrib, 2, GL_FLOAT, false, 32, 0)
        glVertexAttribPointer(uvAttrib, 2, GL_FLOAT, false, 32, 8)
        glVertexAttribPointer(colourAttrib, 4, GL_FLOAT, false, 32, 16)

        glUseProgram(shader.handle)

        // Set the transform uniforms
        updateTransformMatrix(posTransformLoc)
        updateUvTransformMatrix(image)

        glDrawArrays(GL_TRIANGLES, 0, numVerts)

        // Cleanup
        glUseProgram(0)
        glBindBuffer(GL_ARRAY_BUFFER, 0)
        glBindVertexArray(0)

        // Clear out the buffer for subsequent renders
        data.clear()
        numVerts = 0

        return
    }

    private fun updateUvTransformMatrix(image: Image) {
        // UVs are 0-1 in both axes.

        transformMatData.clear()

        transformMatData.put(1f / image.texture.rawTextureWidth)
        transformMatData.put(0f)
        transformMatData.put(0f)

        transformMatData.put(0f)
        transformMatData.put(1f / image.texture.rawTextureHeight)
        transformMatData.put(0f)

        // Transform column, 1 is multiplied into this in the shader.
        transformMatData.put(image.textureOffsetX.f / image.texture.rawTextureWidth)
        transformMatData.put(image.textureOffsetY.f / image.texture.rawTextureHeight)
        transformMatData.put(0f)

        transformMatData.flip()
        glUniformMatrix3fv(uvTransformLoc, false, transformMatData)
    }

    companion object {
        // Per-context shader cache - see BulkColourRenderer's comment.
        private var shader: ShaderProgramme? = null
        private var posAttrib = 0
        private var uvAttrib = 0
        private var colourAttrib = 0
        private var posTransformLoc = 0
        private var uvTransformLoc = 0

        private fun getShader(): ShaderProgramme {
            var s = shader
            if (s == null) {
                s = ShaderProgramme("shaders/image_rect_vert.glsl", "shaders/image_rect_frag.glsl")
                posAttrib = s.getAttributeLocation("pos")
                uvAttrib = s.getAttributeLocation("uv")
                colourAttrib = s.getAttributeLocation("filterColour")
                posTransformLoc = s.getUniformLocation("posTransform")
                uvTransformLoc = s.getUniformLocation("uvTransform")
                shader = s
            }
            return s
        }

        /** The GL context was (re)created; drop cached handles from the old one. */
        fun onContextRecreated() {
            shader = null
        }
    }
}

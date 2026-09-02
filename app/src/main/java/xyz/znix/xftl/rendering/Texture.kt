package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL11
import xyz.znix.xftl.sys.INativeResource

/**
 * A representation of the underlying texture data that an image represents a view of.
 */
class Texture(
    // The size of the texture, as uploaded to the GPU.
    val rawTextureWidth: Int,
    val rawTextureHeight: Int,

    // The size of the portion of the image that contains something useful.
    // This is larger, as Slick rounds images up to power-of-two sizes.
    // TODO can we save some VRAM by not doing this? Is it an old compatibility issue?
    val imageWidth: Int,
    val imageHeight: Int,

    // The OpenGL resource reference ('name') of this image.
    private val glName: Int
) : INativeResource {
    private var currentFiltering = -1

    override var freed: Boolean = false
        private set

    /**
     * Bind the texture, with a given filtering setting - either [GL11.GL_LINEAR]
     * or [GL11.GL_NEAREST] - the latter should be used when scaling up the image.
     */
    fun bind(filtering: Int) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glName)

        if (currentFiltering != filtering) {
            // Note we have to set both min and mag, since we use this when drawing
            // rotated (but not scaled) drones.
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filtering)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filtering)
        }
    }

    override fun free() {
        require(!freed)
        freed = true

        GL11.glDeleteTextures(glName)
    }
}

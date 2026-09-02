package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL11
import org.newdawn.slick.opengl.ImageData
import org.newdawn.slick.opengl.ImageDataFactory
import org.newdawn.slick.opengl.InternalTextureLoader
import org.newdawn.slick.opengl.PNGDecoder
import xyz.znix.xftl.sys.ResourceContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

// Note: bits of this are copy/pasted from Slick's InternalTextureLoader.

object TextureLoader {
    init {
        // Make sure we're using our modified copy of PNGDecoder.
        // This is a bit of an ugly place to put it, but it'll do.
        // Use reflection here, since Julk said this was causing issues
        // with Gradle compiling against Slick's class (though it should
        // always be correct at runtime).
        try {
            PNGDecoder::class.java.getField("FTL_MARKER")
        } catch (e: NoSuchFieldException) {
            throw RuntimeException("Couldn't verify modified PNG loader is in use", e)
        }
    }

    fun loadTexture(context: ResourceContext, imageData: ImageData): Texture {
        // Very heavily copied from InternalTextureLoader.

        val textureID = GL11.glGenTextures()
        val dstPixelFormat = GL11.GL_RGBA8
        val target = GL11.GL_TEXTURE_2D

        // bind this texture
        GL11.glBindTexture(target, textureID)

        val width = imageData.width
        val height = imageData.height
        val hasAlpha = imageData.depth == 32

        val max = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)
        if (imageData.texWidth > max || imageData.texHeight > max) {
            throw IOException("Attempt to allocate a texture to big for the current hardware")
        }

        val srcPixelFormat = if (hasAlpha) GL11.GL_RGBA else GL11.GL_RGB

        // Load the texture data into a byte array
        val textureBuffer = imageData.imageBufferData

        // produce a texture from the byte buffer
        GL11.glTexImage2D(
            target,
            0,
            dstPixelFormat,
            InternalTextureLoader.get2Fold(width),
            InternalTextureLoader.get2Fold(height),
            0,
            srcPixelFormat,
            GL11.GL_UNSIGNED_BYTE,
            textureBuffer
        )

        val tex = Texture(
            imageData.texWidth,
            imageData.texHeight,

            imageData.width,
            imageData.height,

            textureID
        )

        // Delete the OpenGL image when appropriate
        context.register(tex)

        return tex
    }

    fun loadImage(context: ResourceContext, imageData: ImageData): Image {
        val texture = loadTexture(context, imageData)
        return Image(
            0, 0,
            texture.imageWidth, texture.imageHeight,
            texture
        )
    }

    fun loadImage(context: ResourceContext, stream: InputStream, path: String): Image {
        val imageData = ImageDataFactory.getImageDataFor(path)

        // Discard the result, we'll get the same thing by calling imageBufferData later
        imageData.loadImage(BufferedInputStream(stream), false, null)

        return loadImage(context, imageData)
    }
}

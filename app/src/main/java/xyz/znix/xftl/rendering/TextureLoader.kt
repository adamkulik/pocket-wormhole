package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
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

        // The game never tiles textures via GL_REPEAT (tiling is done by
        // drawing repeated quads), and with linear filtering REPEAT blends
        // the outermost pixel columns with the opposite edge of the texture
        // or its power-of-two padding - visible as dark fringes around
        // scaled (touch-UI) draws, eg the beacon-label pills on the jump
        // map. Clamp instead.
        GL11.glTexParameteri(target, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(target, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)

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
        val buf = textureBuffer.duplicate()

        // Premultiply the RGB channels by alpha. Linear filtering averages a
        // pixel's neighbours, and fully-transparent pixels have meaningless
        // RGB values (the PNGs keep their colour there - some black, some
        // the art's own colour), so filtering unpremultiplied data bleeds
        // those colours into edges of scaled draws. Filtering premultiplied
        // data is correct; the image shader divides the alpha back out after
        // sampling, so 1:1 nearest draws are restored to their original
        // values. See image_rect_frag.glsl.
        if (hasAlpha) {
            var off = 0
            val size = buf.capacity()
            while (off + 4 <= size) {
                val a = buf.get(off + 3).toInt() and 0xFF
                if (a != 255) {
                    for (c in 0..2) {
                        val v = buf.get(off + c).toInt() and 0xFF
                        buf.put(off + c, ((v * a + 127) / 255).toByte())
                    }
                }
                off += 4
            }
        }

        // Replicate the right column and bottom row into the power-of-two
        // padding: the padding is otherwise transparent black (the decoder
        // only writes width x height pixels), and with linear filtering the
        // outermost fragments of scaled draws blend with it - darkening the
        // right/bottom edges of non-power-of-two art (eg the beacon-label
        // pills' caps, whose rounded ends sit against the pad). Left/top
        // edges are texture edges and already clamp cleanly. Nearest
        // sampling never touches the padding, so 1:1 draws are unaffected.
        run {
            val texWidth = imageData.texWidth
            val texHeight = imageData.texHeight
            val bpp = if (hasAlpha) 4 else 3

            if (width < texWidth) {
                for (y in 0 until height) {
                    val srcOff = (y * texWidth + width - 1) * bpp
                    for (x in width until texWidth) {
                        val dstOff = (y * texWidth + x) * bpp
                        for (c in 0 until bpp)
                            buf.put(dstOff + c, buf.get(srcOff + c))
                    }
                }
            }
            if (height < texHeight) {
                val srcRow = (height - 1) * texWidth * bpp
                for (y in height until texHeight) {
                    val dstOff = y * texWidth * bpp
                    for (x in 0 until texWidth * bpp)
                        buf.put(dstOff + x, buf.get(srcRow + x))
                }
            }
        }

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

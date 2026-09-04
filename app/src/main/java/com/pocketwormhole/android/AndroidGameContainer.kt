package com.pocketwormhole.android

import android.opengl.EGL14
import android.opengl.EGLConfig as AospEGLConfig
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import org.lwjgl.opengl.GL11
import xyz.znix.xftl.rendering.BulkColourRenderer
import xyz.znix.xftl.rendering.BulkImageRenderer
import org.newdawn.slick.KeyListener
import org.newdawn.slick.MouseListener
import xyz.znix.xftl.rendering.Cursor
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.ShaderProgramme
import xyz.znix.xftl.sys.Game
import xyz.znix.xftl.sys.GameContainer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GameContainer implementation driving the game from a GLSurfaceView
 * renderer, replacing the desktop GLFW/LWJGL container.
 */
class AndroidGameContainer(
    private val game: Game,
    override val input: AndroidInput,
    private val finishCallback: () -> Unit
) : GameContainer {

    override val width: Int = GAME_W
    override val height: Int = GAME_H

    private val g = Graphics()
    private var lastNanos = System.nanoTime()
    private var started = false

    /** Physical surface size, for viewport letterboxing. */
    var surfaceW: Int = GAME_W
    var surfaceH: Int = GAME_H

    val renderer: GLSurfaceView.Renderer = object : GLSurfaceView.Renderer {

        // Offscreen framebuffer with a guaranteed depth+stencil renderbuffer.
        // The windowing surface's EGL config may lack stencil bits (GLSurfaceView
        // only picks the *closest* match), which breaks all of the engine's
        // stencil-based UI (mask geometry gets painted visibly - see the
        // red-tinted JumpWindow / white jump-button box bug on Adreno devices).
        private var fbo = 0
        private var colorRb = 0
        private var depthStencilRb = 0
        private var fboW = 0
        private var fboH = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            logEglSurfaceInfo()

            // A new EGL context means all cached GL handles from the old one
            // (shader programmes) are invalid - drop them before anything can
            // reuse them (grey-screen bug when reopening after Save+Quit).
            BulkColourRenderer.onContextRecreated()
            BulkImageRenderer.onContextRecreated()

            // Base GL state, as the desktop container sets up
            GLES30.glDisable(GL11.GL_DEPTH_TEST)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glEnable(GL11.GL_BLEND)
            GLES30.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            ShaderProgramme.SHADER_SCREEN_SIZE.set(GAME_W, GAME_H)

            if (!started) {
                started = true
                g.markCurrentImageTransformSource()

                if (game is KeyListener) input.addListener(game)
                if (game is MouseListener) input.addListener(game)

                try {
                    game.init(this@AndroidGameContainer)
                } catch (ex: Exception) {
                    android.util.Log.e(TAG, "Exception during game init", ex)
                    throw RuntimeException(ex)
                }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            android.util.Log.i(TAG, "onSurfaceChanged ${width}x$height")
            surfaceW = width
            surfaceH = height
            recreateFbo(width, height)
            applyViewport()
        }

        override fun onDrawFrame(gl: GL10?) {
            val thisTime = System.nanoTime()
            val deltaSec = (thisTime - lastNanos) / 1_000_000_000f
            lastNanos = thisTime

            try {
                input.drainEvents()
                input.postUpdate()
            } catch (ex: Exception) {
                // Event listeners run engine code; keep the game alive and
                // mirror the update/render exception handling.
                android.util.Log.e(TAG, "Exception during input dispatch", ex)
            }

            try {
                game.update(this@AndroidGameContainer, deltaSec)
            } catch (ex: Exception) {
                android.util.Log.e(TAG, "Exception during game update", ex)
            }

            // Render the frame into the offscreen FBO (which always has
            // depth+stencil), then blit it to the screen.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)

            // Clear the whole surface (including letterbox bars) to black
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glViewport(0, 0, surfaceW, surfaceH)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_STENCIL_BUFFER_BIT)

            applyViewport()

            try {
                game.render(this@AndroidGameContainer, g)
            } catch (ex: Exception) {
                android.util.Log.e(TAG, "Exception during game render", ex)
                // The exception skipped whatever popTransform calls were
                // pending; clear the stack or every future frame fails the
                // engine's checkNoPushedTransforms (stale-frame flicker).
                g.recoverFromAbortedRender()
            }

            // Blit the finished frame to the default (window) framebuffer
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fbo)
            GLES30.glBlitFramebuffer(
                0, 0, fboW, fboH,
                0, 0, surfaceW, surfaceH,
                GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

            // A frame has been rendered; pending clicks may now dispatch
            input.markRendered()
        }

        private fun recreateFbo(width: Int, height: Int) {
            destroyFbo()

            val fboArr = IntArray(1)
            GLES30.glGenFramebuffers(1, fboArr, 0)
            fbo = fboArr[0]
            val rbArr = IntArray(2)
            GLES30.glGenRenderbuffers(2, rbArr, 0)
            colorRb = rbArr[0]
            depthStencilRb = rbArr[1]

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)

            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, colorRb)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, width, height)
            GLES30.glFramebufferRenderbuffer(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_RENDERBUFFER, colorRb
            )

            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthStencilRb)
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH24_STENCIL8, width, height)
            GLES30.glFramebufferRenderbuffer(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                GLES30.GL_RENDERBUFFER, depthStencilRb
            )
            GLES30.glFramebufferRenderbuffer(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_STENCIL_ATTACHMENT,
                GLES30.GL_RENDERBUFFER, depthStencilRb
            )

            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                android.util.Log.e(TAG, "Render FBO incomplete: 0x" + Integer.toHexString(status))
            }
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, 0)
            fboW = width
            fboH = height
        }

        private fun destroyFbo() {
            if (fbo != 0) {
                GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
                fbo = 0
            }
            if (colorRb != 0 || depthStencilRb != 0) {
                GLES30.glDeleteRenderbuffers(2, intArrayOf(colorRb, depthStencilRb), 0)
                colorRb = 0
                depthStencilRb = 0
            }
        }

        /**
         * Logs the depth/stencil bits of the EGL config the surface actually
         * got, so stencil-related rendering bugs can be diagnosed from the
         * logs alone.
         */
        private fun logEglSurfaceInfo() {
            try {
                android.util.Log.i(TAG, "logEglSurfaceInfo: enter")
                val display = EGL14.eglGetCurrentDisplay()
                val ctx = EGL14.eglGetCurrentContext()
                android.util.Log.i(TAG, "logEglSurfaceInfo: display=$display ctx=$ctx")
                if (display === EGL14.EGL_NO_DISPLAY || ctx === EGL14.EGL_NO_CONTEXT) return
                val idVal = IntArray(1)
                val qok = EGL14.eglQueryContext(display, ctx, EGL14.EGL_CONFIG_ID, idVal, 0)
                android.util.Log.i(TAG, "logEglSurfaceInfo: queryContext ok=$qok configId=${idVal[0]}")
                if (!qok) return
                val num = IntArray(1)
                val configs = arrayOfNulls<AospEGLConfig>(64)
                val gok = EGL14.eglGetConfigs(display, configs, 0, configs.size, num, 0)
                android.util.Log.i(TAG, "logEglSurfaceInfo: getConfigs ok=$gok num=${num[0]}")
                if (!gok) return
                for (c in configs) {
                    c ?: continue
                    val id = IntArray(1)
                    EGL14.eglGetConfigAttrib(display, c, EGL14.EGL_CONFIG_ID, id, 0)
                    if (id[0] != idVal[0]) continue
                    val v = IntArray(1)
                    EGL14.eglGetConfigAttrib(display, c, EGL14.EGL_DEPTH_SIZE, v, 0)
                    val depth = v[0]
                    EGL14.eglGetConfigAttrib(display, c, EGL14.EGL_STENCIL_SIZE, v, 0)
                    val stencil = v[0]
                    android.util.Log.i(
                        TAG, "EGL surface config: depth=$depth stencil=$stencil " +
                        "(engine requires stencil; rendering goes through an " +
                        "offscreen FBO with guaranteed DEPTH24_STENCIL8)"
                    )
                    break
                }
            } catch (ex: Throwable) {
                android.util.Log.w(TAG, "EGL config query failed", ex)
            }
        }
    }

    fun applyViewport() {
        // Letterbox the 16:9 game area inside the physical surface
        val surfaceAspect = surfaceW.toFloat() / surfaceH
        val gameAspect = GAME_W.toFloat() / GAME_H

        var viewW = surfaceW
        var viewH = surfaceH
        if (surfaceAspect > gameAspect) {
            viewW = (surfaceH * gameAspect).toInt()
        } else {
            viewH = (surfaceW / gameAspect).toInt()
        }
        val viewX = (surfaceW - viewW) / 2
        val viewY = (surfaceH - viewH) / 2

        GLES30.glViewport(viewX, viewY, viewW, viewH)

        val scale = if (surfaceAspect > gameAspect) GAME_H.toFloat() / viewH else GAME_W.toFloat() / viewW
        input.viewTransform = AndroidInput.TouchTransform(
            viewX * (GAME_W.toFloat() / viewW),
            (surfaceH - viewY - viewH) * (GAME_H.toFloat() / viewH),
            GAME_W.toFloat() / viewW,
            GAME_H.toFloat() / viewH
        )
    }

    override fun exit() {
        game.shutdown()
        finishCallback()
    }

    override fun setCursor(cursor: Cursor?) {
        // No OS cursors on Android
    }

    companion object {
        const val GAME_W = 1280
        const val GAME_H = 720
        private const val TAG = "XFTL"
    }
}

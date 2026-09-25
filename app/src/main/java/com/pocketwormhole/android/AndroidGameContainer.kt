package com.pocketwormhole.android

import android.graphics.RectF
import android.opengl.EGL14
import android.opengl.EGLConfig as AospEGLConfig
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import org.lwjgl.opengl.GL11
import xyz.znix.xftl.rendering.BulkColourRenderer
import xyz.znix.xftl.rendering.BulkImageRenderer
import org.newdawn.slick.KeyListener
import org.newdawn.slick.MouseListener
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Cursor
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.ShaderProgramme
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GameContainer implementation driving the game from a GLSurfaceView
 * renderer, replacing the desktop GLFW/LWJGL container.
 */
class AndroidGameContainer(
    private val game: MainGame,
    override val input: AndroidInput,
    private val finishCallback: () -> Unit
) : GameContainer {

    override val width: Int = GAME_W
    override val height: Int = GAME_H

    private val g = Graphics()
    private var lastNanos = System.nanoTime()
    private var started = false

    /**
     * Set by the activity when the app loses focus (GitHub issue #96);
     * the next frame opens the pause menu before the render thread
     * suspends, so returning to the app never resumes an unpaused
     * battlefield.
     */
    @Volatile
    var autoPauseRequested = false

    /**
     * Set by the view when the letterbox pause button was tapped; consumed
     * on the GL thread at the top of the next frame.
     */
    @Volatile
    private var pauseToggleRequested = false

    /**
     * The letterbox pause button's tappable rect in surface pixels, or
     * null while it's hidden (not in flight / no usable bar). Written on
     * the GL thread every frame, read on the UI thread by the touch path.
     */
    @Volatile
    var pauseButtonHitRect: RectF? = null
        private set

    /** The letterboxed game-area viewport, in surface pixels. */
    private var viewX = 0
    private var viewY = 0
    private var viewW = GAME_W
    private var viewH = GAME_H

    /** Called from the UI thread when the pause button is tapped. */
    fun requestPauseToggle() {
        pauseToggleRequested = true
    }

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
            if (autoPauseRequested) {
                autoPauseRequested = false
                try {
                    game.autoPauseIfInFlight()
                } catch (ex: Throwable) {
                    android.util.Log.e(TAG, "auto-pause on focus loss failed", ex)
                }
            }

            if (pauseToggleRequested) {
                pauseToggleRequested = false
                try {
                    game.togglePauseFromTouch()
                } catch (ex: Throwable) {
                    android.util.Log.e(TAG, "letterbox pause toggle failed", ex)
                }
            }

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

            // Draw the touch pause button into the letterbox bar; the
            // blit below carries it to the screen.
            try {
                drawPauseOverlay()
            } catch (ex: Throwable) {
                android.util.Log.e(TAG, "letterbox pause overlay failed", ex)
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

        // Remember the letterbox geometry for the pause button overlay.
        this.viewX = viewX
        this.viewY = viewY
        this.viewW = viewW
        this.viewH = viewH

        GLES30.glViewport(viewX, viewY, viewW, viewH)

        val scale = if (surfaceAspect > gameAspect) GAME_H.toFloat() / viewH else GAME_W.toFloat() / viewW
        // The input transform is the exact inverse of the viewport mapping:
        // a touch at screen (x, y) corresponds to canvas
        // ((x - viewX) * scale, (y - viewYbottom) * scale). The offsets must
        // be in SCREEN pixels - scaling them (the old form) put every tap
        // systematically off-target by viewX * (1 - scale) canvas pixels,
        // which the scaled systems strip made unmissable.
        input.viewTransform = AndroidInput.TouchTransform(
            viewX.toFloat(),
            (surfaceH - viewY - viewH).toFloat(),
            GAME_W.toFloat() / viewW,
            GAME_H.toFloat() / viewH
        )
    }

    /**
     * Draws the touch pause button into the letterbox bar. The game
     * viewport clips anything outside the 16:9 area, so this switches to
     * a full-surface viewport and temporarily rescales the pixel-to-NDC
     * mapping to the surface size - overlay coordinates are then plain
     * surface pixels, exactly as engine draws are canvas pixels.
     */
    private fun drawPauseOverlay() {
        val visual = computePauseButtonRect()

        // Publish (or hide) the hit rect for the UI thread: the visual
        // rect inflated for easier tapping, clamped to the letterbox bar
        // so the button can never steal taps aimed at the game area.
        pauseButtonHitRect = if (visual == null) null else RectF(visual).apply {
            inset(-HIT_INFLATE_PX, -HIT_INFLATE_PX)
            intersect(buttonBandRect())
        }

        visual ?: return

        // The engine may have left these tests on; the overlay draws raw
        // quads and the next frame's clear re-establishes state anyway.
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glViewport(0, 0, surfaceW, surfaceH)

        val oldW = ShaderProgramme.SHADER_SCREEN_SIZE.x
        val oldH = ShaderProgramme.SHADER_SCREEN_SIZE.y
        ShaderProgramme.SHADER_SCREEN_SIZE.set(surfaceW, surfaceH)
        g.loadIdentityMatrix()

        // The pause glyph: two bars, like every media player's.
        g.colour = PAUSE_COLOUR
        val barW = visual.width() * 0.16f
        val barH = visual.height() * 0.5f
        val gap = visual.width() * 0.20f
        val cx = visual.centerX()
        val cy = visual.centerY()
        g.fillRect(cx - gap / 2 - barW, cy - barH / 2, barW, barH)
        g.fillRect(cx + gap / 2, cy - barH / 2, barW, barH)

        ShaderProgramme.SHADER_SCREEN_SIZE.set(oldW, oldH)
    }

    /**
     * The pause button's visual rect in surface pixels, or null when it
     * is hidden: outside a run, or when the letterbox bar is too narrow
     * to hold it (16:9-ish screens - the in-game top-bar menu button is
     * the pause affordance there).
     */
    private fun computePauseButtonRect(): RectF? {
        if (!game.isInFlight())
            return null

        val sideBarW = viewX
        if (sideBarW >= MIN_BAR_PX) {
            // sensorLandscape phones: right-hand bar, vertically centred
            // (thumb-reachable). The surface excludes any camera-cutout
            // inset (S24 Ultra in HD+: 1496x720 -> a 108px bar), so the
            // glyph never collides with the camera hole; size yields to
            // the bar width instead of a hard floor so cutout-inset
            // devices still get the button.
            val size = minOf(surfaceH * BUTTON_SIZE_FRAC, sideBarW * BAR_FIT_FRAC)
                .coerceIn(MIN_BUTTON_PX, MAX_BUTTON_PX)
            val x = surfaceW - sideBarW + (sideBarW - size) / 2f
            val y = (surfaceH - size) / 2f
            return RectF(x, y, x + size, y + size)
        }

        val topBottomBarH = viewY
        if (topBottomBarH >= MIN_BAR_PX) {
            // Portrait-ish surfaces: bottom bar, right-aligned.
            val size = minOf(surfaceW * BUTTON_SIZE_FRAC, topBottomBarH * BAR_FIT_FRAC)
                .coerceIn(MIN_BUTTON_PX, MAX_BUTTON_PX)
            val x = surfaceW - size - 16f
            val y = surfaceH - topBottomBarH + (topBottomBarH - size) / 2f
            return RectF(x, y, x + size, y + size)
        }

        return null
    }

    /** The letterbox band the button lives in, for hit-rect clamping. */
    private fun buttonBandRect(): RectF {
        val surfaceWf = surfaceW.toFloat()
        val surfaceHf = surfaceH.toFloat()
        return if (viewX >= MIN_BAR_PX)
            RectF(surfaceWf - viewX, 0f, surfaceWf, surfaceHf)
        else
            RectF(0f, surfaceHf - viewY, surfaceWf, surfaceHf)
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

        // Letterbox pause button: bars narrower than MIN_BAR_PX hide it;
        // otherwise its visual size follows the surface's short axis but
        // yields to the bar width (cutout insets shrink it - S24 Ultra
        // HD+ has a 108px bar).
        private const val MIN_BAR_PX = 72
        private const val MIN_BUTTON_PX = 56f
        private const val MAX_BUTTON_PX = 150f
        private const val BUTTON_SIZE_FRAC = 0.11f
        private const val BAR_FIT_FRAC = 0.72f
        private const val HIT_INFLATE_PX = 24f
        private val PAUSE_COLOUR = Colour(200, 200, 200, 220)
    }
}

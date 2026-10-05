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
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.rendering.Image
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
     * Set by the view when a letterbox button was tapped; consumed on the
     * GL thread at the top of the next frame.
     */
    @Volatile
    private var pendingLetterboxAction: LetterboxAction? = null

    /**
     * The letterbox buttons' tappable rects in surface pixels, or empty
     * while they're hidden (not in flight / no usable bar). Written on
     * the GL thread every frame, read on the UI thread by the touch path.
     */
    @Volatile
    var letterboxButtons: List<LetterboxButton> = emptyList()
        private set

    /** The letterboxed game-area viewport, in surface pixels. */
    private var viewX = 0
    private var viewY = 0
    private var viewW = GAME_W
    private var viewH = GAME_H

    /** Called from the UI thread when a letterbox button is tapped. */
    fun requestLetterboxAction(action: LetterboxAction) {
        pendingLetterboxAction = action
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

            val queuedAction = pendingLetterboxAction
            if (queuedAction != null) {
                pendingLetterboxAction = null
                try {
                    when (queuedAction) {
                        LetterboxAction.PAUSE_TOGGLE -> game.togglePauseFromTouch()
                        LetterboxAction.OPEN_DOORS -> game.letterboxOpenAllDoors()
                        LetterboxAction.CLOSE_DOORS -> game.letterboxCloseAllDoors()
                        LetterboxAction.SAVE_STATIONS -> game.letterboxSaveStations()
                        LetterboxAction.RETURN_STATIONS -> game.letterboxReturnStations()
                    }
                } catch (ex: Throwable) {
                    android.util.Log.e(TAG, "letterbox button action failed", ex)
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

            // Draw the letterbox buttons into the bars; the blit below
            // carries them to the screen.
            try {
                drawLetterboxOverlay()
            } catch (ex: Throwable) {
                android.util.Log.e(TAG, "letterbox button overlay failed", ex)
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

        // Remember the letterbox geometry for the buttons overlay.
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
     * Draws the letterbox buttons into the bars. The game viewport clips
     * anything outside the 16:9 area, so this switches to a full-surface
     * viewport and temporarily rescales the pixel-to-NDC mapping to the
     * surface size - overlay coordinates are then plain surface pixels,
     * exactly as engine draws are canvas pixels.
     */
    private fun drawLetterboxOverlay() {
        val defs = computeLetterboxButtons()

        // Publish (or hide) the hit rects for the UI thread: each visual
        // rect inflated for easier tapping, clamped to its own letterbox
        // band so the buttons can never steal taps aimed at the game area.
        letterboxButtons = defs.map { (action, visual) ->
            val band = bandRectFor(visual)
            val hit = RectF(visual).apply {
                inset(-HIT_INFLATE_PX, -HIT_INFLATE_PX)
                intersect(band)
            }
            LetterboxButton(action, hit)
        }

        if (defs.isEmpty()) return
        val state = game.letterboxOverlayState() ?: return
        val art = letterboxArt(state) ?: return

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

        val doorsOperable = game.doorsOperableForOverlay()
        val stationsSaved = game.hasSavedStationsForOverlay()
        for ((action, rect) in defs) {
            val cx = rect.centerX()
            val cy = rect.centerY()
            when (action) {
                LetterboxAction.PAUSE_TOGGLE -> {
                    // The pause glyph: two bars, like every media player's
                    // (the PAUSED banner art was tried here and reverted -
                    // the plain glyph is what the user wants).
                    g.colour = PAUSE_COLOUR
                    val barW = rect.width() * 0.16f
                    val barH = rect.height() * 0.5f
                    val gap = rect.width() * 0.20f
                    g.fillRect(cx - gap / 2 - barW, cy - barH / 2, barW, barH)
                    g.fillRect(cx + gap / 2, cy - barH / 2, barW, barH)
                }
                LetterboxAction.OPEN_DOORS -> drawFitted(
                    art.doorOpen, cx, cy, rect.width(),
                    if (doorsOperable) BUTTON_TINT else BUTTON_TINT_DIM
                )
                LetterboxAction.CLOSE_DOORS -> drawFitted(
                    art.doorClose, cx, cy, rect.width(),
                    if (doorsOperable) BUTTON_TINT else BUTTON_TINT_DIM
                )
                LetterboxAction.SAVE_STATIONS -> drawFitted(
                    if (stationsSaved) art.assignOn else art.assignOff,
                    cx, cy, rect.width(), BUTTON_TINT
                )
                LetterboxAction.RETURN_STATIONS -> drawFitted(
                    art.returnOff, cx, cy, rect.width(),
                    if (stationsSaved) BUTTON_TINT else BUTTON_TINT_DIM
                )
            }
        }

        ShaderProgramme.SHADER_SCREEN_SIZE.set(oldW, oldH)
    }

    /** Draw an image aspect-fit inside a box centred on (cx, cy). */
    private fun drawFitted(img: Image, cx: Float, cy: Float, box: Float, filter: Colour) {
        val s = box / maxOf(img.width, img.height)
        val w = img.width * s
        val h = img.height * s
        img.draw(cx - w / 2f, cy - h / 2f, w, h, filter)
    }

    /**
     * The letterbox buttons' visual rects in surface pixels, or empty
     * when hidden: outside a run, or when no letterbox bar is wide
     * enough (16:9-ish screens - the in-game top-bar menu button is the
     * pause affordance there).
     *
     * Layout (user-specified): right bar top-to-bottom PAUSE / OPEN ALL
     * DOORS / CLOSE ALL DOORS; left bar's top slot is reserved (L1, not
     * used yet) with SAVE STATIONS / RETURN TO STATIONS below it.
     */
    private fun computeLetterboxButtons(): List<Pair<LetterboxAction, RectF>> {
        if (!game.isInFlight())
            return emptyList()

        val sideBarW = viewX
        if (sideBarW >= MIN_BAR_PX) {
            // sensorLandscape phones: two columns in the side bars. The
            // surface excludes any camera-cutout inset (S24 Ultra in HD+:
            // 1496x720 -> a 108px bar), so the buttons never collide with
            // the camera hole; size yields to the bar width instead of a
            // hard floor so cutout-inset devices still get buttons.
            val size = minOf(surfaceH * BUTTON_SIZE_FRAC, sideBarW * BAR_FIT_FRAC)
                .coerceIn(MIN_BUTTON_PX, MAX_BUTTON_PX)
            val gap = (surfaceH - 3 * size) / 4f
            fun slotY(i: Int) = gap * (i + 1) + i * size
            val rightX = surfaceW - sideBarW + (sideBarW - size) / 2f
            val leftX = (sideBarW - size) / 2f
            return listOf(
                LetterboxAction.PAUSE_TOGGLE to RectF(rightX, slotY(0), rightX + size, slotY(0) + size),
                LetterboxAction.OPEN_DOORS to RectF(rightX, slotY(1), rightX + size, slotY(1) + size),
                LetterboxAction.CLOSE_DOORS to RectF(rightX, slotY(2), rightX + size, slotY(2) + size),
                // L1 (top-left) is reserved for a future button.
                LetterboxAction.SAVE_STATIONS to RectF(leftX, slotY(1), leftX + size, slotY(1) + size),
                LetterboxAction.RETURN_STATIONS to RectF(leftX, slotY(2), leftX + size, slotY(2) + size),
            )
        }

        val bottomBarH = viewY
        if (bottomBarH >= MIN_BAR_PX) {
            // Portrait-ish surfaces: one row, right-aligned in the bar.
            val size = minOf(surfaceW * BUTTON_SIZE_FRAC, bottomBarH * BAR_FIT_FRAC)
                .coerceIn(MIN_BUTTON_PX, MAX_BUTTON_PX)
            val order = listOf(
                LetterboxAction.PAUSE_TOGGLE,
                LetterboxAction.OPEN_DOORS,
                LetterboxAction.CLOSE_DOORS,
                LetterboxAction.SAVE_STATIONS,
                LetterboxAction.RETURN_STATIONS,
            )
            val spacing = size + 12f
            var x = surfaceW - order.size * size - (order.size - 1) * 12f - 16f
            val y = surfaceH - bottomBarH + (bottomBarH - size) / 2f
            return order.map { action ->
                val r = RectF(x, y, x + size, y + size)
                x += spacing
                action to r
            }
        }

        return emptyList()
    }

    /** The letterbox band a button's visual rect lives in, for hit-rect clamping. */
    private fun bandRectFor(visual: RectF): RectF {
        val surfaceWf = surfaceW.toFloat()
        val surfaceHf = surfaceH.toFloat()
        val cx = visual.centerX()
        return when {
            cx < viewX -> RectF(0f, 0f, viewX.toFloat(), surfaceHf)
            cx > surfaceWf - viewX -> RectF(surfaceWf - viewX, 0f, surfaceWf, surfaceHf)
            else -> RectF(0f, surfaceHf - viewY, surfaceWf, surfaceHf)
        }
    }

    override fun exit() {
        game.shutdown()
        finishCallback()
    }

    /**
     * The overlay's button art, built once per in-flight InGameState (its
     * own image cache owns the textures and frees them on shutdown).
     */
    private var overlayArtState: Any? = null
    private var overlayArt: LetterboxArt? = null

    private class LetterboxArt(
        val assignOff: Image,
        val assignOn: Image,
        val returnOff: Image,
        val doorOpen: Image,
        val doorClose: Image,
    )

    private fun letterboxArt(state: InGameState): LetterboxArt? {
        if (overlayArtState === state && overlayArt != null)
            return overlayArt
        try {
            val art = LetterboxArt(
                assignOff = state.getImg("img/ipad/statusUI/button_station_assign_off.png"),
                assignOn = state.getImg("img/ipad/statusUI/button_station_assign_on.png"),
                returnOff = state.getImg("img/ipad/statusUI/button_station_return_off.png"),
                // The door glyphs live in the button-state variants
                // (_off/_on/_select2) - there is no plain
                // button_door_top/bottom.png, and a missing getImg name
                // silently returns the nullResource hazard-stripe
                // texture. Crop the 32x32 normal-state (_on) glyphs:
                // top plate = close all, bottom plate = open all
                // (Doors.kt's DoorButton ctor args).
                doorOpen = state.getImg("img/systemUI/button_door_bottom_on.png")
                    .getSubImage(4, 27, 32, 32),
                doorClose = state.getImg("img/systemUI/button_door_top_on.png")
                    .getSubImage(4, 4, 32, 32),
            )
            overlayArtState = state
            overlayArt = art
            return art
        } catch (ex: Throwable) {
            android.util.Log.w(TAG, "letterbox overlay art load failed", ex)
            overlayArtState = null
            overlayArt = null
            return null
        }
    }

    override fun setCursor(cursor: Cursor?) {
        // No OS cursors on Android
    }

    companion object {
        const val GAME_W = 1280
        const val GAME_H = 720
        private const val TAG = "XFTL"

        // Letterbox buttons: bars narrower than MIN_BAR_PX hide them;
        // otherwise their visual size follows the surface's short axis but
        // yields to the bar width (cutout insets shrink it - S24 Ultra
        // HD+ has a 108px bar). Three buttons stack per bar, so the size
        // fraction is smaller than the old single-pause-button one.
        private const val MIN_BAR_PX = 72
        private const val MIN_BUTTON_PX = 44f
        private const val MAX_BUTTON_PX = 120f
        private const val BUTTON_SIZE_FRAC = 0.085f
        private const val BAR_FIT_FRAC = 0.72f
        private const val HIT_INFLATE_PX = 24f
        private val BUTTON_TINT = Colour(255, 255, 255, 235)
        private val BUTTON_TINT_DIM = Colour(255, 255, 255, 100)
        private val PAUSE_COLOUR = Colour(200, 200, 200, 220)
    }
}

/** What a letterbox button does when tapped. */
enum class LetterboxAction {
    PAUSE_TOGGLE,
    OPEN_DOORS,
    CLOSE_DOORS,
    SAVE_STATIONS,
    RETURN_STATIONS,
}

/** A letterbox button's action + tappable rect in surface pixels. */
data class LetterboxButton(val action: LetterboxAction, val rect: RectF)

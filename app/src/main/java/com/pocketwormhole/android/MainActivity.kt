package com.pocketwormhole.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.opengl.EGL14
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.PlatformSpecific
import xyz.znix.xftl.sys.XftlResources
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var statusText: TextView
    private var surfaceView: GameSurfaceView? = null
    private var container: AndroidGameContainer? = null
    private lateinit var input: AndroidInput

    private var awaitingPicker = false
    private var gameStarted = false

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Capture any future crash to storage before the process dies,
        // and tee the app's logcat to session files for post-mortem debugging
        CrashCatcher.install(applicationContext)
        LogTee.start(applicationContext)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Audio debug dumps (enabled by marker file via adb)
        org.lwjgl.openal.SoftAL.dumpDir = filesDir

        val baseDir: Path = filesDir.toPath()
        PlatformSpecific.setBaseDir(baseDir)

        // Route engine resource loads to the APK assets
        XftlResources.opener = { path ->
            try {
                assets.open("resources/$path")
            } catch (e: Exception) {
                null
            }
        }

        input = AndroidInput()

        root = FrameLayout(this)
        statusText = TextView(this).apply {
            text = "FTL\n\nChecking for game data..."
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        root.addView(statusText)
        setContentView(root)

        hideSystemUi()

        // Try to start the game; if there's no ftl.dat yet, ask for it.
        val datPath = checkForFtlDat()
        if (datPath != null) {
            startGame()
        } else {
            statusText.text = "FTL\n\nFirst launch: this app needs the ftl.dat file " +
                    "from your copy of FTL: Faster Than Light.\n\n" +
                    "Tap anywhere to choose the file (e.g. from your PC's FTL install " +
                    "folder: FTL Faster Than Light\\ftl.dat)."
            root.setOnClickListener {
                if (!awaitingPicker) {
                    launchPicker()
                }
            }
        }
    }

    private fun checkForFtlDat(): Path? {
        return try {
            MainGame.findFtlDat()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "ftl.dat check failed", e)
            null
        }
    }

    private fun launchPicker() {
        awaitingPicker = true
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
        }
        startActivityForResult(intent, PICK_FTL_DAT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FTL_DAT) return
        awaitingPicker = false

        val uri: Uri = data?.data ?: run {
            finish()
            return
        }
        if (resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }

        statusText.text = "Copying game data...\n(this happens only once, ~250MB)"

        thread {
            try {
                val outFile = File(filesDir, "ftl.dat")
                contentResolver.openInputStream(uri)?.use { ins ->
                    outFile.outputStream().use { outs ->
                        ins.copyTo(outs, 1 shl 16)
                    }
                } ?: throw RuntimeException("Could not open selected file")

                // Write the path file for the engine
                val saveDir = filesDir.toPath().resolve("ProjectWormhole")
                Files.createDirectories(saveDir)
                Files.write(
                    saveDir.resolve("ftl-path.txt"),
                    outFile.absolutePath.toByteArray(StandardCharsets.UTF_8)
                )

                runOnUiThread {
                    statusText.visibility = View.GONE
                    startGame()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Copy of ftl.dat failed", e)
                runOnUiThread {
                    statusText.text = "Failed to copy ftl.dat:\n${e.message}\n\nTap to try again."
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startGame() {
        if (gameStarted) return
        gameStarted = true

        statusText.visibility = View.GONE

        val game = MainGame(MainGame.CommandLineArgs())
        val container = AndroidGameContainer(game, input) { finish() }
        this.container = container

        val view = GameSurfaceView(this, container, input)
        surfaceView = view
        root.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.requestFocus()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Handled in onKeyDown; keep the override to prevent default (exit)
        // behaviour when the game is running.
        if (surfaceView == null) {
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val sv = surfaceView
        if (sv != null) {
            val mapped = when (keyCode) {
                android.view.KeyEvent.KEYCODE_BACK -> xyz.znix.xftl.sys.Input.KEY_ESCAPE
                android.view.KeyEvent.KEYCODE_ESCAPE -> xyz.znix.xftl.sys.Input.KEY_ESCAPE
                android.view.KeyEvent.KEYCODE_SPACE -> xyz.znix.xftl.sys.Input.KEY_SPACE
                android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> xyz.znix.xftl.sys.Input.KEY_ENTER
                android.view.KeyEvent.KEYCODE_DPAD_UP -> xyz.znix.xftl.sys.Input.KEY_UP
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> xyz.znix.xftl.sys.Input.KEY_DOWN
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> xyz.znix.xftl.sys.Input.KEY_LEFT
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> xyz.znix.xftl.sys.Input.KEY_RIGHT
                android.view.KeyEvent.KEYCODE_TAB -> xyz.znix.xftl.sys.Input.KEY_TAB
                android.view.KeyEvent.KEYCODE_GRAVE -> xyz.znix.xftl.sys.Input.KEY_GRAVE
                in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z ->
                    xyz.znix.xftl.sys.Input.KEY_A + (keyCode - android.view.KeyEvent.KEYCODE_A)
                else -> -1
            }
            if (mapped != -1) {
                // Letters and symbols carry the character they type, so
                // text-entry UIs (the debug console) work over adb keyboards.
                val typed = when (keyCode) {
                    android.view.KeyEvent.KEYCODE_GRAVE -> '`'
                    android.view.KeyEvent.KEYCODE_SPACE -> ' '
                    in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z ->
                        'a' + (keyCode - android.view.KeyEvent.KEYCODE_A)
                    else -> 0.toChar()
                }
                sv.queueEvent { input.injectKeyPress(mapped, typed) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    companion object {
        private const val TAG = "XFTL"
        private const val PICK_FTL_DAT = 4242
    }
}

/**
 * The GL surface. Renders the game and feeds touch input to the AndroidInput.
 */
class GameSurfaceView(
    activity: MainActivity,
    private val container: AndroidGameContainer,
    private val input: AndroidInput
) : GLSurfaceView(activity) {

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setEGLConfigChooser(FallbackConfigChooser())
        setRenderer(container.renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * Tries increasingly modest EGL configs until the driver provides one.
     *
     * The engine renders every frame into its own offscreen FBO with a
     * guaranteed 24-bit depth + 8-bit stencil attachment, so the window
     * surface doesn't need them - this keeps older GLES stacks (API 26
     * emulators, old devices) from rejecting the surface outright.
     */
    private class FallbackConfigChooser : EGLConfigChooser {
        // Colour/depth/stencil combos, tried in decreasing strictness.
        private val combos = arrayOf(
            intArrayOf(8, 8, 8, 8, 24, 8),
            intArrayOf(8, 8, 8, 8, 24, 0),
            intArrayOf(8, 8, 8, 8, 16, 8),
            intArrayOf(8, 8, 8, 8, 16, 0),
            intArrayOf(5, 6, 5, 0, 24, 8),
            intArrayOf(5, 6, 5, 0, 16, 8),
            intArrayOf(5, 6, 5, 0, 16, 0)
        )

        // Some stacks (API 26 emulator images) only allow creating a GLES 3.0
        // context on a config that advertises ES3 support, so the ES3
        // renderable type is tried before the plain ES2 one.
        private val renderableTypes = intArrayOf(
            0x40, // EGL_OPENGL_ES3_BIT (not exposed by android.opengl.EGL14)
            EGL14.EGL_OPENGL_ES2_BIT
        )

        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            for (rt in renderableTypes) {
                for (c in combos) {
                    val attribs = intArrayOf(
                        EGL10.EGL_RED_SIZE, c[0],
                        EGL10.EGL_GREEN_SIZE, c[1],
                        EGL10.EGL_BLUE_SIZE, c[2],
                        EGL10.EGL_ALPHA_SIZE, c[3],
                        EGL10.EGL_DEPTH_SIZE, c[4],
                        EGL10.EGL_STENCIL_SIZE, c[5],
                        EGL10.EGL_RENDERABLE_TYPE, rt,
                        EGL10.EGL_NONE
                    )
                    val num = IntArray(1)
                    if (egl.eglChooseConfig(display, attribs, null, 0, num) && num[0] > 0) {
                        val configs = arrayOfNulls<EGLConfig>(num[0])
                        if (egl.eglChooseConfig(display, attribs, configs, num[0], num) && num[0] > 0) {
                            return configs[0]!!
                        }
                    }
                }
            }

            throw IllegalArgumentException("eglChooseConfig failed: no supported EGL config")
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        input.onTouchEvent(event)
        performClick()
        return true
    }

    @Suppress("UNUSED_PARAMETER")
    override fun performClick(): Boolean {
        return super.performClick()
    }
}

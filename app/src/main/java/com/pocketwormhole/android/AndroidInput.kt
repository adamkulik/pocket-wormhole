package com.pocketwormhole.android

import android.view.MotionEvent
import org.lwjgl.glfw.GLFW
import org.newdawn.slick.InputListener
import org.newdawn.slick.KeyListener
import org.newdawn.slick.MouseListener
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.sys.Input
import java.util.Collections

/**
 * Touch-to-mouse input adapter, mirroring the event dispatch logic of the
 * engine's LWJGLInput (press/release/click-vs-drag detection, drag/move,
 * wheel).
 *
 * Right click = long-press (~450 ms).
 *
 * A synthetic right click holds the right button down for exactly one update
 * frame: the in-game state (InGameState) detects clicks by polling
 * [isMouseButtonDown], so the button must be seen down and then up.
 *
 * Touch events arrive on the UI thread and are queued; they are dispatched to
 * game listeners on the GL thread when [drainEvents] is called each frame.
 */
class AndroidInput : Input {
    override var mouseX: Int = 0
        private set
    override var mouseY: Int = 0
        private set

    private val mouseClickPos = Point(0, 0)

    private val pendingKeyPresses = BooleanArray(GLFW.GLFW_KEY_LAST + 1)
    private val buttonsDown = BooleanArray(3)

    private val keyListeners = ArrayList<KeyListener>()
    private val mouseListeners = ArrayList<MouseListener>()

    private val eventQueue = Collections.synchronizedList(ArrayList<() -> Unit>())

    /** Events dispatched on the frame after [eventQueue] — presses are held
     *  here so the engine gets the position-setting move one update earlier
     *  (its hover/click-target state is recomputed at the end of each update;
     *  a press arriving in the same frame as the move sees stale hover). */
    private val nextFrameQueue = Collections.synchronizedList(ArrayList<() -> Unit>())

    /**
     * The pending release+click, dispatched only after a frame has been
     * rendered since the press (the engine computes click-target hover state
     * at draw time, so the click must land after that draw).
     */
    private var pendingRelease: (() -> Unit)? = null

    /** True from ACTION_DOWN until the press lambda has been dispatched. */
    @Volatile
    private var pressQueued = false

    /** True while the left press is still waiting in [nextFrameQueue]. */
    @Volatile
    private var pressHeld = false

    @Volatile
    private var pressRendered = true

    fun markRendered() {
        pressRendered = true
    }

    // Two-finger scrolling
    private var scrollPointerId = -1
    private var scrollLastY = 0f

    // Long-press right click
    private var longPressPending = false
    private var longPressFired = false
    private var longPressX = 0f
    private var longPressY = 0f
    private var longPressAt = 0L

    // A synthetic right click in flight. Phases: 0 = idle, 1 = press queued
    // (not yet dispatched), 2 = press dispatched (release due next frame).
    // The press must dispatch in one drainEvents and the release happen at the
    // top of a later one, so the engine's state-polling UI sees the button
    // down for exactly one update.
    @Volatile
    private var rightClickState = 0
    private var rightClickX = 0
    private var rightClickY = 0

    // Viewport mapping (set by the renderer)
    @Volatile var viewTransform: TouchTransform = TouchTransform(0f, 0f, 1f, 1f)

    var viewportW: Int = 1280
    var viewportH: Int = 720

    /** Callback into the view, to schedule long-press checks. */
    var longPressCallback: ((x: Int, y: Int) -> Unit)? = null

    class TouchTransform(val offX: Float, val offY: Float, val scaleX: Float, val scaleY: Float)

    // ------------------------------------------------------------------ //
    // Called from the GL thread each frame
    // ------------------------------------------------------------------ //

    fun drainEvents() {
        // Finish a synthetic right click dispatched on a previous frame: the
        // engine's in-game state polls button state, so the right button must
        // be down for exactly one update, then released.
        if (rightClickState == 2) {
            rightClickState = 0
            buttonsDown[Input.MOUSE_RIGHT_BUTTON] = false
            val rx = rightClickX
            val ry = rightClickY
            iterate(mouseListeners) {
                it.mouseReleased(Input.MOUSE_RIGHT_BUTTON, rx, ry)
                it.mouseClicked(Input.MOUSE_RIGHT_BUTTON, rx, ry, 1)
            }
        }

        // Dispatch a pending click only once a frame has been rendered since its
        // press was dispatched, so hover/click-target state is up to date.
        if (pressRendered && pressQueued && buttonsDown[Input.MOUSE_LEFT_BUTTON]) {
            val release = pendingRelease
            if (release != null) {
                pendingRelease = null
                release()
            }
        }

        val events: List<() -> Unit>
        synchronized(eventQueue) {
            events = ArrayList(eventQueue)
            eventQueue.clear()
        }
        for (e in events) e()

        // Events held back at the previous frame dispatch now.
        synchronized(nextFrameQueue) {
            if (nextFrameQueue.isNotEmpty()) {
                eventQueue.addAll(nextFrameQueue)
                nextFrameQueue.clear()
            }
        }
    }

    fun postUpdate() {
        // Handle long-press right click
        if (longPressPending && System.nanoTime() - longPressAt > LONG_PRESS_NANOS) {
            longPressPending = false
            longPressFired = true
            val x = longPressX.toInt()
            val y = longPressY.toInt()
            eventQueue.add {
                fireRightClick(x, y)
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Called from the UI thread
    // ------------------------------------------------------------------ //

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) != primaryPointerId(event) && event.pointerCount == 2) {
                    // Second finger: start scroll tracking
                    scrollPointerId = event.getPointerId(idx)
                    scrollLastY = event.getY(idx)
                    return
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // Primary press
                    val (lx, ly) = toLogical(event.x, event.y)

                    // Synthesize a move event first: the engine (like the
                    // original game) computes hover/click-target state at
                    // draw time from the previous mouse position. A quick tap
                    // has no ACTION_MOVE, so without this the click would
                    // land against a stale hover state.
                    val lastX = mouseX
                    val lastY = mouseY
                    mouseX = lx
                    mouseY = ly
                    mouseClickPos.set(lx, ly)
                    longPressPending = true
                    longPressFired = false
                    longPressX = lx.toFloat()
                    longPressY = ly.toFloat()
                    val now = System.nanoTime()
                    longPressAt = now

                    pressQueued = true
                    pressHeld = true
                    eventQueue.add {
                        if (lastX != mouseX || lastY != mouseY) {
                            iterate(mouseListeners) { l -> l.mouseMoved(lastX, lastY, mouseX, mouseY) }
                        }
                    }
                    nextFrameQueue.add {
                        pressHeld = false
                        buttonsDown[Input.MOUSE_LEFT_BUTTON] = true
                        pressRendered = false
                        iterate(mouseListeners) { it.mousePressed(Input.MOUSE_LEFT_BUTTON, mouseX, mouseY) }
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                if (pid == scrollPointerId) {
                    val y = event.y
                    val dyPixels = scrollLastY - y
                    scrollLastY = y
                    val wheel = (dyPixels * SCROLL_SCALE).toInt()
                    if (wheel != 0) {
                        eventQueue.add {
                            iterate(mouseListeners) { it.mouseWheelMoved(wheel) }
                        }
                    }
                    return
                }
                if (pid != primaryPointerId(event)) return

                val (lx, ly) = toLogical(event.x, event.y)
                if (longPressPending) {
                    val dx = lx - longPressX
                    val dy = ly - longPressY
                    if (dx * dx + dy * dy > DRAG_CANCEL_SQ) {
                        longPressPending = false
                        // A drag starts: dispatch a left press still held for
                        // the next frame right away (hover state doesn't
                        // matter for drags, latency does).
                        if (pressHeld) {
                            pressHeld = false
                            synchronized(nextFrameQueue) {
                                eventQueue.addAll(nextFrameQueue)
                                nextFrameQueue.clear()
                            }
                        }
                    }
                }

                val lastX = mouseX
                val lastY = mouseY
                mouseX = lx
                mouseY = ly

                val dragging = buttonsDown[Input.MOUSE_LEFT_BUTTON]
                eventQueue.add {
                    val draggingNow = buttonsDown[Input.MOUSE_LEFT_BUTTON]
                    iterate(mouseListeners) { l ->
                        when (draggingNow) {
                            true -> l.mouseDragged(lastX, lastY, mouseX, mouseY)
                            false -> l.mouseMoved(lastX, lastY, mouseX, mouseY)
                        }
                    }
                    @Suppress("UNUSED_VARIABLE")
                    if (!dragging) { /* no-op, keeps compiler quiet */ }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                if (pid == scrollPointerId) {
                    scrollPointerId = -1
                    return
                }
                if (pid != primaryPointerId(event)) return
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val (lx, ly) = toLogical(event.x, event.y)
                    mouseX = lx
                    mouseY = ly
                    val wasLongPress = longPressFired
                    longPressPending = false
                    longPressFired = false
                    val clickX = mouseClickPos.x
                    val clickY = mouseClickPos.y
                    val dist = Point(clickX, clickY).distToSq(mouseX, mouseY)
                    val isClick = dist <= CLICK_DISTANCE * CLICK_DISTANCE && !wasLongPress

                    pendingRelease = {
                        pressQueued = false
                        buttonsDown[Input.MOUSE_LEFT_BUTTON] = false
                        iterate(mouseListeners) { it.mouseReleased(Input.MOUSE_LEFT_BUTTON, mouseX, mouseY) }

                        if (isClick) {
                            iterate(mouseListeners) { it.mouseClicked(Input.MOUSE_LEFT_BUTTON, mouseX, mouseY, 1) }
                        }
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressPending = false
                longPressFired = false
                scrollPointerId = -1
                pendingRelease = null
                pressQueued = false
                // Drop a press that hasn't dispatched yet, or it would press
                // a button nobody is touching any more.
                pressHeld = false
                synchronized(nextFrameQueue) { nextFrameQueue.clear() }
                eventQueue.add {
                    buttonsDown.fill(false)
                }
            }
        }
    }

    private fun primaryPointerId(event: MotionEvent): Int {
        // The first pointer that went down (index 0 when it's still around)
        return event.getPointerId(0)
    }

    /**
     * Queue a synthetic right click: press is dispatched on the next frame and
     * held for exactly one update (so state-polling UI registers it), then
     * released+clicked on the frame after — mirroring the desktop container.
     */
    private fun fireRightClick(x: Int, y: Int) {
        if (rightClickState != 0) return
        rightClickState = 1
        rightClickX = x
        rightClickY = y
        android.util.Log.d("XFTL", "Right click (synthetic) at $x,$y")
        // Held to the next frame, like left presses: the engine recomputes
        // hover state at the end of the update in which the position changed.
        nextFrameQueue.add {
            if (rightClickState != 1) return@add
            rightClickState = 2
            buttonsDown[Input.MOUSE_RIGHT_BUTTON] = true
            iterate(mouseListeners) { it.mousePressed(Input.MOUSE_RIGHT_BUTTON, x, y) }
        }
    }

    private fun toLogical(x: Float, y: Float): Pair<Int, Int> {
        val t = viewTransform
        return Pair(((x - t.offX) * t.scaleX).toInt(), ((y - t.offY) * t.scaleY).toInt())
    }

    // ------------------------------------------------------------------ //
    // Input interface
    // ------------------------------------------------------------------ //

    override fun isMouseButtonDown(button: Int): Boolean = buttonsDown.getOrNull(button) ?: false

    override fun isKeyPressed(key: Int): Boolean {
        if (key < 0 || key >= pendingKeyPresses.size) return false
        val old = pendingKeyPresses[key]
        pendingKeyPresses[key] = false
        return old
    }

    override fun isKeyDown(key: Int): Boolean = false

    override fun addListener(listener: InputListener) {
        if (listener is KeyListener) keyListeners.add(listener)
        if (listener is MouseListener) mouseListeners.add(listener)
    }

    override fun removeAllListeners() {
        keyListeners.clear()
        mouseListeners.clear()
    }

    override fun clearInputPressedRecord() {
        pendingKeyPresses.fill(false)
    }

    /** Inject a key press (e.g. back button -> escape). GL-thread safe. */
    fun injectKeyPress(key: Int, c: Char = 0.toChar()) {
        if (key < 0 || key >= pendingKeyPresses.size) return
        eventQueue.add {
            pendingKeyPresses[key] = true
            iterate(keyListeners) { it.keyPressed(key, c) }
            // Games often want release too, to keep state consistent
            iterate(keyListeners) { it.keyReleased(key, 0.toChar()) }
        }
    }

    private inline fun <T> iterate(list: List<T>, callback: (T) -> Unit) {
        var i = 0
        while (i < list.size) {
            callback(list[i])
            i++
        }
    }

    companion object {
        private const val CLICK_DISTANCE = 4
        private const val SCROLL_SCALE = 3.0f
        private const val DRAG_CANCEL_SQ = 12f * 12f
        private const val LONG_PRESS_NANOS = 450_000_000L
    }
}

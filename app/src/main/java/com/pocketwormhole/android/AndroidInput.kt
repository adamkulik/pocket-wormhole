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
 * Right click = long-press (450 ms of INTERACTIVE holding - measured in
 * rendered frame time, so render hitches don't convert a tap the user is
 * holding through into a right click).
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

    // Interactive time (ms of actually-rendered frames) since ACTION_DOWN.
    // Measured as capped per-frame gaps rather than wall time: the natural
    // reaction to a frozen screen is to keep holding, and wall-clock timing
    // converted those taps into right clicks - which most UI ignores, so
    // the tap silently did nothing (issue #33: the button highlights but
    // never switches).
    private var longPressElapsedMs = 0f

    // The pointer id driving the current press, or -1. All press/move/
    // release handling keys on this id; other pointers are only allowed to
    // scroll. (Keying on pointer INDEX 0 broke when a second finger - often
    // a palm edge while tapping - lifted first: the release was swallowed,
    // the left button stuck down, and later taps clicked against stale
    // state or not at all.)
    private var pressedPointerId = -1

    // Frame timing (set by drainEvents), for long-press accumulation and
    // the flight recorder.
    private var lastDrainNanos = 0L
    private var frameGapMs = 0f

    /** Directory holding the debug stall_ms marker (filesDir), or null. */
    var stallMarkerDir: java.io.File? = null

    /** Debug builds only: enable the stall_ms test hook. */
    var debugInputHooks: Boolean = false

    // ------------------------------------------------------------------ //
    // Input flight recorder: a ring of recent input events + decisions,
    // dumped to the log (which LogTee captures into log-session-*.txt)
    // when something suspicious happens - long-press conversion, a release
    // that isn't a click, cancellation, a lost release. Makes
    // non-reproducible "taps don't register" reports come with data.
    private val flightLog = ArrayDeque<String>()
    private var flightBase = 0L
    private var lastFlightDump = 0L

    private fun flight(msg: String) {
        val t = android.os.SystemClock.uptimeMillis()
        if (flightBase == 0L) flightBase = t
        flightLog.addLast("${t - flightBase}ms $msg")
        if (flightLog.size > FLIGHT_LOG_SIZE) flightLog.removeFirst()
    }

    /** Dump the ring to logcat. */
    fun dumpFlightLog(reason: String) {
        android.util.Log.i("XFTL", "input flight log ($reason), last ${flightLog.size} events:")
        for (line in flightLog) android.util.Log.i("XFTL", "  $line")
    }

    private fun dumpFlightLogRateLimited(reason: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastFlightDump < 10_000L) return
        lastFlightDump = now
        dumpFlightLog(reason)
    }

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
        noteFrame()

        // Debug builds: a filesDir/stall_ms marker simulates a render hitch,
        // so tap-vs-hitch behaviour can be reproduced deterministically over
        // adb (write the marker, then touch and hold through the stall).
        // Only checked while a touch is in progress - the hitch lands
        // mid-tap, and there's no per-frame disk I/O when just playing.
        if (debugInputHooks && (pressQueued || buttonsDown[Input.MOUSE_LEFT_BUTTON])) {
            checkStallMarker()
        }

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
        // Handle long-press right click. The timer accumulates interactive
        // time (capped frame gaps - see longPressElapsedMs), so it
        // effectively pauses while the render thread is hitching.
        if (longPressPending) {
            longPressElapsedMs += frameGapMs
            if (longPressElapsedMs >= LONG_PRESS_MS) {
                longPressPending = false
                longPressFired = true
                val x = longPressX.toInt()
                val y = longPressY.toInt()
                flight("long-press fired after ${longPressElapsedMs.toInt()}ms interactive at $x,$y")
                dumpFlightLogRateLimited("long-press conversion")
                eventQueue.add {
                    fireRightClick(x, y)
                }
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
                val pid = event.getPointerId(idx)

                // A second finger (often a palm edge while tapping) is only
                // allowed to scroll - it must never press, move or release.
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    if (pid != pressedPointerId) {
                        scrollPointerId = pid
                        scrollLastY = event.getY(idx)
                        flight("POINTER_DOWN id=$pid (scroll) pointers=${event.pointerCount}")
                    }
                    return
                }

                // Primary press
                pressedPointerId = pid
                val (lx, ly) = toLogical(event.x, event.y)

                // Defensive: a previous gesture that never saw its release
                // (shouldn't happen now that releases are id-tracked, but a
                // stuck left button makes every later click gate open early
                // and fire against stale hover - the #33 symptom).
                if (buttonsDown[Input.MOUSE_LEFT_BUTTON] || pendingRelease != null) {
                    flight("DOWN while previous gesture incomplete " +
                        "(down=${buttonsDown[Input.MOUSE_LEFT_BUTTON]}, pendingRelease=${pendingRelease != null})")
                    pendingRelease = null
                    eventQueue.add { buttonsDown[Input.MOUSE_LEFT_BUTTON] = false }
                }

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
                longPressElapsedMs = 0f

                pressQueued = true
                pressHeld = true
                flight("DOWN id=$pid pos=$lx,$ly pointers=${event.pointerCount}")
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

            MotionEvent.ACTION_MOVE -> {
                // A single MOVE event carries every pointer's position, so
                // an in-progress scroll and the pressed pointer can both
                // move in the same event.
                if (scrollPointerId != -1) {
                    val scrollIdx = event.findPointerIndex(scrollPointerId)
                    if (scrollIdx != -1) {
                        val y = event.getY(scrollIdx)
                        val dyPixels = scrollLastY - y
                        scrollLastY = y
                        val wheel = (dyPixels * SCROLL_SCALE).toInt()
                        if (wheel != 0) {
                            eventQueue.add {
                                iterate(mouseListeners) { it.mouseWheelMoved(wheel) }
                            }
                        }
                    }
                }

                // Follow the pressed pointer; with no press in progress,
                // follow the first pointer (plain hover moves).
                val moveIdx = if (pressedPointerId != -1) event.findPointerIndex(pressedPointerId) else 0
                if (moveIdx == -1) return

                val (lx, ly) = toLogical(event.getX(moveIdx), event.getY(moveIdx))
                if (longPressPending) {
                    val dx = lx - longPressX
                    val dy = ly - longPressY
                    if (dx * dx + dy * dy > CLICK_SLOP * CLICK_SLOP) {
                        longPressPending = false
                        longPressElapsedMs = 0f
                        flight("drag started (moved >$CLICK_SLOP px) - long-press cancelled")
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
                    flight("POINTER_UP id=$pid (scroll ended) pointers=${event.pointerCount}")
                    return
                }
                // The press only ends when ITS pointer lifts - which for the
                // tracked pointer can be a POINTER_UP (second finger still
                // down), not just ACTION_UP. Swallowing that case used to
                // leave the left button stuck down, eating later taps.
                if (pid != pressedPointerId) {
                    flight("UP of untracked id=$pid ignored (pressed=$pressedPointerId)")
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // All pointers are gone but none of them matched -
                        // state is inconsistent; drop everything.
                        flight("all pointers gone without a tracked release - resetting")
                        dumpFlightLogRateLimited("lost release")
                        resetGestureState()
                    }
                    return
                }
                handleRelease(event, idx)
            }

            MotionEvent.ACTION_CANCEL -> {
                flight("CANCEL - resetting gesture state")
                dumpFlightLogRateLimited("touch cancelled")
                resetGestureState()
            }
        }
    }

    /**
     * The tracked pointer lifted: finish the gesture (queue the deferred
     * release+click, deciding tap vs drag vs long-press).
     */
    private fun handleRelease(event: MotionEvent, idx: Int) {
        pressedPointerId = -1
        val (lx, ly) = toLogical(event.getX(idx), event.getY(idx))
        mouseX = lx
        mouseY = ly
        val wasLongPress = longPressFired
        val heldMs = longPressElapsedMs.toInt()
        longPressPending = false
        longPressFired = false
        val clickX = mouseClickPos.x
        val clickY = mouseClickPos.y
        val dist = Point(clickX, clickY).distToSq(mouseX, mouseY)
        val isClick = dist <= CLICK_SLOP * CLICK_SLOP && !wasLongPress

        when {
            wasLongPress -> flight("UP pos=$lx,$ly: long-press (right click already sent)")
            isClick -> flight("UP pos=$lx,$ly: CLICK (moved %.1f px, held ~${heldMs}ms interactive)"
                .format(Math.sqrt(dist.toDouble())))
            else -> {
                val moved = Math.sqrt(dist.toDouble())
                flight("UP pos=$lx,$ly: release only (moved $moved px > slop $CLICK_SLOP) - drag end")
                dumpFlightLogRateLimited("tap moved ${moved}px past slop")
            }
        }

        pendingRelease = {
            pressQueued = false
            buttonsDown[Input.MOUSE_LEFT_BUTTON] = false
            iterate(mouseListeners) { it.mouseReleased(Input.MOUSE_LEFT_BUTTON, mouseX, mouseY) }

            if (isClick) {
                iterate(mouseListeners) { it.mouseClicked(Input.MOUSE_LEFT_BUTTON, mouseX, mouseY, 1) }
            }
        }
    }

    /**
     * Drop all gesture state - the touch system told us the gesture is over
     * (cancel), or it ended in a way we can't reconcile (an UP for a pointer
     * we weren't tracking).
     */
    private fun resetGestureState() {
        longPressPending = false
        longPressFired = false
        pressedPointerId = -1
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

    /** Called once per frame from [drainEvents]: measure the frame gap. */
    private fun noteFrame() {
        val now = System.nanoTime()
        frameGapMs = if (lastDrainNanos == 0L) 0f
        else ((now - lastDrainNanos) / 1_000_000f).coerceAtMost(FRAME_GAP_CAP_MS)
        if (lastDrainNanos != 0L) {
            val gapMs = (now - lastDrainNanos) / 1_000_000
            if (gapMs > 250) flight("frame gap ${gapMs}ms")
        }
        lastDrainNanos = now
    }

    /**
     * Debug-build test hook: if filesDir/stall_ms exists and a touch is in
     * progress, sleep the GL thread for that long once - simulating the
     * frame hitches slower devices hit - then delete the marker. Lets
     * taps-during-hitches be reproduced deterministically over adb.
     */
    private fun checkStallMarker() {
        val dir = stallMarkerDir ?: return
        val f = java.io.File(dir, "stall_ms")
        val ms = try {
            f.readText().trim().toLongOrNull()
        } catch (e: Exception) {
            null
        } ?: return
        // Only fire while a touch is in progress, so the hitch lands mid-tap.
        if (!pressQueued && !buttonsDown[Input.MOUSE_LEFT_BUTTON]) return
        f.delete()
        flight("simulated stall ${ms}ms (debug marker)")
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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
        // Tap-vs-drag slop, in logical px. ONE threshold for both decisions:
        // a release within this distance of the press is a click; movement
        // beyond it promotes a drag and cancels the long-press. The old pair
        // (click <= 4px, drag > 12px) had a dead zone where a finger rolling
        // 5-11px between down and up was neither a click nor a drag - a
        // silently eaten tap.
        private const val CLICK_SLOP = 10

        private const val SCROLL_SCALE = 3.0f

        // Long-press = this many ms of INTERACTIVE holding (see
        // longPressElapsedMs); wall-time measurement used to convert taps
        // held through render hitches into right clicks.
        private const val LONG_PRESS_MS = 450f

        // Per-frame cap on the long-press timer's accumulation: a single
        // huge frame gap counts as at most this much holding.
        private const val FRAME_GAP_CAP_MS = 100f

        private const val FLIGHT_LOG_SIZE = 128
    }
}

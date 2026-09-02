package xyz.znix.xftl.sys

import org.lwjgl.glfw.GLFW
import org.newdawn.slick.InputListener

/**
 * A representation of all user input.
 *
 * This is based around Slick's Input class.
 */
interface Input {
    val mouseX: Int
    val mouseY: Int

    /**
     * Check if a given mouse button is down
     *
     * @param button The index of the button to check (starting at 0)
     * @return True if the mouse button is down
     */
    fun isMouseButtonDown(button: Int): Boolean

    /**
     * Check if a particular key has been pressed since this method
     * was last called for the specified key
     *
     * @param key The key code of the key to check
     * @return True if the key has been pressed
     */
    fun isKeyPressed(key: Int): Boolean

    /**
     * Check if a particular key is down
     *
     * @param key The key code of the key to check
     * @return True if the key is down
     */
    fun isKeyDown(key: Int): Boolean

    /**
     * Add a listener to be notified of input events
     *
     * @param listener The listener to be notified
     */
    fun addListener(listener: InputListener)

    /**
     * Remove all the listeners from this input
     */
    fun removeAllListeners()

    /**
     * Clear all unacknowledged inputs to [isKeyPressed].
     */
    fun clearInputPressedRecord()

    @Suppress("unused", "SpellCheckingInspection")
    companion object {
        const val MOUSE_LEFT_BUTTON: Int = GLFW.GLFW_MOUSE_BUTTON_LEFT
        const val MOUSE_RIGHT_BUTTON: Int = GLFW.GLFW_MOUSE_BUTTON_RIGHT

        const val KEY_F1: Int = GLFW.GLFW_KEY_F1
        const val KEY_F2: Int = GLFW.GLFW_KEY_F2
        const val KEY_F3: Int = GLFW.GLFW_KEY_F3
        const val KEY_F4: Int = GLFW.GLFW_KEY_F4
        const val KEY_F5: Int = GLFW.GLFW_KEY_F5
        const val KEY_F6: Int = GLFW.GLFW_KEY_F6
        const val KEY_F7: Int = GLFW.GLFW_KEY_F7
        const val KEY_F8: Int = GLFW.GLFW_KEY_F8
        const val KEY_F9: Int = GLFW.GLFW_KEY_F9
        const val KEY_F10: Int = GLFW.GLFW_KEY_F10
        const val KEY_F11: Int = GLFW.GLFW_KEY_F11
        const val KEY_F12: Int = GLFW.GLFW_KEY_F12
        const val KEY_F13: Int = GLFW.GLFW_KEY_F13
        const val KEY_F14: Int = GLFW.GLFW_KEY_F14
        const val KEY_F15: Int = GLFW.GLFW_KEY_F15

        const val KEY_UP: Int = GLFW.GLFW_KEY_UP
        const val KEY_DOWN: Int = GLFW.GLFW_KEY_DOWN
        const val KEY_LEFT: Int = GLFW.GLFW_KEY_LEFT
        const val KEY_RIGHT: Int = GLFW.GLFW_KEY_RIGHT

        const val KEY_ENTER: Int = GLFW.GLFW_KEY_ENTER
        const val KEY_ESCAPE: Int = GLFW.GLFW_KEY_ESCAPE
        const val KEY_SPACE: Int = GLFW.GLFW_KEY_SPACE
        const val KEY_TAB: Int = GLFW.GLFW_KEY_TAB

        const val KEY_LBRACKET: Int = GLFW.GLFW_KEY_LEFT_BRACKET
        const val KEY_RBRACKET: Int = GLFW.GLFW_KEY_RIGHT_BRACKET
        const val KEY_HASH: Int = GLFW.GLFW_KEY_BACKSLASH // UK (#) vs US (\) layouts differ
        const val KEY_SEMICOLON: Int = GLFW.GLFW_KEY_SEMICOLON
        const val KEY_APOSTROPHE: Int = GLFW.GLFW_KEY_APOSTROPHE
        const val KEY_COMMA: Int = GLFW.GLFW_KEY_COMMA
        const val KEY_FULL_STOP: Int = GLFW.GLFW_KEY_PERIOD
        const val KEY_STROKE: Int = GLFW.GLFW_KEY_SLASH

        // ISO UK layout "|\" key
        // This key is mapped to GLFW_KEY_WORLD_1 on Mac, GLFW_KEY_WORLD_2
        // on Windows, and to either on Linux depending on whether you use X11 or Wayland.
        // See https://github.com/glfw/glfw/issues/2481
        // We'll solve this by re-mapping everything to WORLD_1 as it comes in from GLFW.
        const val KEY_BAR: Int = GLFW.GLFW_KEY_WORLD_1

        const val KEY_LSHIFT: Int = GLFW.GLFW_KEY_LEFT_SHIFT
        const val KEY_RSHIFT: Int = GLFW.GLFW_KEY_RIGHT_SHIFT
        const val KEY_LCTRL: Int = GLFW.GLFW_KEY_LEFT_CONTROL
        const val KEY_RCTRL: Int = GLFW.GLFW_KEY_RIGHT_CONTROL
        const val KEY_LALT: Int = GLFW.GLFW_KEY_LEFT_ALT
        const val KEY_RALT: Int = GLFW.GLFW_KEY_RIGHT_ALT
        const val KEY_LSUPER: Int = GLFW.GLFW_KEY_LEFT_SUPER
        const val KEY_RSUPER: Int = GLFW.GLFW_KEY_RIGHT_SUPER

        const val KEY_BACK: Int = GLFW.GLFW_KEY_BACKSPACE
        const val KEY_DELETE: Int = GLFW.GLFW_KEY_DELETE
        const val KEY_INSERT: Int = GLFW.GLFW_KEY_INSERT
        const val KEY_END: Int = GLFW.GLFW_KEY_END
        const val KEY_HOME: Int = GLFW.GLFW_KEY_HOME
        const val KEY_PG_UP: Int = GLFW.GLFW_KEY_PAGE_UP
        const val KEY_PG_DN: Int = GLFW.GLFW_KEY_PAGE_DOWN
        const val KEY_GRAVE: Int = GLFW.GLFW_KEY_GRAVE_ACCENT
        const val KEY_MENU: Int = GLFW.GLFW_KEY_MENU

        const val KEY_A: Int = GLFW.GLFW_KEY_A
        const val KEY_B: Int = GLFW.GLFW_KEY_B
        const val KEY_C: Int = GLFW.GLFW_KEY_C
        const val KEY_D: Int = GLFW.GLFW_KEY_D
        const val KEY_E: Int = GLFW.GLFW_KEY_E
        const val KEY_F: Int = GLFW.GLFW_KEY_F
        const val KEY_G: Int = GLFW.GLFW_KEY_G
        const val KEY_H: Int = GLFW.GLFW_KEY_H
        const val KEY_I: Int = GLFW.GLFW_KEY_I
        const val KEY_J: Int = GLFW.GLFW_KEY_J
        const val KEY_K: Int = GLFW.GLFW_KEY_K
        const val KEY_L: Int = GLFW.GLFW_KEY_L
        const val KEY_M: Int = GLFW.GLFW_KEY_M
        const val KEY_N: Int = GLFW.GLFW_KEY_N
        const val KEY_O: Int = GLFW.GLFW_KEY_O
        const val KEY_P: Int = GLFW.GLFW_KEY_P
        const val KEY_Q: Int = GLFW.GLFW_KEY_Q
        const val KEY_R: Int = GLFW.GLFW_KEY_R
        const val KEY_S: Int = GLFW.GLFW_KEY_S
        const val KEY_T: Int = GLFW.GLFW_KEY_T
        const val KEY_U: Int = GLFW.GLFW_KEY_U
        const val KEY_V: Int = GLFW.GLFW_KEY_V
        const val KEY_W: Int = GLFW.GLFW_KEY_W
        const val KEY_X: Int = GLFW.GLFW_KEY_X
        const val KEY_Y: Int = GLFW.GLFW_KEY_Y
        const val KEY_Z: Int = GLFW.GLFW_KEY_Z

        const val KEY_0 = GLFW.GLFW_KEY_0
        const val KEY_1 = GLFW.GLFW_KEY_1
        const val KEY_2 = GLFW.GLFW_KEY_2
        const val KEY_3 = GLFW.GLFW_KEY_3
        const val KEY_4 = GLFW.GLFW_KEY_4
        const val KEY_5 = GLFW.GLFW_KEY_5
        const val KEY_6 = GLFW.GLFW_KEY_6
        const val KEY_7 = GLFW.GLFW_KEY_7
        const val KEY_8 = GLFW.GLFW_KEY_8
        const val KEY_9 = GLFW.GLFW_KEY_9

        const val KEY_KEYPAD_0 = GLFW.GLFW_KEY_KP_0
        const val KEY_KEYPAD_1 = GLFW.GLFW_KEY_KP_1
        const val KEY_KEYPAD_2 = GLFW.GLFW_KEY_KP_2
        const val KEY_KEYPAD_3 = GLFW.GLFW_KEY_KP_3
        const val KEY_KEYPAD_4 = GLFW.GLFW_KEY_KP_4
        const val KEY_KEYPAD_5 = GLFW.GLFW_KEY_KP_5
        const val KEY_KEYPAD_6 = GLFW.GLFW_KEY_KP_6
        const val KEY_KEYPAD_7 = GLFW.GLFW_KEY_KP_7
        const val KEY_KEYPAD_8 = GLFW.GLFW_KEY_KP_8
        const val KEY_KEYPAD_9 = GLFW.GLFW_KEY_KP_9

        const val KEY_KEYPAD_ADD = GLFW.GLFW_KEY_KP_ADD
        const val KEY_KEYPAD_SUB = GLFW.GLFW_KEY_KP_SUBTRACT
        const val KEY_KEYPAD_MUL = GLFW.GLFW_KEY_KP_MULTIPLY
        const val KEY_KEYPAD_DIV = GLFW.GLFW_KEY_KP_DIVIDE
        const val KEY_KEYPAD_DEC = GLFW.GLFW_KEY_KP_DECIMAL
        const val KEY_KEYPAD_ENTER = GLFW.GLFW_KEY_KP_ENTER
        const val KEY_KEYPAD_EQ = GLFW.GLFW_KEY_KP_EQUAL
    }
}

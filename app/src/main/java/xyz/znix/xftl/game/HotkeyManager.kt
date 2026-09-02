package xyz.znix.xftl.game

import xyz.znix.xftl.sys.XftlResources

import org.jdom2.Element
import org.jdom2.input.SAXBuilder
import xyz.znix.xftl.GameText
import xyz.znix.xftl.requireAttributeValue
import xyz.znix.xftl.sys.Input

class HotkeyManager {
    val groups: List<HotkeyGroup>
    val byID: Map<String, Hotkey>

    // These are special-cased, and thus we have to fish them out here
    val keyDevConsole: Hotkey
    val keyFrameStep: Hotkey
    val keyFastForward: Hotkey

    init {
        groups = ArrayList()

        // TODO support modding these files
        val builder = com.pocketwormhole.android.SafeXML.builder()
                val doc = builder.build(XftlResources.open("assets/data/xftl_hotkeys.xml"))

        require(doc.rootElement.name == "hotkeys")

        for (groupElem in doc.rootElement.children) {
            require(groupElem.name == "group")

            groups += parseGroup(groupElem)
        }

        byID = groups.flatMap { it.hotkeys }.associateBy { it.id }
        keyDevConsole = byID.getValue(VanillaHotkeys.DEV_CONSOLE)
        keyFrameStep = byID.getValue(VanillaHotkeys.DEV_FRAME_STEP)
        keyFastForward = byID.getValue(VanillaHotkeys.DEV_FAST_FORWARD)
    }

    private fun parseGroup(elem: Element): HotkeyGroup {
        val id = elem.requireAttributeValue("id")
        val uiName = elem.requireAttributeValue("ui_name")
        val columnCount = elem.getAttributeValue("columns")?.toInt() ?: 1

        val hotkeys = ArrayList<Hotkey>()
        for (child in elem.children) {
            require(child.name == "hotkey")
            hotkeys.add(parseHotkey(child))
        }

        return HotkeyGroup(id, GameText.localised(uiName), columnCount, hotkeys)
    }

    private fun parseHotkey(elem: Element): Hotkey {
        val id = elem.requireAttributeValue("id")
        val uiName = elem.requireAttributeValue("ui_name")
        val defaultId = elem.getAttributeValue("default_bind")

        val default = defaultId?.let { HotkeyButton.BY_NAME[it] ?: error("Invalid default keybind $it") }

        return Hotkey(id, GameText.localised(uiName), default)
    }

    /**
     * Updates the [forward] and [reverse] map with the hotkey-to-key bindings, including defaults.
     */
    fun calculateBindings(
        profile: SaveProfile,
        forward: HashMap<HotkeyButton, Hotkey>,
        reverse: HashMap<Hotkey, HotkeyButton>
    ) {
        forward.clear()
        reverse.clear()

        val byId: Map<String, Hotkey> = groups
            .flatMap { it.hotkeys }
            .associateBy { it.id }

        // Add the default mappings
        for (hotkey in byId.values) {
            reverse[hotkey] = hotkey.default ?: continue
        }

        // Add our custom mappings
        for ((actionId, keyId) in profile.getKeybinds()) {
            val action = byId[actionId] ?: continue

            if (keyId == null) {
                reverse.remove(action)
                continue
            }

            val key = HotkeyButton.BY_NAME[keyId] ?: continue
            reverse[action] = key
        }

        // Build the forward bindings map.
        // Note some actions might overlap each other.
        for ((action, key) in reverse) {
            forward[key] = action
        }
    }
}

class HotkeyGroup(val id: String, val name: GameText, val columns: Int, val hotkeys: List<Hotkey>)

class Hotkey(val id: String, val name: GameText, val default: HotkeyButton?)

class HotkeyButton(val id: String, val keyID: Int, val text: GameText) {
    companion object {
        @JvmField
        val ALL = listOf(
            HotkeyButton("f1", Input.KEY_F1, GameText.localised("keycap_f1")),
            HotkeyButton("f2", Input.KEY_F2, GameText.localised("keycap_f2")),
            HotkeyButton("f3", Input.KEY_F3, GameText.localised("keycap_f3")),
            HotkeyButton("f4", Input.KEY_F4, GameText.localised("keycap_f4")),
            HotkeyButton("f5", Input.KEY_F5, GameText.localised("keycap_f5")),
            HotkeyButton("f6", Input.KEY_F6, GameText.localised("keycap_f6")),
            HotkeyButton("f7", Input.KEY_F7, GameText.localised("keycap_f7")),
            HotkeyButton("f8", Input.KEY_F8, GameText.localised("keycap_f8")),
            HotkeyButton("f9", Input.KEY_F9, GameText.localised("keycap_f9")),
            HotkeyButton("f10", Input.KEY_F10, GameText.localised("keycap_f10")),
            HotkeyButton("f11", Input.KEY_F11, GameText.localised("keycap_f11")),
            HotkeyButton("f12", Input.KEY_F12, GameText.localised("keycap_f12")),
            HotkeyButton("f13", Input.KEY_F13, GameText.localised("keycap_f13")),
            HotkeyButton("f14", Input.KEY_F14, GameText.localised("keycap_f14")),
            HotkeyButton("f15", Input.KEY_F15, GameText.localised("keycap_f15")),

            HotkeyButton("up", Input.KEY_UP, GameText.localised("keycap_up")),
            HotkeyButton("down", Input.KEY_DOWN, GameText.localised("keycap_down")),
            HotkeyButton("left", Input.KEY_LEFT, GameText.localised("keycap_left")),
            HotkeyButton("right", Input.KEY_RIGHT, GameText.localised("keycap_right")),

            HotkeyButton("enter", Input.KEY_ENTER, GameText.localised("keycap_enter")),
            HotkeyButton("escape", Input.KEY_ESCAPE, GameText.localised("keycap_escape")),
            HotkeyButton("space", Input.KEY_SPACE, GameText.localised("keycap_space")),
            HotkeyButton("tab", Input.KEY_TAB, GameText.localised("keycap_tab")),
            HotkeyButton("lshift", Input.KEY_LSHIFT, GameText.localised("keycap_leftshift")),
            HotkeyButton("rshift", Input.KEY_RSHIFT, GameText.localised("keycap_rightshift")),
            HotkeyButton("lctrl", Input.KEY_LCTRL, GameText.localised("keycap_leftcontrol")),
            HotkeyButton("rctrl", Input.KEY_RCTRL, GameText.localised("keycap_rightcontrol")),
            HotkeyButton("lalt", Input.KEY_LALT, GameText.localised("keycap_leftalt")),
            HotkeyButton("ralt", Input.KEY_RALT, GameText.localised("keycap_rightalt")),
            HotkeyButton("lsuper", Input.KEY_LSUPER, GameText.localised("keycap_leftsuper")),
            HotkeyButton("rsuper", Input.KEY_RSUPER, GameText.localised("keycap_rightsuper")),
            HotkeyButton("back", Input.KEY_BACK, GameText.localised("keycap_backspace")),
            HotkeyButton("delete", Input.KEY_DELETE, GameText.localised("keycap_delete")),
            HotkeyButton("insert", Input.KEY_INSERT, GameText.localised("keycap_insert")),
            HotkeyButton("end", Input.KEY_END, GameText.localised("keycap_end")),
            HotkeyButton("home", Input.KEY_HOME, GameText.localised("keycap_home")),
            HotkeyButton("pg_up", Input.KEY_PG_UP, GameText.localised("keycap_pageup")),
            HotkeyButton("pg_dn", Input.KEY_PG_DN, GameText.localised("keycap_pagedown")),
            HotkeyButton("backtick", Input.KEY_GRAVE, GameText.literal("`")),
            HotkeyButton("menu", Input.KEY_MENU, GameText.localised("keycap_menu")),

            // Unfortunately these don't show the right keys on eg the German QWERTZ layout,
            // but there's not really a good solution for that.
            HotkeyButton("lbracket", Input.KEY_LBRACKET, GameText.literal("[")),
            HotkeyButton("rbracket", Input.KEY_RBRACKET, GameText.literal("]")),
            HotkeyButton("hash", Input.KEY_HASH, GameText.literal("# or \\")),
            HotkeyButton("semicolon", Input.KEY_SEMICOLON, GameText.literal(";")),
            HotkeyButton("apostrophe", Input.KEY_APOSTROPHE, GameText.literal("'")),
            HotkeyButton("comma", Input.KEY_COMMA, GameText.literal(",")),
            HotkeyButton("full_stop", Input.KEY_FULL_STOP, GameText.literal(".")),
            HotkeyButton("stroke", Input.KEY_STROKE, GameText.literal("/")),
            HotkeyButton("bar", Input.KEY_BAR, GameText.literal("|")),

            HotkeyButton("a", Input.KEY_A, GameText.literal("a")),
            HotkeyButton("b", Input.KEY_B, GameText.literal("b")),
            HotkeyButton("c", Input.KEY_C, GameText.literal("c")),
            HotkeyButton("d", Input.KEY_D, GameText.literal("d")),
            HotkeyButton("e", Input.KEY_E, GameText.literal("e")),
            HotkeyButton("f", Input.KEY_F, GameText.literal("f")),
            HotkeyButton("g", Input.KEY_G, GameText.literal("g")),
            HotkeyButton("h", Input.KEY_H, GameText.literal("h")),
            HotkeyButton("i", Input.KEY_I, GameText.literal("i")),
            HotkeyButton("j", Input.KEY_J, GameText.literal("j")),
            HotkeyButton("k", Input.KEY_K, GameText.literal("k")),
            HotkeyButton("l", Input.KEY_L, GameText.literal("l")),
            HotkeyButton("m", Input.KEY_M, GameText.literal("m")),
            HotkeyButton("n", Input.KEY_N, GameText.literal("n")),
            HotkeyButton("o", Input.KEY_O, GameText.literal("o")),
            HotkeyButton("p", Input.KEY_P, GameText.literal("p")),
            HotkeyButton("q", Input.KEY_Q, GameText.literal("q")),
            HotkeyButton("r", Input.KEY_R, GameText.literal("r")),
            HotkeyButton("s", Input.KEY_S, GameText.literal("s")),
            HotkeyButton("t", Input.KEY_T, GameText.literal("t")),
            HotkeyButton("u", Input.KEY_U, GameText.literal("u")),
            HotkeyButton("v", Input.KEY_V, GameText.literal("v")),
            HotkeyButton("w", Input.KEY_W, GameText.literal("w")),
            HotkeyButton("x", Input.KEY_X, GameText.literal("x")),
            HotkeyButton("y", Input.KEY_Y, GameText.literal("y")),
            HotkeyButton("z", Input.KEY_Z, GameText.literal("z")),

            HotkeyButton("0", Input.KEY_0, GameText.literal("0")),
            HotkeyButton("1", Input.KEY_1, GameText.literal("1")),
            HotkeyButton("2", Input.KEY_2, GameText.literal("2")),
            HotkeyButton("3", Input.KEY_3, GameText.literal("3")),
            HotkeyButton("4", Input.KEY_4, GameText.literal("4")),
            HotkeyButton("5", Input.KEY_5, GameText.literal("5")),
            HotkeyButton("6", Input.KEY_6, GameText.literal("6")),
            HotkeyButton("7", Input.KEY_7, GameText.literal("7")),
            HotkeyButton("8", Input.KEY_8, GameText.literal("8")),
            HotkeyButton("9", Input.KEY_9, GameText.literal("9")),

            HotkeyButton("keypad_0", Input.KEY_KEYPAD_0, GameText.localised("keycap_kp0")),
            HotkeyButton("keypad_1", Input.KEY_KEYPAD_1, GameText.localised("keycap_kp1")),
            HotkeyButton("keypad_2", Input.KEY_KEYPAD_2, GameText.localised("keycap_kp2")),
            HotkeyButton("keypad_3", Input.KEY_KEYPAD_3, GameText.localised("keycap_kp3")),
            HotkeyButton("keypad_4", Input.KEY_KEYPAD_4, GameText.localised("keycap_kp4")),
            HotkeyButton("keypad_5", Input.KEY_KEYPAD_5, GameText.localised("keycap_kp5")),
            HotkeyButton("keypad_6", Input.KEY_KEYPAD_6, GameText.localised("keycap_kp6")),
            HotkeyButton("keypad_7", Input.KEY_KEYPAD_7, GameText.localised("keycap_kp7")),
            HotkeyButton("keypad_8", Input.KEY_KEYPAD_8, GameText.localised("keycap_kp8")),
            HotkeyButton("keypad_9", Input.KEY_KEYPAD_9, GameText.localised("keycap_kp9")),

            HotkeyButton("keypad_add", Input.KEY_KEYPAD_ADD, GameText.localised("keycap_kp_add")),
            HotkeyButton("keypad_sub", Input.KEY_KEYPAD_SUB, GameText.localised("keycap_kp_subtract")),
            HotkeyButton("keypad_mul", Input.KEY_KEYPAD_MUL, GameText.localised("keycap_kp_multiply")),
            HotkeyButton("keypad_div", Input.KEY_KEYPAD_DIV, GameText.localised("keycap_kp_divide")),
            HotkeyButton("keypad_dec", Input.KEY_KEYPAD_DEC, GameText.localised("keycap_kp_decimal")),
            HotkeyButton("keypad_enter", Input.KEY_KEYPAD_ENTER, GameText.localised("keycap_kp_enter")),
            HotkeyButton("keypad_eq", Input.KEY_KEYPAD_EQ, GameText.localised("keycap_kp_equals")),
        )

        @JvmField
        val BY_NAME: Map<String, HotkeyButton> = ALL.associateBy { it.id }

        @JvmField
        val BY_KEY_ID: Map<Int, HotkeyButton> = ALL.associateBy { it.keyID }
    }
}

/**
 * Contains the string IDs for the vanilla hotkeys, to make stuff like searching
 * and refactoring easier.
 *
 * It also makes it easy to see what hotkeys aren't yet implemented - anything
 * that's shown as unused in your IDE is obviously unimplemented.
 */
object VanillaHotkeys {
    const val PAUSE: String = "pause"
    const val FTL_JUMP: String = "ftl_jump"
    const val SHIP_INVENTORY: String = "ship_inventory"
    const val SHIP_UPGRADES: String = "ship_upgrades"
    const val SHIP_CREW: String = "ship_crew"
    const val OPEN_STORE: String = "open_store"
    const val OPEN_OPTIONS: String = "open_options"

    @JvmField
    val WEAPON_SLOTS: List<String> = listOf("weapon_slot_1", "weapon_slot_2", "weapon_slot_3", "weapon_slot_4")

    @JvmField
    val DRONE_SLOTS: List<String> = listOf("drone_slot_1", "drone_slot_2", "drone_slot_3")

    const val WEAPON_AUTOFIRE_TOGGLE: String = "weapon_autofire_toggle"
    const val WEAPON_AUTOFIRE_TARGET: String = "weapon_autofire_target"

    // We don't have constants for the systems, as their ID is built from the blueprint name
    // The exception is the medbay/clonebay which share a hotkey
    const val SYS_POWER_MEDICAL: String = "sys_power_medical"
    const val SYS_POWER_MEDICAL_OFF: String = "sys_power_medical_off"

    const val SYS_ACTION_DOOR_OPEN: String = "sys_action_door_open"
    const val SYS_ACTION_DOOR_CLOSE: String = "sys_action_door_close"
    const val SYS_ACTION_CLOAKING: String = "sys_action_cloaking"
    const val SYS_ACTION_TELEPORT_SEND: String = "sys_action_teleport_send"
    const val SYS_ACTION_TELEPORT_RECV: String = "sys_action_teleport_recv"
    const val SYS_ACTION_HACKING: String = "sys_action_hacking"
    const val SYS_ACTION_MIND: String = "sys_action_mind"
    const val SYS_ACTION_BATTERY: String = "sys_action_battery"

    @JvmField
    val SELECT_CREW: List<String> = listOf(
        "select_crew_1", "select_crew_2", "select_crew_3", "select_crew_4",
        "select_crew_5", "select_crew_6", "select_crew_7", "select_crew_8",
    )

    const val SELECT_CREW_ALL: String = "select_crew_all"
    const val LOAD_CREW_POS: String = "load_crew_pos"
    const val SAVE_CREW_POS: String = "save_crew_pos"
    const val LOCKDOWN_ACTIVATE: String = "lockdown_activate"

    const val DEV_CONSOLE: String = "dev_console"
    const val DEV_FRAME_STEP: String = "dev_frame_step"
    const val DEV_FAST_FORWARD: String = "dev_fast_forward"
}

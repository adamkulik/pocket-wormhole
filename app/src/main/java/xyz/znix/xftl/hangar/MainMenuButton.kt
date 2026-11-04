package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.Input

/**
 * Touch only: a MAIN MENU button beside EDIT SHIP, as the iOS port's
 * hangar has. On desktop Esc does the same, like vanilla.
 */
class MainMenuButton(val state: SelectShipState, private val x: Int, private val y: Int) {
    private val font = SILFontLoader(state.fontHL2).apply { scale = 2f }
    private val text = state.translator["escape_mainmenu"]
    private val width = 5 + font.getWidth(text) + 5
    private val height = 20

    private var hover = false

    fun draw(g: Graphics) {
        hover = (state.mousePos.x - x) in 0..width && (state.mousePos.y - y) in 0..height

        g.colour = when {
            hover -> Constants.UI_BUTTON_HOVER
            else -> Constants.SECTOR_CUTOUT_TEXT
        }
        Buttons.drawRounded(g, x, y, width, height, 3)
        font.drawStringCentred(x.f, y + 16f, width.f, text, Constants.JUMP_DISABLED_TEXT)
    }

    fun mouseClicked(button: Int) {
        if (button == Input.MOUSE_LEFT_BUTTON && hover)
            state.backToMainMenu()
    }
}

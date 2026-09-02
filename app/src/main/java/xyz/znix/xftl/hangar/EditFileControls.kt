package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.Input
import kotlin.math.max

class EditFileControls(val state: SelectShipState) {
    private val offsetX = 200
    private val offsetY = 10

    private val editBtnX = offsetX + 0
    private val editBtnY = offsetY + 0

    private val font = SILFontLoader(state.fontHL2).apply { scale = 2f }
    private val editText = state.translator["xftl_edit_ship"]
    private val clearEditText = state.translator["xftl_clear_edit_ship"]
    private val editBtnWidth = 5 + max(font.getWidth(editText), font.getWidth(clearEditText)) + 5
    private val editBtnHeight = 20

    private var editBtnHover = false

    fun draw(g: Graphics) {
        editBtnHover =
            (state.mousePos.x - editBtnX) in 0..editBtnWidth && (state.mousePos.y - editBtnY) in 0..editBtnHeight

        g.colour = when {
            editBtnHover -> Constants.UI_BUTTON_HOVER
            else -> Constants.SECTOR_CUTOUT_TEXT
        }
        Buttons.drawRounded(g, editBtnX, editBtnY, editBtnWidth, editBtnHeight, 3)

        val editBtnText = when (state.isShipEdited) {
            false -> editText
            true -> clearEditText
        }
        font.drawStringCentred(editBtnX.f, editBtnY + 16f, editBtnWidth.f, editBtnText, Constants.JUMP_DISABLED_TEXT)
    }

    fun mouseClicked(button: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        if (editBtnHover) {
            if (state.isShipEdited) {
                state.stopEditingShip()
            } else {
                state.startEditingShip()
            }
        }
    }
}

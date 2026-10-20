package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.Input

/**
 * Vanilla's "Advanced Edition Content" box in the hangar: the DISABLED and
 * ENABLED buttons below the ship's right-hand side, with an info panel
 * describing the hovered option. The layout is measured against vanilla
 * 1.6.14 (the box_advanced.png frame and its two button slots).
 */
class AdvancedEditionBox(val state: SelectShipState) {
    private val frame = state.getImg("img/customizeUI/box_advanced.png")

    private val titleFont = state.getFont("JustinFont12Bold")
    private val buttonFont = SILFontLoader(state.fontHL2).apply { scale = 2f }
    private val infoTitleFont = state.getFont("JustinFont12Bold")
    private val infoFont = state.getFont("JustinFont10")

    private var hovered: Boolean? = null

    fun draw(g: Graphics) {
        val x = state.screenSize.x - frame.width - RIGHT_MARGIN
        val y = BOX_Y

        frame.draw(x, y)

        val title = text("hangar_advanced_title", "Advanced Edition Content")
        titleFont.drawStringCentred(x + TITLE_X1.f, y + TITLE_TEXT_Y.f, (TITLE_X2 - TITLE_X1).f, title, Constants.SECTOR_CUTOUT_TEXT)

        hovered = null
        drawButton(g, x + OFF_X1, y, OFF_X2 - OFF_X1, false, text("advanced_off_button", "DISABLED"))
        drawButton(g, x + ON_X1, y, ON_X2 - ON_X1, true, text("advanced_on_button", "ENABLED"))

        // On touch the cursor stays where the button was tapped, so the
        // description of what was just picked stays up - like hovering it.
        hovered?.let { drawInfo(g, it) }
    }

    private fun drawButton(g: Graphics, x: Int, boxY: Int, width: Int, enables: Boolean, label: String) {
        val y = boxY + BUTTON_Y1
        val height = BUTTON_Y2 - BUTTON_Y1
        val selected = state.advancedEdition == enables
        val isHovering = state.mousePos.x in x until x + width && state.mousePos.y in y until y + height
        if (isHovering)
            hovered = enables

        // The frame's slot is the unselected look; the selected option
        // fills it yellow, like the difficulty buttons.
        if (selected) {
            g.colour = Constants.UI_BUTTON_HOVER
            Buttons.drawRounded(g, x + 2, y + 2, width - 4, height - 4, 3)
        }
        val textColour = when {
            selected -> Constants.JUMP_DISABLED_TEXT
            isHovering -> Constants.UI_BUTTON_HOVER
            else -> Constants.SECTOR_CUTOUT_TEXT
        }
        buttonFont.drawStringCentred(x.f, y + 18f, width.f, label, textColour)
    }

    private fun drawInfo(g: Graphics, enables: Boolean) {
        val title = if (enables) text("advanced_on_title", "Advanced Content") else text("advanced_off_title", "Default Content")
        val body = if (enables) text("advanced_on_text", "") else text("advanced_off_text", "")
        val lines = infoFont.wrapString(body, INFO_W - 20)

        val height = 40 + lines.size * infoFont.lineSpacing + 10
        g.colour = Constants.SECTOR_CUTOUT_TEXT
        Buttons.drawRounded(g, INFO_X - 2, INFO_Y - 2, INFO_W + 4, height + 4, 6)
        g.colour = INFO_BACKGROUND
        Buttons.drawRounded(g, INFO_X, INFO_Y, INFO_W, height, 5)

        infoTitleFont.drawString(INFO_X + 10f, INFO_Y + 24f, title, Constants.SECTOR_CUTOUT_TEXT)
        for ((i, line) in lines.withIndex()) {
            infoFont.drawString(INFO_X + 10f, INFO_Y + 46f + i * infoFont.lineSpacing, line, Constants.SECTOR_CUTOUT_TEXT)
        }
    }

    fun mouseClicked(button: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON)
            return
        val choice = hovered ?: return
        if (state.advancedEdition != choice)
            state.advancedEdition = choice
    }

    private fun text(key: String, fallback: String): String = state.translator.translations[key] ?: fallback

    companion object {
        // Where vanilla puts the box: its top-left at (979, 406) on the
        // 1280x720 screen, measured from a 1.6.14 screenshot.
        private const val RIGHT_MARGIN = 34
        private const val BOX_Y = 406

        // Inside box_advanced.png: the title bar and the two button slots.
        private const val TITLE_X1 = 17
        private const val TITLE_X2 = 250
        private const val TITLE_TEXT_Y = 27
        private const val OFF_X1 = 14
        private const val OFF_X2 = 125
        private const val ON_X1 = 148
        private const val ON_X2 = 253
        private const val BUTTON_Y1 = 40
        private const val BUTTON_Y2 = 64

        // The hangar's info panel (vanilla shows system descriptions there
        // too), right of the ship below the start box.
        private const val INFO_X = 945
        private const val INFO_Y = 127
        private const val INFO_W = 328

        private val INFO_BACKGROUND = Colour(113, 92, 92, 240)
    }
}

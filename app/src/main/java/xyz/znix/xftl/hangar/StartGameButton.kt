package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.Input

class StartGameButton(val state: SelectShipState) {
    private val startFont = SILFontLoader(state.fontHL2).apply { scale = 4f }
    private val difficultyFont = SILFontLoader(state.fontHL2).apply { scale = 2f }

    var selected: Difficulty = Difficulty.NORMAL
    private var hovered: Difficulty? = null
    private var hoveringStart: Boolean = false

    fun draw(g: Graphics) {
        val image = state.getImg("img/customizeUI/box_start.png")

        val startText = state.translator["start_button"]
        val startTextWidth = startFont.getWidth(startText)

        val maxDiffWidth = Difficulty.entries
            .maxOf { difficultyFont.getWidth(state.translator[it.startButtonKey]) }

        val difficultyButtonX = 5 + 2
        val difficultyButtonWidth = 5 + maxDiffWidth + 5
        val startButtonX = difficultyButtonX + difficultyButtonWidth + 2 + 5 + 3
        val startButtonWidth = 8 + startTextWidth + 8
        val width = startButtonX + startButtonWidth + 8

        val x = state.screenSize.x - (width + GLOW)
        val y = GLOW

        // Cut the image up into sections and draw those, to fit variable string widths.
        val diffStretchStart = 30
        val diffStretchEnd = GLOW + difficultyButtonX + difficultyButtonWidth - 10
        val transitionEnd = diffStretchEnd + 40
        val startAreaEnd = GLOW + width + GLOW - 40
        image.drawSection(x - GLOW, y - GLOW, diffStretchStart, image.height)
        image.drawSection(
            x - GLOW + diffStretchStart, y - GLOW,
            10, image.height,
            offsetX = diffStretchStart,
            stretchX = diffStretchEnd - diffStretchStart
        )
        image.drawSection(
            x - GLOW + diffStretchEnd, y - GLOW,
            transitionEnd - diffStretchEnd, image.height,
            offsetX = 99
        )
        image.drawSection(
            x - GLOW + transitionEnd, y - GLOW,
            20, image.height,
            offsetX = 160,
            stretchX = startAreaEnd - transitionEnd
        )
        image.drawSection(
            x - GLOW + startAreaEnd, y - GLOW,
            40, image.height,
            offsetX = image.width - 40
        )

        hovered = null
        for (difficulty in Difficulty.entries) {
            drawDifficultyButton(g, x + 7, y + 7, difficultyButtonWidth, difficulty)
        }

        drawStartButton(g, x + startButtonX, y + 21, startButtonWidth, startText)
    }

    private fun drawDifficultyButton(g: Graphics, x: Int, yBase: Int, width: Int, difficulty: Difficulty) {
        val height = 24
        val y = yBase + 26 * difficulty.ordinal

        val text = state.translator[difficulty.startButtonKey]

        val isHovering = state.mousePos.x in x until x + width && state.mousePos.y in y until y + height

        if (isHovering) {
            this.hovered = difficulty
        }

        g.colour = when {
            isHovering -> Constants.UI_BUTTON_HOVER
            selected == difficulty -> Constants.UI_BUTTON_HOVER
            else -> Constants.SECTOR_CUTOUT_TEXT
        }

        Buttons.drawRounded(g, x, y, width, height, 4)
        difficultyFont.drawStringCentred(x.f, y + 18f, width.f, text, Constants.JUMP_DISABLED_TEXT)
    }

    private fun drawStartButton(g: Graphics, x: Int, y: Int, width: Int, text: String) {
        val height = 48

        hoveringStart = state.mousePos.x in x until x + width && state.mousePos.y in y until y + height

        g.colour = when {
            hoveringStart -> Constants.UI_BUTTON_HOVER
            else -> Constants.SECTOR_CUTOUT_TEXT
        }

        Buttons.drawRounded(g, x, y, width, height, 6)
        startFont.drawStringCentred(x.f, y + 36f, width.f, text, Constants.JUMP_DISABLED_TEXT)
    }

    fun mouseClicked(button: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        hovered?.let { selected = it }

        if (hoveringStart) {
            state.startGame(selected)
        }
    }

    companion object {
        private const val GLOW = 7
    }
}

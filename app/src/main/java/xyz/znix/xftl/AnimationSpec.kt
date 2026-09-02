package xyz.znix.xftl

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.rendering.Image

class AnimationSpec(
    val sheet: Animations.SpriteSheetSpec,
    val name: String,
    val x: Int, val y: Int,
    val length: Int,
    val time: Float
) {
    val totalTime: Float get() = time * length

    fun startSingle(game: InGameState) = startSingle(game, 1f, false)

    fun startLooping(game: InGameState) = FTLAnimation(game, this, true, 1f, false)

    fun startSingle(game: InGameState, speed: Float, backwards: Boolean): FTLAnimation {
        return FTLAnimation(game, this, false, speed, backwards)
    }

    fun spriteAt(game: InGameState, i: Int): Image {
        if (i >= length) throw IndexOutOfBoundsException(i)
        val img = game.getImg(sheet.sheetPath)
        return sheet.getSprite(img, x + i, y)
    }
}

package xyz.znix.xftl

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.Renderable

class FTLAnimation(
    private val game: InGameState,
    val spec: AnimationSpec,
    var loop: Boolean,
    val speed: Float,
    val backwards: Boolean
) : Renderable {
    val sprites: List<Image> = (0 until spec.length).map { spec.spriteAt(game, it) }

    val width: Int = sprites[0].width
    val height: Int = sprites[0].height

    var timer: Float = 0f
        set(value) {
            field = value
            updateImage()
        }

    val isStopped: Boolean get() = timer >= duration && !loop

    val frameCount: Int get() = spec.length

    var isPaused: Boolean = false

    var frame: Int = -1
        private set

    var duration: Float = spec.totalTime
        set(value) {
            field = value
            updateImage()
        }

    val currentFrame: Image get() = sprites[frame]

    init {
        // Initialise the current index and frame
        updateImage()
    }

    fun update(dt: Float) {
        if (isPaused) {
            return
        }

        timer += dt * speed

        if (timer >= duration) {
            when {
                loop -> timer -= duration
                else -> timer = duration
            }
        }
    }

    private fun updateImage() {
        val effectiveTimer = if (backwards) duration - timer else timer
        val progress = effectiveTimer / duration // 0-1 progress through the animation
        frame = (frameCount * progress).toInt().coerceIn(0 until frameCount)
    }

    fun spriteAt(i: Int): Image {
        // Don't crash if there aren't enough frames (eg insufficient boost
        // frames on weapon or missing crew animations). Instead, fall back
        // to using the first frame.
        // This isn't especially nice, so add an assertion here that only
        // trips when -ea is specified on Java's command line.
        assert(i in sprites.indices)
        return sprites.getOrElse(i) { sprites.first() }
    }

    override fun draw(x: Float, y: Float) {
        draw(x, y, Colour.white)
    }

    override fun draw(x: Float, y: Float, filter: Colour) {
        currentFrame.draw(x, y, filter)
    }

    override fun draw(x: Float, y: Float, width: Float, height: Float) {
        currentFrame.draw(x, y, width, height)
    }

    override fun draw(x: Float, y: Float, width: Float, height: Float, filter: Colour) {
        currentFrame.draw(x, y, width, height, filter)
    }
}

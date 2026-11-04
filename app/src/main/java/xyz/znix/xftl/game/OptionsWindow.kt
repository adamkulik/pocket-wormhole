package xyz.znix.xftl.game

import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.replaceArg
import xyz.znix.xftl.ui.Label
import xyz.znix.xftl.ui.Slider
import xyz.znix.xftl.ui.WidgetContainer

class OptionsWindow(val game: InGameState, val close: () -> Unit) : Window() {
    private val widgetTree: WidgetContainer = game.uiLoader.load("options").mainWidget

    private val soundSlider = widgetTree.byId["sound"] as Slider
    private val musicSlider = widgetTree.byId["music"] as Slider
    private val touchAutoPauseLabel = widgetTree.byId["touch_autopause_label"] as Label

    override val size: IPoint get() = widgetTree.root.size

    init {
        // Before the buttons are built, so the option's button is sized
        // for its real text
        updateTouchAutoPauseLabel()

        buttons += widgetTree.buildButtons(game, this, ConstPoint.ZERO)

        soundSlider.value = game.mainGame.profile.soundVolume
        musicSlider.value = game.mainGame.profile.musicVolume

        soundSlider.changeListener = this::updateVolumes
        musicSlider.changeListener = this::updateVolumes

        widgetTree.addButtonListener("keybinds") {
            game.shipUI.showKeybindWindow()
        }

        widgetTree.addButtonListener("touch_autopause") {
            val profile = game.mainGame.profile
            profile.touchAutoPause = !profile.touchAutoPause
            updateTouchAutoPauseLabel()
        }
    }

    private fun updateTouchAutoPauseLabel() {
        val state = if (game.mainGame.profile.touchAutoPause) "on" else "off"
        touchAutoPauseLabel.text = game.translator["touch_autopause"].replaceArg(game.translator[state])
        widgetTree.updateLayout()
    }

    override fun draw(g: Graphics) {
        g.pushTransform()
        g.translate(position.x.f, position.y.f)

        widgetTree.draw(g)

        g.popTransform()
    }

    override fun escapePressed() {
        updateVolumes()
        game.mainGame.profile.markDirty()

        close()
    }

    private fun updateVolumes() {
        game.mainGame.profile.soundVolume = soundSlider.value
        game.mainGame.profile.musicVolume = musicSlider.value
    }
}

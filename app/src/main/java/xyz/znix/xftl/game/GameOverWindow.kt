package xyz.znix.xftl.game

import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.ui.Label
import xyz.znix.xftl.ui.WidgetContainer

/**
 * This is the window that is displayed when the game ends, either
 * through success or failure.
 */
class GameOverWindow(private val game: InGameState, val outcome: Outcome) : Window() {
    private val bodyFont = game.getFont("c&cnew", 2f)
    private val scoreFont = game.getFont("c&cnew", 3f)

    private val wrappedMessage: List<String>

    private val widgetTree: WidgetContainer = game.uiLoader.load("gameover").mainWidget

    override val size: IPoint = widgetTree.root.size

    init {
        buttons += widgetTree.buildButtons(game, this, ConstPoint.ZERO)

        widgetTree.addButtonListener("stats", this::statsClicked)
        widgetTree.addButtonListener("restart", this::restartClicked)
        widgetTree.addButtonListener("hangar", this::hangarClicked)
        widgetTree.addButtonListener("mainmenu", this::mainMenuClicked)
        widgetTree.addButtonListener("quit", this::quitClicked)

        val titleLabel = widgetTree.byId["title"] as Label
        val titleKey = when (outcome) {
            Outcome.WIN -> "gameover_title_victory"
            else -> "gameover_title_gameover"
        }
        titleLabel.text = game.translator[titleKey]

        // Build and wrap the message once
        val bodyKey = when (outcome) {
            Outcome.WIN -> "gameover_win"
            Outcome.LOOSE_HULL -> "gameover_blowup"
            Outcome.LOOSE_CREW -> "gameover_crew"
            Outcome.LOOSE_BASE_DESTROYED -> "gameover_fedbase"
        }
        val bodyStr = game.translator[bodyKey]
        wrappedMessage = bodyFont.wrapString(bodyStr, size.x - 80)
    }

    override fun draw(g: Graphics) {
        // g.colour = Color.red
        // g.fillRect(position.x.f, position.y.f, size.x.f, size.y.f)

        g.pushTransform()
        g.translate(position.x.f, position.y.f)
        widgetTree.draw(g)
        g.popTransform()

        // The image is cut up to fit all the localised buttons.

        for (button in buttons) {
            button.draw(g)
        }

        val centreX = position.x + size.x / 2

        // Draw and wrap the main message

        // Drawing wrapped text is duplicated with the dialogue window,
        // but it's probably not a big problem as I can't see where else
        // this would be required.
        // Note that 0,0 includes the glow margin, so add 7px for it
        var textY = 92 + 7
        for (line in wrappedMessage) {
            bodyFont.drawStringCentred(centreX.f, position.y + textY.f, 0f, line, Colour.white)
            textY += 34
        }

        val scoreText = "SCORE: 123"
        scoreFont.drawStringCentred(centreX.f, position.y + 208f, 0f, scoreText, Colour.white)
    }

    override fun escapePressed() {
        // Do nothing, as the player can't dismiss this window, nor
        // open the pause screen.
    }

    private fun restartClicked() {
        game.mainGame.restartGame()
    }

    private fun statsClicked() {
        TODO("Not yet implemented")
    }

    private fun quitClicked() {
        game.mainGame.quitGame()
    }

    private fun mainMenuClicked() {
        // TODO implement a main menu
        game.mainGame.switchToShipSelect()
    }

    private fun hangarClicked() {
        game.mainGame.switchToShipSelect()
    }

    enum class Outcome {
        WIN,
        LOOSE_HULL,
        LOOSE_CREW,
        LOOSE_BASE_DESTROYED,
    }
}

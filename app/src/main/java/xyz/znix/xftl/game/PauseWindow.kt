package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.ui.ImageView
import xyz.znix.xftl.ui.Label
import xyz.znix.xftl.ui.Widget
import xyz.znix.xftl.ui.WidgetContainer

class PauseWindow(val game: InGameState, val close: () -> Unit) : Window() {
    private val widgetTree: WidgetContainer = game.uiLoader.load("escape_menu").mainWidget
    private val lockIcon: Image = game.getImg("img/customizeUI/box_lock_on.png")
    private val mousePos = Point(0, 0)
    private val shipFamily: ShipFamily? = game.content.shipFamilies.byShipId[game.player.type.name]
    private val achievements: List<Achievement> =
        shipFamily?.achievements?.map { game.content.achievements[it] } ?: emptyList()

    override val size: IPoint = widgetTree.root.size

    init {
        buttons += widgetTree.buildButtons(game, this, ConstPoint.ZERO)

        widgetTree.addButtonListener("continue", this::continueClicked)
        widgetTree.addButtonListener("main_menu", this::mainMenuClicked)
        widgetTree.addButtonListener("hangar", this::hangarClicked)
        widgetTree.addButtonListener("restart", this::restartClicked)
        widgetTree.addButtonListener("options", this::optionsClicked)
        widgetTree.addButtonListener("controls", this::controlsClicked)
        widgetTree.addButtonListener("quit", this::quitClicked)

        // Set the difficulty label
        val difficultyLabel = widgetTree.byId["difficulty"] as Label
        difficultyLabel.text = game.translator[game.difficulty.startButtonKey]

        // Set the advanced edition status
        val aeLabel = widgetTree.byId["ae-status"] as Label
        val aeKey = when (game.content.enableAdvancedEdition) {
            true -> "advanced_on_button"
            false -> "advanced_off_button"
        }
        aeLabel.text = game.translator[aeKey]

        // Set the achievement icons
        for (idx in 0 until 3) {
            val ach = achievements.getOrNull(idx)
            val img = widgetTree.byId["ach${idx + 1}"] as ImageView
            if (ach != null) {
                img.image = game.getImg(ach.img)
            } else {
                img.isVisible = false
            }
        }

        val questWidget = widgetTree.byId["quest"] as ImageView
        questWidget.isVisible = shipFamily?.hasQuest == true
        if (shipFamily != null && shipFamily.hasQuest) {
            val questImagePath = when (game.mainGame.profile.getShipUnlock(shipFamily)) {
                null -> "img/achievements/S_Q_off.png"
                else -> "img/achievements/S_Q_on.png"
            }
            questWidget.image = game.getImg(questImagePath)
        }
    }

    override fun draw(g: Graphics) {
        g.pushTransform()
        g.translate(position.x.f, position.y.f)

        // Draw the base UI
        widgetTree.draw(g)

        decorateAchievement(g, widgetTree.byId["ach1"] as ImageView, 0)
        decorateAchievement(g, widgetTree.byId["ach2"] as ImageView, 1)
        decorateAchievement(g, widgetTree.byId["ach3"] as ImageView, 2)

        val questBox = widgetTree.byId["quest"]!!
        if (mousePos.containedInBox(questBox.position, questBox.size) && questBox.isVisible) {
            g.colour = Constants.UI_BUTTON_HOVER
            drawOutline(g, questBox)

            // TODO tooltip
        }

        val victoryBox = widgetTree.byId["victory"]!!
        if (mousePos.containedInBox(victoryBox.position, victoryBox.size)) {
            g.colour = Constants.UI_BUTTON_HOVER
            drawOutline(g, victoryBox)

            // TODO tooltip
        }

        // TODO show the dots that indicate the player has won with a given layout

        g.popTransform()
    }

    private fun decorateAchievement(g: Graphics, img: ImageView, index: Int) {
        val hovering = mousePos.containedInBox(img.position, img.size)

        // If the achievement isn't populated - such as for modded ships - then
        // there's nothing to draw.
        val achievement = achievements.getOrNull(index) ?: return
        val unlockInfo = game.mainGame.profile.getAchievement(achievement)

        // Draw the padlock icon, if this achievement is locked
        if (unlockInfo == null) {
            g.colour = Colour(0, 0, 0, 200)
            g.fillRect(img.position.x, img.position.y, img.size.x, img.size.y)

            val x = img.position.x + (img.size.x - lockIcon.width) / 2
            val y = img.position.y + (img.size.y - lockIcon.height) / 2
            lockIcon.draw(x, y)
        }

        // Draw the outline
        g.colour = when (hovering) {
            true -> Constants.ACHIEVEMENT_OUTLINE_HIGHLIGHT
            false -> Constants.ACHIEVEMENT_OUTLINE
        }
        drawOutline(g, img)

        // TODO tooltip when highlighted
    }

    private fun drawOutline(g: Graphics, widget: Widget) {
        val pos = widget.position
        val size = widget.size

        g.drawRect(pos.x, pos.y, size.x - 1, size.y - 1)
        g.drawRect(pos.x + 1, pos.y + 1, size.x - 3, size.y - 3)
    }

    override fun updateUI(x: Int, y: Int) {
        super.updateUI(x, y)

        mousePos.set(x - position.x, y - position.y)
    }

    override fun escapePressed() {
        close()
    }

    // Button handlers
    private fun continueClicked() {
        close()
    }

    private fun mainMenuClicked() {
        // TODO implement a main menu
        game.mainGame.switchToShipSelect()
    }

    private fun hangarClicked() {
        // TODO warning box
        game.mainGame.switchToShipSelect()
    }

    private fun restartClicked() {
        // TODO warning box
        game.mainGame.restartGame()
    }

    private fun optionsClicked() {
        game.shipUI.showOptionsWindow()
    }

    private fun controlsClicked() {
        // TODO implement
    }

    private fun quitClicked() {
        game.mainGame.quitGame()
    }
}

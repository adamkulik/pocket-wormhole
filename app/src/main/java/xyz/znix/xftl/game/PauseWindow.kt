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

    private var dialog: WidgetContainer? = null
    private val dialogPos = Point(0, 0)
    private var menuButtons: List<Button> = emptyList()

    /** A file chosen in the picker, waiting for the next frame. */
    private var pickedSave: ByteArray? = null

    init {
        buttons += widgetTree.buildButtons(game, this, ConstPoint.ZERO)

        widgetTree.addButtonListener("continue", this::continueClicked)
        widgetTree.addButtonListener("main_menu", this::mainMenuClicked)
        widgetTree.addButtonListener("hangar", this::hangarClicked)
        widgetTree.addButtonListener("restart", this::restartClicked)
        widgetTree.addButtonListener("options", this::optionsClicked)
        widgetTree.addButtonListener("controls", this::controlsClicked)
        widgetTree.addButtonListener("quit", this::quitClicked)
        widgetTree.addButtonListener("export_save") { exportSaveClicked() }
        widgetTree.addButtonListener("import_save") { importSaveClicked() }

        // Save transfer buttons are only offered when the platform can't
        // expose the save directory directly (Android's SAF); on desktop the
        // continue.sav sits next to the other saves anyway.
        val transfer = xyz.znix.xftl.sys.PlatformSpecific.INSTANCE.saveTransfer
        // Vanilla-format exports are only meaningful at a sector boundary:
        // the importing engine regenerates the beacon layout, so only the
        // sector's entry beacon is a stable landing point. Hide the button
        // everywhere else (the long explanation label did not fit).
        val exportVisible = transfer != null && atSectorStart()
        widgetTree.byId["export_save"]?.isVisible = exportVisible
        widgetTree.byId["export_label"]?.isVisible = exportVisible
        widgetTree.byId["import_save"]?.isVisible = transfer != null
        widgetTree.byId["import_label"]?.isVisible = transfer != null
        (widgetTree.byId["import_label"] as? Label)?.text = "IMPORT SAVE"

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

        dialog?.let {
            g.colour = DIALOG_DIM
            g.fillRect(0f, 0f, 1280f, 720f)
            g.pushTransform()
            g.translate(position.x + dialogPos.x.f, position.y + dialogPos.y.f)
            it.draw(g)
            g.popTransform()
        }
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

        pickedSave?.let {
            pickedSave = null
            importSave(it)
        }
    }

    override fun escapePressed() {
        if (dialog != null) {
            closeDialog()
            return
        }
        close()
    }

    /**
     * A box over the menu, which takes the clicks while it's open: yes/no
     * with [onConfirm], or just a message to dismiss without.
     */
    private fun showDialog(message: String, onConfirm: (() -> Unit)?) {
        val widget = game.uiLoader.load("confirm").mainWidget

        // Labels don't wrap by themselves
        val label = widget.byId["message"] as Label
        label.text = label.font.wrapString(message, DIALOG_TEXT_WIDTH).joinToString("\n")
        if (onConfirm == null) {
            widget.byId["yes"]?.isVisible = false
            (widget.byId["no_label"] as Label).text = "OK"
        }
        widget.root.updateSizes()
        widget.root.expandToParent(ConstPoint.ZERO)
        widget.root.updateLayout()
        dialogPos.x = (size.x - widget.root.size.x) / 2
        dialogPos.y = (size.y - widget.root.size.y) / 2

        widget.addButtonListener("yes") {
            closeDialog()
            onConfirm?.invoke()
        }
        widget.addButtonListener("no") { closeDialog() }

        dialog = widget
        menuButtons = ArrayList(buttons)
        buttons.clear()
        buttons += widget.buildButtons(game, this, dialogPos)
        positionUpdated() // the new buttons need the window's position
    }

    private fun closeDialog() {
        dialog = null
        buttons.clear()
        buttons += menuButtons
    }

    // Vanilla-format exports are only meaningful at a sector boundary: the
    // importing engine regenerates the beacon layout, so only the sector's
    // entry beacon is a stable landing point (the loader snaps to the same
    // beacon on import, and the writer pins its export order around it).
    private fun atSectorStart() =
        game.currentBeacon === game.currentBeacon.sector.startBeacon

    // Button handlers
    private fun exportSaveClicked() {
        val transfer = xyz.znix.xftl.sys.PlatformSpecific.INSTANCE.saveTransfer ?: return
        if (!atSectorStart()) return
        val path = game.mainGame.getVanillaSavePath()
        val bytes = if (java.nio.file.Files.exists(path)) {
            java.nio.file.Files.readAllBytes(path)
        } else {
            // No vanilla save yet - write one from the current state.
            game.mainGame.writeRunSave()
            java.nio.file.Files.readAllBytes(path)
        }
        transfer.exportSave(bytes)
    }

    private fun importSaveClicked() {
        showDialog("Import a save? It replaces the run you're playing now.", this::pickSaveToImport)
    }

    private fun pickSaveToImport() {
        val transfer = xyz.znix.xftl.sys.PlatformSpecific.INSTANCE.saveTransfer ?: return
        // Loaded on the next frame (see updateUI): coming back from the file
        // picker, this can run before the app has its GL context again, and
        // loading a run needs it for the ship's images.
        transfer.importSave { bytes -> pickedSave = bytes }
    }

    private fun importSave(bytes: ByteArray) {
        val path = game.mainGame.getVanillaSavePath()
        java.nio.file.Files.createDirectories(path.parent)
        // Keep the current run's save if this one turns out not to load
        val previous = if (java.nio.file.Files.exists(path)) java.nio.file.Files.readAllBytes(path) else null
        java.nio.file.Files.write(path, bytes)
        // Switch to the imported run
        if (!game.mainGame.continueVanillaSave()) {
            if (previous != null) java.nio.file.Files.write(path, previous)
            else java.nio.file.Files.delete(path)
            showDialog("Couldn't load that save. It may be damaged, or made with different mods.", null)
        }
    }

    private fun continueClicked() {
        close()
    }

    private fun mainMenuClicked() {
        // Save+Quit has already stored the run (see quitClicked), so the
        // player can use the main menu's Continue button to get back here.
        game.mainGame.switchToMainMenu()
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

    companion object {
        /** The width of confirm.xml's box, less its margins. */
        private const val DIALOG_TEXT_WIDTH = 266
        private val DIALOG_DIM = Colour(0, 0, 0, 200)
    }
}

package xyz.znix.xftl.game

import xyz.znix.xftl.Datafile
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.ResourceContext

/**
 * The game's title screen, shown when the game opens (issue #2).
 *
 * Vanilla-look layout: the logo in the top-right and a column of menu
 * buttons under it, right-aligned. 'Continue' only appears when there's a
 * saved run to resume; the screens we don't implement yet (tutorial, stats,
 * options, credits) are drawn like vanilla but do nothing.
 */
class MainMenuState(private val main: MainGame, private val datafile: Datafile) : MainGame.GameState() {
    private val resourceContext = ResourceContext()
    private val images = HashMap<String, Image>()

    private val background = getImg("img/main_menus/main_base2.png")
    private val logo = getImg("img/main_menus/main_FTL2.png")

    private val buttons = ArrayList<MenuButton>()

    // All the buttons share a right edge (under the logo), and are stacked
    // one image-height apart - the images include their own visual padding.
    private val buttonsRightX = 1216
    private val firstButtonY = 274
    private val buttonSpacing = 62

    private var mouseX = 0
    private var mouseY = 0

    private var musicStarted = false

    init {
        var y = firstButtonY

        // 'Continue' only exists if there's a saved run to go back to.
        if (main.hasRunSave()) {
            buttons.add(makeButton("continue", y) { main.continueSavedRun() })
            y += buttonSpacing
        }

        buttons.add(makeButton("start", y) { main.switchToShipSelect() })
        y += buttonSpacing

        // These screens don't exist yet - draw them like vanilla, but they
        // don't do anything when clicked.
        for (name in listOf("tutorial", "stats", "options", "credits")) {
            buttons.add(makeButton(name, y))
            y += buttonSpacing
        }

        buttons.add(makeButton("quit", y) { main.exitGame() })
    }

    override fun shutdown() {
        resourceContext.freeAll()
    }

    override fun update(container: GameContainer, delta: Float) {
        mouseX = container.input.mouseX
        mouseY = container.input.mouseY

        // Play the title music. Switching is a one-off, but the streaming
        // refills need pumping every frame like InGameState does.
        val sounds = main.gameContent.sounds
        if (!musicStarted) {
            musicStarted = true
            sounds.switchMusicList(listOf(sounds.getTrack("title")))
        }
        (sounds as? RealSoundManager)?.let {
            it.updateLoopedSounds(false)
            it.updateMusic(delta)
        }
    }

    override fun render(container: GameContainer, g: Graphics) {
        background.draw(0f, 0f)
        logo.draw(LOGO_X.f, LOGO_Y.f)

        for (button in buttons) {
            button.draw(g, mouseX, mouseY)
        }
    }

    override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        for (b in buttons) {
            if (b.contains(x, y) && b.action != null) {
                b.action.invoke()
                return
            }
        }
    }

    private fun makeButton(name: String, y: Int, action: (() -> Unit)? = null): MenuButton {
        // The buttons are right-aligned, like vanilla.
        val image = getImg("img/main_menus/${name}_off.png")
        val x = buttonsRightX - image.width
        val hovered = getImg("img/main_menus/${name}_on.png")
        return MenuButton(image, hovered, x, y, action)
    }

    private fun getImg(path: String): Image {
        images[path]?.let { return it }

        val file = datafile.getOrNull(path)
            ?: error("Missing main menu image '$path'")
        val img = datafile.readImage(resourceContext, file)
        images[path] = img
        return img
    }

    private class MenuButton(
        val image: Image,
        val hovered: Image,
        val x: Int,
        val y: Int,
        val action: (() -> Unit)?
    ) {
        fun contains(px: Int, py: Int): Boolean {
            return px in x until x + image.width && py in y until y + image.height
        }

        fun draw(g: Graphics, mouseX: Int, mouseY: Int) {
            // Buttons without an action never light up.
            val img = if (action != null && contains(mouseX, mouseY)) hovered else image
            img.draw(x.f, y.f)
        }
    }

    companion object {
        private const val LOGO_X = 805
        private const val LOGO_Y = 62
    }
}

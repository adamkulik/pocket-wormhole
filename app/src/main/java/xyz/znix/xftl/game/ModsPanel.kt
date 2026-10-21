package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.modding.ModManager
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.PlatformSpecific
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The main menu's mod list: install a mod from a file, turn mods on and off,
 * remove them, and restart the game to apply the changes.
 *
 * Android keeps the mods folder in the app's private storage, so without
 * this the user has no way to add a mod at all.
 */
class ModsPanel(
    private val titleFont: SILFontLoader,
    private val textFont: SILFontLoader,
    private val buttonFont: SILFontLoader,
    private val profile: SaveProfile,
    private val close: () -> Unit
) {
    private val modImport = PlatformSpecific.INSTANCE.modImport

    private var mods: List<ModManager.Entry> = emptyList()
    private var firstRow = 0
    private var changed = false
    private var confirmDelete: String? = null
    private var status: String? = null
    private var statusIsError = false

    // Picked files arrive from the platform on another thread.
    private val pending = ConcurrentLinkedQueue<() -> Unit>()

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: () -> Unit)

    private val hits = ArrayList<Hit>()

    init {
        reload()
    }

    private fun reload() {
        mods = try {
            ModManager.list()
        } catch (e: Exception) {
            setStatus("Couldn't read the mods folder: ${e.message}", true)
            emptyList()
        }
        firstRow = firstRow.coerceIn(0, (mods.size - ROWS).coerceAtLeast(0))
    }

    fun update() {
        while (true) {
            val action = pending.poll() ?: break
            action()
        }
    }

    fun draw(g: Graphics, mouseX: Int, mouseY: Int) {
        hits.clear()

        // Dim the menu behind us.
        g.colour = DIM
        g.fillRect(0, 0, 1280, 720)

        g.colour = Constants.SECTOR_CUTOUT_TEXT
        Buttons.drawRounded(g, X - 2, Y - 2, W + 4, H + 4, 7)
        g.colour = BACKGROUND
        Buttons.drawRounded(g, X, Y, W, H, 6)

        titleFont.drawString(X + 20f, Y + 34f, "MODS", Constants.SECTOR_CUTOUT_TEXT)

        // Built-in extras that aren't vanilla, so they're off by default
        // and switched on here like a mod. They apply straight away.
        val speedOn = profile.gameSpeedButton
        g.colour = ROW_A
        g.fillRect(X + 12, Y + 54, W - 24, ROW_H - 4)
        textFont.drawString(X + 24f, Y + 54 + 26f, "Game speed button (built in)",
            if (speedOn) Constants.SECTOR_CUTOUT_TEXT else DISABLED_TEXT)
        button(g, mouseX, mouseY, X + W - 24 - DELETE_W - 12 - TOGGLE_W, Y + 54 + 4, TOGGLE_W, ROW_H - 12,
            if (speedOn) "ON" else "OFF", speedOn) {
            profile.gameSpeedButton = !speedOn
            confirmDelete = null
        }
        // The fastest speed the button offers: lower it on a slow phone.
        val limits = MainGame.GAME_SPEED_LIMITS
        button(g, mouseX, mouseY, X + W - 24 - DELETE_W, Y + 54 + 4, DELETE_W, ROW_H - 12,
            "MAX ${profile.gameSpeedMax}x", false, speedOn) {
            val i = limits.indexOf(profile.gameSpeedMax)
            profile.gameSpeedMax = limits[(i + 1) % limits.size]
            confirmDelete = null
        }

        val listTop = Y + 54 + ROW_H + 8
        if (mods.isEmpty()) {
            val lines = textFont.wrapString(
                "No mods installed. Tap ADD MOD and pick a Slipstream mod file (.ftl or .zip) " +
                        "- for example from your Downloads folder.", W - 40
            )
            for ((i, line) in lines.withIndex())
                textFont.drawString(X + 20f, listTop + 30f + i * textFont.lineSpacing, line, Constants.SECTOR_CUTOUT_TEXT)
        }

        for (row in 0 until ROWS) {
            val mod = mods.getOrNull(firstRow + row) ?: break
            val rowY = listTop + row * ROW_H

            g.colour = if (row % 2 == 0) ROW_A else ROW_B
            g.fillRect(X + 12, rowY, W - 24, ROW_H - 4)

            val nameColour = if (mod.enabled) Constants.SECTOR_CUTOUT_TEXT else DISABLED_TEXT
            var name = mod.name
            while (name.length > 4 && textFont.getWidth(name) > NAME_W)
                name = name.dropLast(2)
            if (name != mod.name)
                name = name.dropLast(1) + "…"
            textFont.drawString(X + 24f, rowY + 26f, name, nameColour)

            val onX = X + W - 24 - DELETE_W - 12 - TOGGLE_W
            button(g, mouseX, mouseY, onX, rowY + 4, TOGGLE_W, ROW_H - 12, if (mod.enabled) "ON" else "OFF", mod.enabled) {
                ModManager.setEnabled(mod.name, !mod.enabled)
                changed = true
                confirmDelete = null
                reload()
            }

            val deleting = confirmDelete == mod.name
            button(g, mouseX, mouseY, X + W - 24 - DELETE_W, rowY + 4, DELETE_W, ROW_H - 12, if (deleting) "SURE?" else "REMOVE", deleting) {
                if (deleting) {
                    ModManager.delete(mod.name)
                    changed = true
                    confirmDelete = null
                    setStatus("Removed ${mod.name}.", false)
                    reload()
                } else {
                    confirmDelete = mod.name
                }
            }
        }

        // Paging, for long mod lists.
        if (mods.size > ROWS) {
            val pageY = listTop + ROWS * ROW_H
            button(g, mouseX, mouseY, X + W / 2 - 110, pageY, 100, 30, "UP", false, firstRow > 0) {
                firstRow = (firstRow - ROWS).coerceAtLeast(0)
            }
            button(g, mouseX, mouseY, X + W / 2 + 10, pageY, 100, 30, "DOWN", false, firstRow + ROWS < mods.size) {
                firstRow = (firstRow + ROWS).coerceAtMost(mods.size - 1)
            }
        }

        status?.let {
            val colour = if (statusIsError) Constants.SYS_ENERGY_BROKEN else Constants.SYS_ENERGY_ACTIVE
            textFont.drawString(X + 20f, Y + H - 78f, it, colour)
        }
        val note = if (changed) "Restart the game to apply the changes." else "Mods are applied when the game starts."
        textFont.drawString(X + 20f, Y + H - 56f, note, DISABLED_TEXT)

        val bottomY = Y + H - 44
        if (modImport != null) {
            button(g, mouseX, mouseY, X + 20, bottomY, 160, 32, "ADD MOD", false) { addMod() }
            button(g, mouseX, mouseY, X + W - 20 - 160 - 16 - 160, bottomY, 160, 32, "RESTART", changed) {
                modImport.restartGame()
            }
        }
        button(g, mouseX, mouseY, X + W - 20 - 160, bottomY, 160, 32, "CLOSE", false) { close() }
    }

    private fun button(
        g: Graphics, mouseX: Int, mouseY: Int,
        x: Int, y: Int, w: Int, h: Int,
        label: String, highlighted: Boolean, enabled: Boolean = true,
        action: () -> Unit
    ) {
        val hovering = enabled && mouseX in x until x + w && mouseY in y until y + h

        g.colour = if (enabled) Constants.SECTOR_CUTOUT_TEXT else DISABLED_TEXT
        Buttons.drawRounded(g, x, y, w, h, 4)
        g.colour = when {
            highlighted -> Constants.UI_BUTTON_HOVER
            hovering -> BUTTON_HOVER_FILL
            else -> BACKGROUND
        }
        Buttons.drawRounded(g, x + 2, y + 2, w - 4, h - 4, 3)

        val textColour = when {
            !enabled -> DISABLED_TEXT
            highlighted -> Constants.JUMP_DISABLED_TEXT
            hovering -> Constants.UI_BUTTON_HOVER
            else -> Constants.SECTOR_CUTOUT_TEXT
        }
        buttonFont.drawStringCentred(x.f, y + h / 2f + 7f, w.f, label, textColour)

        if (enabled)
            hits += Hit(x, y, w, h, action)
    }

    private fun addMod() {
        val import = modImport ?: return
        confirmDelete = null
        setStatus("Pick a mod file...", false)
        import.pickMod { name, bytes ->
            pending += {
                if (name == null || bytes == null) {
                    status = null
                } else {
                    try {
                        val saved = ModManager.install(name, bytes)
                        changed = true
                        setStatus("Installed $saved.", false)
                    } catch (e: ModManager.InvalidModException) {
                        setStatus(e.message ?: "Not a mod file.", true)
                    } catch (e: Exception) {
                        setStatus("Couldn't install the mod: ${e.message}", true)
                    }
                    reload()
                    firstRow = (mods.size - ROWS).coerceAtLeast(0)
                }
            }
        }
    }

    private fun setStatus(text: String, error: Boolean) {
        status = text
        statusIsError = error
    }

    /** The panel is modal: clicks outside its buttons do nothing. */
    fun mouseClicked(x: Int, y: Int) {
        val hit = hits.firstOrNull { x in it.x until it.x + it.w && y in it.y until it.y + it.h } ?: return
        try {
            hit.action()
        } catch (e: Exception) {
            setStatus("Error: ${e.message}", true)
        }
    }

    companion object {
        private const val W = 720
        private const val H = 520
        private const val X = (1280 - W) / 2
        private const val Y = (720 - H) / 2

        private const val ROWS = 7
        private const val ROW_H = 40
        private const val TOGGLE_W = 80
        private const val DELETE_W = 110
        private const val NAME_W = W - 24 - 24 - TOGGLE_W - 12 - DELETE_W - 24

        private val DIM = Colour(0, 0, 0, 160)
        private val BACKGROUND = Colour(32, 46, 51, 250)
        private val ROW_A = Colour(46, 63, 69, 255)
        private val ROW_B = Colour(39, 54, 60, 255)
        private val BUTTON_HOVER_FILL = Colour(55, 78, 86, 255)
        private val DISABLED_TEXT = Colour(140, 150, 150)
    }
}

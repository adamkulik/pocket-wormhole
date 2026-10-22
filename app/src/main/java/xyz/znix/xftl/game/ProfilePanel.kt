package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.PlatformSpecific
import xyz.znix.xftl.vanillasave.VanillaProfile
import xyz.znix.xftl.vanillasave.VanillaProfileSync
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The main menu's STATS panel: the profile's progress, and moving it to and
 * from vanilla FTL's profile (ae_prof.sav) - achievements and unlocked
 * ships, both ways. See [VanillaProfileSync].
 */
class ProfilePanel(
    private val titleFont: SILFontLoader,
    private val textFont: SILFontLoader,
    private val buttonFont: SILFontLoader,
    private val profile: SaveProfile,
    private val achievements: Achievement.AchievementTable,
    private val families: ShipFamily.FamilyTable,
    private val close: () -> Unit
) {
    private val transfer = PlatformSpecific.INSTANCE.saveTransfer

    private var status: String? = null
    private var statusIsError = false

    // Picked files arrive from the platform on another thread.
    private val pending = ConcurrentLinkedQueue<() -> Unit>()

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: () -> Unit)

    private val hits = ArrayList<Hit>()

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

        titleFont.drawString(X + 20f, Y + 34f, "PROFILE", Constants.SECTOR_CUTOUT_TEXT)

        val lockable = families.families.filter { it.unlockId != null }
        val ships = lockable.count { it.isUnlocked(profile) }
        val achieved = achievements.achievements.values.count { profile.getAchievement(it) != null }
        var y = Y + 80
        for (line in listOf(
            "Ships unlocked: $ships of ${lockable.size} (the Kestrel, Engi and Lanius cruisers are always available)",
            "Achievements: $achieved of ${achievements.achievements.size}",
            "Runs played here: ${profile.runs.size}, ${profile.runs.count { it.victory }} won" +
                    (profile.runs.maxOfOrNull { it.score }?.let { ", best score $it" } ?: "") +
                    " (${profile.runs.size - profile.runsExported} not exported to FTL yet)",
        )) {
            for (wrapped in textFont.wrapString(line, W - 40)) {
                textFont.drawString(X + 20f, y.f, wrapped, Constants.SECTOR_CUTOUT_TEXT)
                y += textFont.lineSpacing
            }
        }

        y += textFont.lineSpacing
        for (line in textFont.wrapString(EXPLANATION, W - 40)) {
            textFont.drawString(X + 20f, y.f, line, DISABLED_TEXT)
            y += textFont.lineSpacing
        }

        status?.let {
            val colour = if (statusIsError) Constants.SYS_ENERGY_BROKEN else Constants.SYS_ENERGY_ACTIVE
            var statusY = Y + H - 66
            for (line in textFont.wrapString(it, W - 40).take(2).reversed()) {
                textFont.drawString(X + 20f, statusY.f, line, colour)
                statusY -= textFont.lineSpacing
            }
        }

        val bottomY = Y + H - 44
        if (transfer != null) {
            button(g, mouseX, mouseY, X + 20, bottomY, 200, 32, "IMPORT FROM FTL") { importProfile() }
            button(g, mouseX, mouseY, X + 20 + 216, bottomY, 200, 32, "EXPORT FOR FTL") { exportProfile() }
        }
        button(g, mouseX, mouseY, X + W - 20 - 140, bottomY, 140, 32, "CLOSE") { close() }
    }

    private fun button(
        g: Graphics, mouseX: Int, mouseY: Int,
        x: Int, y: Int, w: Int, h: Int,
        label: String, action: () -> Unit
    ) {
        val hovering = mouseX in x until x + w && mouseY in y until y + h

        g.colour = Constants.SECTOR_CUTOUT_TEXT
        Buttons.drawRounded(g, x, y, w, h, 4)
        g.colour = if (hovering) BUTTON_HOVER_FILL else BACKGROUND
        Buttons.drawRounded(g, x + 2, y + 2, w - 4, h - 4, 3)

        val textColour = if (hovering) Constants.UI_BUTTON_HOVER else Constants.SECTOR_CUTOUT_TEXT
        buttonFont.drawStringCentred(x.f, y + h / 2f + 7f, w.f, label, textColour)

        hits += Hit(x, y, w, h, action)
    }

    private fun importProfile() {
        val transfer = transfer ?: return
        setStatus("Pick ae_prof.sav...", false)
        transfer.importSave { bytes ->
            pending += {
                if (bytes == null) {
                    status = null
                } else {
                    try {
                        val result = VanillaProfileSync.import(profile, bytes, achievements, families)
                        setStatus(
                            "Imported: ${result.newAchievements} new achievements, ${result.newShips} new ships. " +
                                    "FTL's scores and records are kept, and runs played here are added to them on export.", false
                        )
                    } catch (e: VanillaProfile.InvalidProfileException) {
                        setStatus(e.message ?: "Not an FTL profile.", true)
                    } catch (e: Exception) {
                        setStatus("Couldn't import the profile: ${e.message}", true)
                    }
                }
            }
        }
    }

    private fun exportProfile() {
        val transfer = transfer ?: return
        val bytes = VanillaProfileSync.export(profile, achievements, families)
        transfer.exportFile(bytes, "ae_prof.sav")
        setStatus("Saved as ae_prof.sav: put it in FTL's profile folder (back up the old one first).", false)
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
        private const val H = 420
        private const val X = (1280 - W) / 2
        private const val Y = (720 - H) / 2

        private const val EXPLANATION =
            "Move your progress between FTL on a computer and this game, both ways: unlocked ships, " +
                    "achievements and the stats screen's scores and records. IMPORT FROM FTL adds what's in FTL's profile to this one; nothing here is " +
                    "lost. EXPORT FOR FTL saves an ae_prof.sav with everything from both. FTL keeps it in " +
                    "Documents\\My Games\\FasterThanLight on Windows, ~/.local/share/FasterThanLight on Linux " +
                    "and ~/Library/Application Support/FasterThanLight on a Mac."

        private val DIM = Colour(0, 0, 0, 160)
        private val BACKGROUND = Colour(32, 46, 51, 250)
        private val BUTTON_HOVER_FILL = Colour(55, 78, 86, 255)
        private val DISABLED_TEXT = Colour(140, 150, 150)
    }
}

package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.Translator
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.PlatformSpecific
import xyz.znix.xftl.vanillasave.VanillaProfile
import xyz.znix.xftl.vanillasave.VanillaProfileSync
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The main menu's STATS panel: the profile's progress, and moving it to and
 * from vanilla FTL's profile (ae_prof.sav) - achievements, unlocked ships
 * and stats, both ways (see [VanillaProfileSync]). Its other pages list the
 * achievements and show the stats screen's scores and records.
 */
class ProfilePanel(
    private val titleFont: SILFontLoader,
    private val textFont: SILFontLoader,
    private val buttonFont: SILFontLoader,
    private val profile: SaveProfile,
    private val achievements: Achievement.AchievementTable,
    private val families: ShipFamily.FamilyTable,
    private val translator: Translator,
    private val close: () -> Unit
) {
    private val transfer = PlatformSpecific.INSTANCE.saveTransfer

    private var status: String? = null
    private var statusIsError = false

    // Picked files arrive from the platform on another thread.
    private val pending = ConcurrentLinkedQueue<() -> Unit>()

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val action: () -> Unit)

    private val hits = ArrayList<Hit>()

    private enum class Page { PROFILE, ACHIEVEMENTS, RECORDS }

    private var page = Page.PROFILE
    private var achievementPage = 0

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

        titleFont.drawString(X + 20f, Y + 34f, page.name, Constants.SECTOR_CUTOUT_TEXT)

        var tabX = X + W - 20
        for (tab in Page.values().reversed()) {
            val w = if (tab == Page.ACHIEVEMENTS) 184 else 110
            tabX -= w
            button(g, mouseX, mouseY, tabX, Y + 14, w, 28, tab.name, selected = tab == page) { page = tab }
            tabX -= 8
        }

        when (page) {
            Page.PROFILE -> drawProfile(g, mouseX, mouseY)
            Page.ACHIEVEMENTS -> drawAchievements(g, mouseX, mouseY)
            Page.RECORDS -> drawRecords()
        }

        button(g, mouseX, mouseY, X + W - 20 - 140, Y + H - 44, 140, 32, "CLOSE") { close() }
    }

    private fun drawProfile(g: Graphics, mouseX: Int, mouseY: Int) {
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
        var buttonX = X + 20
        if (transfer != null) {
            button(g, mouseX, mouseY, buttonX, bottomY, 220, 32, "IMPORT FROM FTL") { importProfile() }
            button(g, mouseX, mouseY, buttonX + 228, bottomY, 220, 32, "EXPORT FOR FTL") { exportProfile() }
            buttonX += 456
        }
        button(g, mouseX, mouseY, buttonX, bottomY, 160, 32, "UNLOCK ALL") { unlockAllShips() }
    }

    /** Every achievement, earned ones lit with the difficulty they were earned on. */
    private fun drawAchievements(g: Graphics, mouseX: Int, mouseY: Int) {
        val all = achievements.achievements.values.toList()
        val pages = (all.size + PER_PAGE - 1) / PER_PAGE
        achievementPage = achievementPage.coerceIn(0, maxOf(0, pages - 1))

        val colW = (W - 40) / 2
        for ((i, ach) in all.drop(achievementPage * PER_PAGE).take(PER_PAGE).withIndex()) {
            val x = X + 20 + (i / ROWS) * colW
            val y = Y + 76 + (i % ROWS) * textFont.lineSpacing
            val info = profile.getAchievement(ach)
            val name = translator[ach.name]
            val text = if (info == null) name else "$name (${info.difficulty.name.lowercase()})"
            val fitted = textFont.wrapString(text, colW - 10).firstOrNull() ?: ""
            textFont.drawString(x.f, y.f, fitted, if (info != null) Constants.SYS_ENERGY_ACTIVE else DISABLED_TEXT)
        }

        val bottomY = Y + H - 44
        if (pages > 1) {
            button(g, mouseX, mouseY, X + 20, bottomY, 100, 32, "PREV") { achievementPage = (achievementPage - 1 + pages) % pages }
            button(g, mouseX, mouseY, X + 128, bottomY, 100, 32, "NEXT") { achievementPage = (achievementPage + 1) % pages }
            textFont.drawString(X + 244f, bottomY + 21f, "Page ${achievementPage + 1} of $pages", DISABLED_TEXT)
        }
    }

    /** The stats screen's scores and records, as FTL will show them after exporting. */
    private fun drawRecords() {
        val stats = VanillaProfileSync.currentStats(profile)
        val r = stats.records
        val colW = (W - 40) / 2
        var y = Y + 76

        fun line(x: Int, text: String, colour: Colour = Constants.SECTOR_CUTOUT_TEXT) {
            textFont.drawString(x.f, y.f, textFont.wrapString(text, colW - 10).firstOrNull() ?: "", colour)
        }

        line(X + 20, "TOP SCORES")
        y += textFont.lineSpacing
        if (stats.topScores.isEmpty()) {
            line(X + 20, "None yet", DISABLED_TEXT)
            y += textFont.lineSpacing
        }
        for (score in stats.topScores) {
            val difficulty = Difficulty.values().getOrNull(score.difficulty)?.name?.lowercase() ?: ""
            val result = if (score.victory) "victory" else "sector ${score.sector}"
            line(X + 20, "${score.score}  ${score.shipName}, $result, $difficulty")
            y += textFont.lineSpacing
        }

        y += textFont.lineSpacing
        val S = VanillaProfile.Stats
        for ((label, most, total) in listOf(
            Triple("Ships defeated", S.MOST_SHIPS_DEFEATED, S.TOTAL_SHIPS_DEFEATED),
            Triple("Beacons explored", S.MOST_BEACONS_EXPLORED, S.TOTAL_BEACONS_EXPLORED),
            Triple("Scrap collected", S.MOST_SCRAP_COLLECTED, S.TOTAL_SCRAP_COLLECTED),
            Triple("Crew hired", S.MOST_CREW_HIRED, S.TOTAL_CREW_HIRED),
        )) {
            line(X + 20, "$label: most ${r[most]}, total ${r[total]}")
            y += textFont.lineSpacing
        }
        line(X + 20, "Games played: ${r[S.TOTAL_GAMES_PLAYED]}, victories: ${r[S.TOTAL_VICTORIES]}")

        y = Y + 76
        val x2 = X + 20 + colW
        line(x2, "CREW RECORDS")
        y += textFont.lineSpacing
        for ((label, rec) in CREW_RECORDS.zip(stats.crewRecords)) {
            line(x2, if (rec.value == 0) "$label: -" else "$label: ${rec.value} (${rec.name})")
            y += textFont.lineSpacing
        }

        y += textFont.lineSpacing
        line(x2, "BEST PER SHIP")
        y += textFont.lineSpacing
        for (score in stats.shipBest.sortedByDescending { it.score }.take(8)) {
            line(x2, "${score.score}  ${score.shipName}")
            y += textFont.lineSpacing
        }
    }

    private fun button(
        g: Graphics, mouseX: Int, mouseY: Int,
        x: Int, y: Int, w: Int, h: Int,
        label: String, selected: Boolean = false, action: () -> Unit
    ) {
        val hovering = selected || (mouseX in x until x + w && mouseY in y until y + h)

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

    /**
     * Unlock every ship (all layouts) without importing, e.g. for testing.
     * Achievements are left to be earned.
     */
    private fun unlockAllShips() {
        var count = 0
        for (family in families.families) {
            if (family.unlockId == null || family.isUnlocked(profile))
                continue
            profile.unlockShip(family, Difficulty.EASY)
            count++
        }
        setStatus(if (count == 0) "Every ship is already unlocked." else "Unlocked $count ships. Achievements are still to be earned.", false)
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
        private const val ROWS = 15
        private const val PER_PAGE = ROWS * 2

        private val CREW_RECORDS = listOf("Repairs", "Combat kills", "Piloted evasions", "Jumps survived", "Skill masteries")

        private const val W = 840
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

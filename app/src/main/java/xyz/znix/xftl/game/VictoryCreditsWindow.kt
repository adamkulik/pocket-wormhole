package xyz.znix.xftl.game

import xyz.znix.xftl.FTLFile
import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

/**
 * Vanilla's ending credits (GitHub issue #178), shown when the Rebel
 * Flagship's third and final stage is destroyed. It displays the vanilla
 * thank-you blurb ("Thanks to the valiant effort of: <ship>... and her
 * successful crew: <names>", followed by the victory line) and then scrolls
 * the staff/backer roll from data/credits.txt. When the roll finishes - or
 * the player clicks/skips - the victory screen is shown.
 *
 * The retail game shows no BOSS_DESTROYED dialogue at this point: the
 * credits appear straight after the explosion and the victory results screen
 * follows them. (Verified against FTL 1.6.14.)
 */
class VictoryCreditsWindow(
    private val game: InGameState,
    private val onFinished: () -> Unit,
) : Window() {
    override val size: IPoint = ConstPoint(1280, 720)

    // We draw our own full-screen background, so PlayerShipUI shouldn't tint
    // the game behind this window.
    override val appliesSelfTint = true

    private val background = game.getImg("img/stars/bg_dullstars2.png")

    private val labelFont = game.getFont("HL1", 2f)
    private val shipFont = game.getFont("HL2", 3f)
    private val crewFont = game.getFont("HL2", 2f)
    private val bodyFont = game.getFont("HL1", 2f)
    private val rollFont = game.getFont("HL1", 1.5f)

    private class Line(val text: String, val font: SILFontLoader, val height: Float)

    private val lines = ArrayList<Line>()

    // The credits scroll at a constant speed after a short pause that lets the
    // thank-you blurb be read.
    private val startNanos = System.nanoTime()
    private var finishRequested = false
    private var finished = false

    init {
        // Vanilla plays the victory theme as the credits appear.
        game.sounds.getSampleOrWarn("victory")?.play()

        addLine(game.translator["thank_ship"], labelFont, 30f)
        addLine(shipDisplayName(), shipFont, 54f)
        addLine("", labelFont, 30f)
        addLine(game.translator["thank_crew"], labelFont, 30f)
        addLine(crewNames(), crewFont, 42f)
        addLine("", labelFont, 90f)
        for (line in bodyFont.wrapString(game.translator["credit_victory"], 1000)) {
            addLine(line, bodyFont, 32f)
        }
        // Leave plenty of gap before the roll, so no names are visible
        // during the initial pause (the blurb is ~340px tall).
        addLine("", labelFont, 420f)
        for (line in creditsLines()) {
            addLine(line, rollFont, 20f)
        }
    }

    private fun addLine(text: String, font: SILFontLoader, height: Float) {
        lines += Line(text, font, height)
    }

    /** The ship's translated display name, eg "The Kestrel". */
    private fun shipDisplayName(): String {
        val title = game.player.type.shipTitle ?: return game.player.name
        return game.translator[title]
    }

    private fun crewNames(): String {
        val names = game.player.crew
            .filterIsInstance<LivingCrew>()
            .filter { it.health > 0f }
            .map { it.info.name }
        return names.joinToString(", ")
    }

    private fun creditsLines(): List<String> {
        val text = game.content.datafile.readString(FTLFile("data/credits.txt"))
        return text.split('\n')
            .map { it.trimEnd('\r').trim() }
            .filter { it.isNotEmpty() }
    }

    private val contentHeight: Float
        get() = lines.sumOf { it.height.toDouble() }.toFloat()

    override fun draw(g: Graphics) {
        background.draw(0f, 0f)

        val elapsed = (System.nanoTime() - startNanos) / 1_000_000_000f
        val offset = ((elapsed - CREDITS_DELAY) * SCROLL_SPEED).coerceAtLeast(0f)

        var y = TOP_MARGIN - offset
        for (line in lines) {
            // Only draw what's on screen - the backer roll is huge.
            if (y > -line.height && y < size.y && line.text.isNotEmpty()) {
                line.font.drawStringCentred(size.x / 2f, y, 0f, line.text, Colour.white)
            }
            y += line.height
        }

        if (!finishRequested && offset > TOP_MARGIN + contentHeight + size.y) {
            finishRequested = true
        }
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        // Clicking skips the rest of the roll, as a convenience.
        requestFinish()
    }

    override fun updateUI(x: Int, y: Int) {
        // Show the victory screen on the frame after the roll ends (never
        // mid-draw), which also covers the click/escape skip.
        if (finishRequested && !finished) {
            finished = true
            onFinished()
        }
    }

    override fun escapePressed() {
        requestFinish()
    }

    private fun requestFinish() {
        finishRequested = true
    }

    companion object {
        // Read the blurb before the roll starts moving.
        private const val CREDITS_DELAY = 3.5f

        // Pixels per second the roll moves.
        private const val SCROLL_SPEED = 220f

        // Where the thank-you blurb starts.
        private const val TOP_MARGIN = 150f
    }
}

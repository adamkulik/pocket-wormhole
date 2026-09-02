package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.GameMap
import org.newdawn.slick.geom.Rectangle

class SectorMapWindow(private val game: InGameState, private val selectedCallback: (GameMap.SectorInfo?) -> Unit) :
    Window() {

    override val size = ConstPoint(567, 327)

    private val titleFont = game.getFont("HL2", 3f)
    private val sectorColourFont = game.getFont("HL1", 2f)
    private val sectorNameFont = game.getFont("JustinFont8")

    private val backgroundImage = game.getImg("img/map/sector_box.png")
    private val closeButtonOutline = game.getImg("img/storeUI/store_close_base.png")
    private val targetBoxHover = game.getImg("img/map/map_targetbox_sector_g.png")
    private val targetBoxOption = game.getImg("img/map/map_targetbox_sector_y.png")

    private val playerShip = game.getImg("img/map/map_icon_ship.png")

    private val closeButton = Buttons.BasicButton(
        game, size + ConstPoint(-132, 7),
        ConstPoint(103, 32), game.translator["button_close"],
        4, titleFont, 25,
        this::escapePressed
    )

    private val currentSector = game.currentBeacon.sector.info
    private val visitedSectors = game.visitedSectors

    // All the sectors we can reach from our current position (or from
    // the currently-hovered beacon, if there is one).
    private val accessible = HashSet<GameMap.SectorInfo>()

    // The potential beacons we can jump to, ordered by Y coordinate
    private val nextSectors = currentSector.nextSectors.sortedBy { it.columnIndex }

    private var hoveredSector: GameMap.SectorInfo? = null

    private val startingNanos = System.nanoTime()

    private val nextSectorNameBoxes = ArrayList<Rectangle>()

    init {
        buttons += closeButton

        // Figure out which sectors are possible to access
        updateAccessibleSectors(null)
    }

    override fun draw(g: Graphics) {
        // Subtract out the glow, so 0,0 is at the top-left pixel (if you got rid
        // of the bevel).
        backgroundImage.draw(position.x - 7, position.y - 7)

        // Draw the window title
        titleFont.drawString(
            position.x + 13f, position.y + 25f,
            game.translator["sector_title"],
            Constants.JUMP_DISABLED_TEXT
        )

        // There's 149 pixels between the LHS of the button and the RHS
        // of the window, plus 6 pixels of glow on the button outline.
        closeButtonOutline.draw(position.x + size.x - 149 - 6, position.y + size.y)
        closeButton.draw(g)

        val sectors = game.gameMap.sectors

        for (column in sectors) {
            for (sector in column) {
                drawSector(g, sector)
            }
        }

        // Draw the labels naming the next sectors
        val updateBoxes = nextSectorNameBoxes.isEmpty()
        for (sector in nextSectors) {
            drawSectorLabel(g, sector, updateBoxes)
        }

        // Draw the cutout that says what each colour means
        var cutoutX = position.x + 10
        val cutoutY = position.y + size.y - 28

        drawCutoutBevel(g, cutoutX, cutoutY, true)
        cutoutX += 3
        cutoutX += drawCutoutLabel(
            g, cutoutX, cutoutY, true, false,
            Constants.SECTOR_CIVILIAN, game.translator["sector_legend_civilian"]
        )

        cutoutX += 3 // Margin between labels

        cutoutX += drawCutoutLabel(
            g, cutoutX, cutoutY, false, false,
            Constants.SECTOR_HOSTILE, game.translator["sector_legend_hostile"]
        )

        cutoutX += 3 // Margin between labels
        cutoutX += drawCutoutLabel(
            g, cutoutX, cutoutY, false, true,
            Constants.SECTOR_NEBULA, game.translator["sector_legend_nebula"]
        )
        drawCutoutBevel(g, cutoutX, cutoutY, false)
    }

    private fun getSectorPos(info: GameMap.SectorInfo, out: Point) {
        // Find the x,y of the top-left corner (if you filled the corner in)
        // of the sector icon.
        out.x = position.x + 43
        out.y = position.y + 75

        val sectorsInColumn = game.gameMap.sectors[info.columnNumber].size

        // Offset the first sector so they're all centred
        out.y += (4 - sectorsInColumn) * SECTOR_Y_SPACING / 2

        // And space them out appropriately
        out.x += info.columnNumber * SECTOR_X_SPACING
        out.y += info.columnIndex * SECTOR_Y_SPACING
    }

    private fun drawSector(g: Graphics, info: GameMap.SectorInfo) {
        val pos = Point(0, 0)
        val other = Point(0, 0)

        getSectorPos(info, pos)

        var colour = when (info.sectorClass) {
            GameMap.SectorClass.CIVILIAN -> Constants.SECTOR_CIVILIAN
            GameMap.SectorClass.HOSTILE -> Constants.SECTOR_HOSTILE
            GameMap.SectorClass.NEBULA -> Constants.SECTOR_NEBULA
        }
        var branchColour = Constants.SECTOR_BRANCH

        if (hoveredSector == info) {
            // Make the hovered branch and beacon turn green
            branchColour = Constants.SECTOR_BRANCH_HOVER
        }

        if (visitedSectors.contains(info)) {
            // If we've already been to this sector, highlight it appropriately
            branchColour = Constants.SECTOR_BRANCH_PATH
        } else if (!accessible.contains(info)) {
            // Grey out this sector, it's impossible to reach
            colour = Colour(colour.r / 4, colour.g / 4, colour.b / 4)
            branchColour = Constants.SECTOR_BRANCH_GREYED
        }

        // First draw all the branches, since we'll later draw
        // the next sector on top so we don't have to care about
        // drawing over the sector circle here.
        g.lineWidth = 2f
        for (next in info.nextSectors) {
            getSectorPos(next, other)

            g.colour = when {
                hoveredSector == next && info == currentSector -> Constants.SECTOR_BRANCH_HOVER
                accessible.contains(next) && accessible.contains(info) -> Constants.SECTOR_BRANCH
                visitedSectors.contains(next) && visitedSectors.contains(info) -> Constants.SECTOR_BRANCH_PATH
                else -> Constants.SECTOR_BRANCH_GREYED
            }
            g.drawLine(
                pos.x.f + SECTOR_RADIUS, pos.y.f + SECTOR_RADIUS,
                other.x.f + SECTOR_RADIUS, other.y.f + SECTOR_RADIUS
            )
        }
        g.lineWidth = 1f

        drawSectorCircle(g, pos.x, pos.y, branchColour, colour)

        // Draw the player ship. Note this is duplicated from JumpWindow, though
        // the player ship offset was changed to move it closer in.
        if (info == currentSector) {
            val periodNS = 20_000_000_000
            val timerNS = (System.nanoTime() % periodNS).toFloat()
            val rotation = timerNS / periodNS * 360f

            val centreX = pos.x + SECTOR_RADIUS
            val centreY = pos.y + SECTOR_RADIUS

            // These offsets are approximate
            g.pushTransform()
            g.rotate(centreX.f, centreY.f, -rotation)
            playerShip.draw(centreX - 12, centreY - 32)
            g.popTransform()
        }

        // Draw the hover icon, if appropriate
        val isOption = nextSectors.contains(info)
        if (isOption) {
            // We need the centre point position
            pos.x += SECTOR_RADIUS
            pos.y += SECTOR_RADIUS

            when (hoveredSector) {
                info -> drawTargetBox(g, pos, true)
                null -> drawTargetBox(g, pos, false)
                else -> Unit // Don't draw the box if the other beacon is hovered
            }
        }
    }

    private fun drawSectorCircle(g: Graphics, x: Int, y: Int, outer: Colour, inner: Colour) {
        val outerDiameter = SECTOR_RADIUS * 2f
        val innerDiameter = (SECTOR_RADIUS - 1) * 2f

        g.colour = outer
        g.fillOval(x.f, y.f, outerDiameter, outerDiameter)
        g.colour = inner
        g.fillOval(x + 1f, y + 1f, innerDiameter, innerDiameter)
    }

    private fun drawSectorLabel(g: Graphics, sector: GameMap.SectorInfo, updateBoxes: Boolean) {
        val pos = Point(0, 0)
        getSectorPos(sector, pos)

        val colour = when (hoveredSector) {
            sector -> Constants.SECTOR_NAME_TEXT_HOVER
            null -> Constants.SECTOR_NAME_TEXT
            else -> Constants.SECTOR_NAME_TEXT_GREYED
        }

        val index = nextSectors.indexOf(sector)

        // If the player has two options, that determines whether the box
        // goes above or below the beacon. Otherwise the label is always
        // above the sector.
        // Speedruns are a good way to find this kind of thing out, particularly
        // if they have the timer that shows what sector they're in - you can
        // seek through the video to find the jumps easily.
        if (index == 0) {
            pos.y -= 33
        } else {
            pos.y += 27
        }

        val name = game.translator[sector.type.shortTextId]
        val text = "%d. %s".format(index + 1, name)

        val textOffset = 6
        val width = textOffset + sectorNameFont.getWidth(text) + 2
        val height = 18

        pos.x = pos.x + 49 - width

        // Draw the outline
        g.colour = colour
        g.drawRect(pos.x.f, pos.y.f, width.f, height.f)

        // And the transparent background
        g.colour = Constants.SECTOR_NAME_BACKGROUND
        g.fillRect(pos.x + 1f, pos.y + 1f, width - 2f, height.f - 2f)

        // Finally, draw the sector name
        sectorNameFont.drawString(pos.x.f + textOffset, pos.y + 13f, text, colour)

        // If needed, register the bounds of this name box so it can be hovered
        if (updateBoxes) {
            val rect = Rectangle(pos.x.f, pos.y.f, width.f, height.f)
            nextSectorNameBoxes.add(rect)
        }
    }

    override fun updateUI(x: Int, y: Int) {
        super.updateUI(x, y)

        val pos = Point(0, 0)

        val oldHovered = hoveredSector
        hoveredSector = null

        // There's a debug flag that lets you jump to any beacon immediately,
        // and it's convenient to make it also apply to sectors.
        val clickable = when {
            game.debugFlags.anyJump.set -> game.gameMap.sectors.flatten()
            else -> nextSectors
        }

        // Check if the sector icons are hovered
        for (sector in clickable) {
            getSectorPos(sector, pos)

            // Add in a small margin to make the selection feel a bit more reliable
            val margin = 5

            // Check if we're hovering over this beacon?
            if (x !in pos.x - margin..pos.x + SECTOR_RADIUS * 2 + margin)
                continue
            if (y !in pos.y - margin..pos.y + SECTOR_RADIUS * 2 + margin)
                continue

            hoveredSector = sector
        }

        // Check if one of the name boxes is hovered
        for ((i, rect) in nextSectorNameBoxes.withIndex()) {
            if (!rect.contains(x.f, y.f))
                continue

            hoveredSector = nextSectors[i]
        }

        if (oldHovered != hoveredSector) {
            updateAccessibleSectors(hoveredSector)
        }
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        super.mouseClick(button, x, y)

        // If a sector is hovered, jump to it.
        hoveredSector?.let(selectedCallback)
    }

    private fun updateAccessibleSectors(hovered: GameMap.SectorInfo?) {
        accessible.clear()

        val next = ArrayList<GameMap.SectorInfo>()

        // If we're hovering over a sector, grey out all the sectors
        // we couldn't reach after jumping to it. Don't grey out
        // the current sector, though.
        if (hovered != null) {
            next.add(hovered)
            accessible.add(currentSector)
        } else {
            next.add(currentSector)
        }

        // Perform a simple wavefront-style walk through all the accessible sectors
        while (next.isNotEmpty()) {
            accessible.addAll(next)
            val newNext = next.flatMap { it.nextSectors }
            next.clear()
            next.addAll(newNext)
        }
    }

    private fun drawTargetBox(g: Graphics, pos: IPoint, hover: Boolean) {
        val targetBox = if (hover) targetBoxHover else targetBoxOption

        // We can re-use JumpWindow's implementation
        JumpWindow.drawTargetMarkers(g, targetBox, pos.x, pos.y)
    }

    // Draw one of the cutouts for the description of a beacon type
    private fun drawCutoutBevel(g: Graphics, x: Int, y: Int, leftSide: Boolean) {
        // TODO Consolidate this functionality and JumpWindow's drawCutout
        g.colour = Constants.SECTOR_TYPE_CUTOUT
        g.lineWidth = 1f

        for (i in 0..2) {
            val cutout = if (leftSide) 3 - i else i + 1
            val px = x + i
            g.drawLine(px.f, y.f + cutout, px.f, y.f + TYPE_WINDOW_HEIGHT - cutout - 1f)
        }
    }

    private fun drawCutoutLabel(
        g: Graphics, x: Int, y: Int,
        bevelledStart: Boolean, bevelledEnd: Boolean,
        colour: Colour, name: String
    ): Int {
        // For alignment purposes, the bevel counts as part of the padding
        val originX = if (bevelledStart) x - 3 else x

        val prePadding = 7
        val beaconToText = 8
        val textWidth = sectorColourFont.getWidth(name)
        val postTextPadding = if (bevelledEnd) 3 else 6
        var width = prePadding + SECTOR_RADIUS * 2 + beaconToText + textWidth + postTextPadding

        if (bevelledStart)
            width -= 3

        g.colour = Constants.SECTOR_TYPE_CUTOUT
        g.fillRect(x.f, y.f, width.f, TYPE_WINDOW_HEIGHT.f)

        drawSectorCircle(g, originX + prePadding, y + 5, Constants.SECTOR_BRANCH, colour)

        val textX = originX + prePadding + SECTOR_RADIUS * 2 + beaconToText
        sectorColourFont.drawString(textX.f, y + 18f, name, Constants.SECTOR_TYPE_CUTOUT_TEXT)

        return width
    }

    override fun escapePressed() {
        selectedCallback(null)
    }

    companion object {
        const val SECTOR_Y_SPACING = 50
        const val SECTOR_X_SPACING = 66

        const val SECTOR_RADIUS = 7

        // The height of the little window at the bottom that says what
        // each of the sector colours mean.
        const val TYPE_WINDOW_HEIGHT = 23
    }
}

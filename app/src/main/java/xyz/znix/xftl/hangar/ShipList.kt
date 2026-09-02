package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.f
import xyz.znix.xftl.game.UIUtils
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

class ShipList(private val state: SelectShipState, private val close: (LazyShipBlueprint?) -> Unit) {
    private val pos = Point(0, 0)
    private val size = Point(0, 0)

    private val titleTab = state.getImg("img/map/side_beaconmap.png")
    private val titleFont = state.getFont("HL2").apply { scale = 2f }

    private val title = state.translator["shiplist_title"]

    private val ships: List<LazyShipBlueprint>

    private var offsetY: Int = 0
    private var maxY: Int = 0

    private var hovered: LazyShipBlueprint? = null

    init {
        // Ships in families only get the A variant, other (modded in) ships
        // all show up separately.
        val nonFamilyShips = state.ships.filter { !state.shipFamilies.byShipId.containsKey(it.name) }
        val aVariants = state.shipFamilies.families.map { family -> state.ships.first { it.name == family.ships[0] } }
        ships = aVariants + nonFamilyShips
    }

    fun draw(container: GameContainer, g: Graphics) {
        g.colour = Colour(25, 25, 25, 100)
        g.fillRect(0, 0, container.width, container.height)

        val xMargin = 100
        val yMargin = 75
        pos.set(xMargin, yMargin)
        size.set(container.width - xMargin * 2, container.height - yMargin * 2)

        // Build the title, copied from BlueprintSelector
        val startWidth = 20
        val endWidth = 38
        val textWidth = titleFont.getWidth(title)
        val titleTabWidth = startWidth + textWidth + endWidth

        val shipName = hovered?.shipClass?.let { state.translator[it] } ?: hovered?.name
        val shipNameWidth = shipName?.let { titleFont.getWidth(it) } ?: 0
        val shipNameTabWidth = startWidth + shipNameWidth + endWidth
        val shipNameX = pos.x + size.x + 7 - shipNameTabWidth

        // Subtract out the glow
        val tabX = pos.x - 7
        val tabY = pos.y - 7

        state.windowRenderer.renderMasked(pos.x, pos.y, size.x, size.y, 0, 0, {
            g.colour = Colour.red // Anything non-transparent will do

            // Draw the title tab on the left
            g.fillRect(tabX.f, tabY.f, titleTabWidth.f, titleTab.height.f)

            // Draw the ship name tab on the right
            if (shipName != null) {
                g.fillRect(shipNameX.f, tabY.f, shipNameTabWidth.f, titleTab.height.f)
            }
        }, {
            drawBody(g)
        })

        UIUtils.drawTab(titleFont, title, titleTab, tabX.f, tabY.f, startWidth.f, endWidth.f)
        titleFont.drawString(tabX + startWidth.f, pos.y + 24f, title, Constants.JUMP_DISABLED_TEXT)

        // Draw the ship name tab on the left
        if (shipName != null) {
            // Draw mirrored
            g.pushTransform()
            g.translate(pos.x + size.x + 7f, tabY.f)
            g.scale(-1f, 1f)
            UIUtils.drawTab(titleFont, shipName, titleTab, 0f, 0f, startWidth.f, endWidth.f)
            g.popTransform()

            // Space by endWidth, since it's the left-most one
            titleFont.drawString(shipNameX + endWidth.f, pos.y + 24f, shipName, Constants.JUMP_DISABLED_TEXT)
        }
    }

    private fun drawBody(g: Graphics) {
        var row = 0
        var col = 0

        val cellWidth = 200
        val cellHeight = 120

        hovered = null

        for (ship in ships) {
            val x = pos.x + 30 + col * cellWidth
            val y = pos.y + 50 + row * cellHeight - offsetY

            // Filter out any ships with missing images here, though there
            // hopefully shouldn't be any.
            val miniPath = "img/customizeUI/miniship_${ship.img}.png"
            val img = state.getImgOrNull(miniPath) ?: continue
            img.draw(x, y)

            col++
            if (x + 200 + img.width > pos.x + size.x) {
                col = 0
                row++
            }

            // The last ship to set this will always be the lowest one.
            // Also, cancel out the scroll here - this is used to determine.
            // how much we're allowed to scroll.
            maxY = y + img.height + offsetY

            if (
                state.mousePos.x - pos.x in 0..size.x && state.mousePos.y - pos.y in 0..size.y &&
                state.mousePos.x - x in 0..cellWidth && state.mousePos.y - y in 0..cellHeight
            ) {
                hovered = ship
            }
        }
    }

    fun mouseClicked(button: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        // Clicking outside the window closes it
        if (state.mousePos.x - pos.x !in 0..size.x || state.mousePos.y - pos.y !in 0..size.y) {
            close(null)
            return
        }

        hovered?.let { close(it) }
    }

    fun mouseWheelMoved(change: Int) {
        // 30px as a margin
        val maxScroll = maxY - (pos.y + size.y) + 30
        if (maxScroll <= 0) {
            offsetY = 0
            return
        }
        offsetY = (offsetY - change / 5).coerceIn(0..maxScroll)
    }
}

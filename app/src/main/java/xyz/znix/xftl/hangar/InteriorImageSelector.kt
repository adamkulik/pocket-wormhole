package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants.ROOM_SIZE
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.Input

class InteriorImageSelector(val editor: ShipEditor, val room: EditableRoom) : EditorMenu {
    private val imagesMeta: List<RoomImageMeta.RoomImage>

    val size = ConstPoint(800, 600)
    val pos = editor.getCentralScreenPosition(size)

    private var hovering: Int? = null

    private val mousePos = Point(0, 0)

    private val scale = 2
    private val roomPixelWidth = room.w * ROOM_SIZE
    private val roomPixelHeight = room.h * ROOM_SIZE

    init {
        val systemType = room.system!!.getBP(editor.state)

        // Show all the images, not just the ones we think are suitable based on
        // which pixels are transparent.
        // Do filter out ones that are the wrong size, though.
        imagesMeta = editor.state.roomImageMeta.roomImages
            .filter { it.matchesSystem(systemType) }
            .filter { it.size.x == room.w && it.size.y == room.h }
    }

    override fun draw(g: Graphics) {
        val title = "SELECT INTERIOR IMAGE"
        editor.state.windowRenderer.renderWithTitleTab(
            g,
            editor.titleTab, editor.titleFont,
            pos.x, pos.y,
            size.x, size.y,
            title
        )

        hovering = null

        val baseX = pos.x + 30
        var x = baseX
        var y = pos.y + 50

        drawOption(g, 0, x, y, null)
        for ((index, meta) in imagesMeta.withIndex()) {
            drawOption(g, index + 1, x, y, meta)

            x += roomPixelWidth * scale + 20

            // Warp-around if necessary.
            if (x + roomPixelWidth * scale >= pos.x + size.x - 30) {
                x = baseX
                y += roomPixelHeight * scale + 20
            }
        }
    }

    private fun drawOption(g: Graphics, index: Int, x: Int, y: Int, meta: RoomImageMeta.RoomImage?) {
        val isHovering = mousePos.x - x in 0..roomPixelWidth * scale && mousePos.y - y in 0..roomPixelHeight * scale
        if (isHovering) {
            hovering = index
        }

        g.pushTransform()
        g.translate(x.f, y.f)
        g.scale(2f, 2f)

        // Draw the image with its floor
        EditableRoom.drawFloor(g, 0, 0, room.w, room.h)
        meta?.let { editor.state.getImg(it.path) }?.draw(0f, 0f)

        if (isHovering) {
            g.colour = Colour(0.5f, 1f, 1f, 0.25f)
            g.fillRect(0f, 0f, roomPixelWidth.f, roomPixelHeight.f)
        }

        // Draw an arrow pointing at the computer
        if (meta?.computerPoint != null && DEBUG_SHOW_COMPUTER_ARROW) {
            val co = meta.computerPoint
            g.translate(
                co.x * ROOM_SIZE.f,
                co.y * ROOM_SIZE.f
            )
            g.rotate(ROOM_SIZE / 2f, ROOM_SIZE / 2f, meta.computerDirection!!.angle.f)

            g.colour = Colour.red
            g.lineWidth = 2f
            g.drawLine(15f, 15f, 15f, 10f)
            g.drawLine(15f, 10f, 11f, 14f)
            g.drawLine(15f, 10f, 19f, 14f)
            g.lineWidth = 1f
        }

        g.popTransform()
    }

    override fun mouseMoved(x: Int, y: Int) {
        mousePos.set(x, y)
    }

    override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        val hoverIndex = hovering ?: return

        // 0 is for no image
        val sys = room.system!!
        val meta = imagesMeta.getOrNull(hoverIndex - 1)
        sys.interiorImage = meta?.path
        sys.computerPoint = meta?.computerPoint
        sys.computerDirection = meta?.computerDirection
        editor.closeMenu(this)
    }

    companion object {
        // Set this to true to show where the game thinks the computers are
        // This is useful when working on BakeRoomImageMeta
        private const val DEBUG_SHOW_COMPUTER_ARROW = false
    }
}

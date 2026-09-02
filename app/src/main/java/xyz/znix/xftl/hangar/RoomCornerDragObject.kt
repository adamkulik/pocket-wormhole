package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

class RoomCornerDragObject(
    val editor: ShipEditor,
    val room: EditableRoom, val right: Boolean, val bottom: Boolean
) : DragObject {
    private val gridX: Int get() = room.x + if (right) room.w else 0
    private val gridY: Int get() = room.y + if (bottom) room.h else 0

    override val dragX: Int get() = gridX * Constants.ROOM_SIZE
    override val dragY: Int get() = gridY * Constants.ROOM_SIZE
    override val selectPriority: Int get() = 10

    override fun draw(g: Graphics) {
        val px = gridX * Constants.ROOM_SIZE
        val py = gridY * Constants.ROOM_SIZE

        drawDragHandle(g, px, py, editor.isHovered(this))
    }

    override fun setGridPos(x: Int, y: Int) {
        val deltaX = x - gridX
        val deltaY = y - gridY

        if (deltaX == 0 && deltaY == 0)
            return

        @Suppress("DuplicatedCode")
        if (bottom) {
            room.h = (room.h + deltaY).coerceIn(ShipEditor.VALID_ROOM_SIZE)
        } else {
            // Adjust the top while still clamping the height
            val oldBottom = room.y + room.h
            room.h = (room.h - deltaY).coerceIn(ShipEditor.VALID_ROOM_SIZE)
            room.y = oldBottom - room.h
        }

        // Same as for the vertical dragging
        @Suppress("DuplicatedCode")
        if (right) {
            room.w = (room.w + deltaX).coerceIn(ShipEditor.VALID_ROOM_SIZE)
        } else {
            val oldRight = room.x + room.w
            room.w = (room.w - deltaX).coerceIn(ShipEditor.VALID_ROOM_SIZE)
            room.x = oldRight - room.w
        }

        // Pick a new suitable image for our system, if we have one, since the
        // previous one isn't the right size.
        room.system?.interiorImage = room.pickBestInteriorImage(editor)
    }

    // Can't be selected, only dragged.
    override fun canSelectFrom(mouseX: Int, mouseY: Int): Boolean = false

    override fun canStartDragging(mouseX: Int, mouseY: Int): Boolean {
        val px = gridX * Constants.ROOM_SIZE
        val py = gridY * Constants.ROOM_SIZE

        return mouseX in px - DRAG_BOX_SIZE / 2..px + DRAG_BOX_SIZE / 2
                && mouseY in py - DRAG_BOX_SIZE / 2..py + DRAG_BOX_SIZE / 2
    }

    override fun canHover(mouseX: Int, mouseY: Int): Boolean = canStartDragging(mouseX, mouseY)

    private fun drawDragHandle(g: Graphics, x: Int, y: Int, highlight: Boolean) {
        val originX = x - DRAG_BOX_SIZE / 2
        val originY = y - DRAG_BOX_SIZE / 2

        // Draw the background to make the handle easier to see
        g.colour = Colour(255, 255, 255, 200)
        g.fillRect(originX.f, originY.f, DRAG_BOX_SIZE.f, DRAG_BOX_SIZE.f)

        // Draw the line indicating the handle - note we have to -3 on the width/height
        // because drawRect draws one pixel larger than if you pass the same coordinates to fillRect.
        g.colour = when (highlight) {
            false -> Colour.black
            true -> Colour.blue
        }
        g.drawRect(originX + 1f, originY + 1f, DRAG_BOX_SIZE - 3f, DRAG_BOX_SIZE - 3f)
    }

    companion object {
        const val DRAG_BOX_SIZE: Int = 10
    }
}

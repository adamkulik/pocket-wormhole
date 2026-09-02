package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.abs
import kotlin.math.pow

class DoorObject(val editor: ShipEditor, val door: EditableDoor) : DragObject {
    private val image = editor.state.getImg("img/effects/door_sheet.png")
    private val highlight = editor.state.getImg("img/effects/door_highlight.png")

    override val selectPriority: Int get() = 5

    override var dragX: Int = 0
    override var dragY: Int = 0

    private var currentSnap: SnapLocation? = null

    private val isCreating: Boolean get() = this == editor.creatingDoor

    override fun draw(g: Graphics) {
        if (editor.isDragging(this) || isCreating) {
            drawDragging(g)
            return
        }
        currentSnap = null

        val showHighlight = editor.isHovered(this) || editor.isSelected(this)
        drawSnapped(g, door.x, door.y, showHighlight)
        return
    }

    private fun drawDragging(g: Graphics) {
        // Figure out if there's somewhere suitable to snap to.
        // The possible locations are on each side of the cursor's grid cell.

        // This means we don't have to handle rounding the position properly
        val mouseGridX = editor.mouseGridPos.x
        val mouseGridY = editor.mouseGridPos.y

        val snapLocations = arrayListOf(
            SnapLocation(mouseGridX, mouseGridY, true),
            SnapLocation(mouseGridX, mouseGridY, false),
            SnapLocation(mouseGridX, mouseGridY + 1, false),
            SnapLocation(mouseGridX + 1, mouseGridY, true)
        )
        snapLocations.sortBy { it.distSq() }

        currentSnap = null

        // Pick the first suitable location
        for (snap in snapLocations) {
            // Check if there's already a door here. This doesn't include
            // the door we're editing, so it's allowed to snap back to its
            // original location.
            if (editor.ship.doors.any { it.x == snap.x && it.y == snap.y && it.isVertical == snap.isVertical && it != door }) {
                continue
            }

            // Make sure there's at least one neighbouring room here.
            if (EditableDoor.findNeighbourRoom(editor.ship, snap.x, snap.y, snap.isVertical, null) == null)
                continue

            // Found a suitable location!
            currentSnap = snap
            EditableDoor.draw(g, image, snap.px, snap.py, snap.isVertical, highlight)
            return
        }

        // Draw the ship at the user's cursor, if it won't fit anywhere.
        EditableDoor.draw(g, image, dragX, dragY, door.isVertical, highlight)
    }

    private fun drawSnapped(g: Graphics, gridX: Int, gridY: Int, showHighlight: Boolean) {
        dragX = gridX * Constants.ROOM_SIZE + door.centreOffsetX
        dragY = gridY * Constants.ROOM_SIZE + door.centreOffsetY

        val highlightImg = if (showHighlight) highlight else null
        EditableDoor.draw(g, image, dragX, dragY, door.isVertical, highlightImg)
    }

    override fun canSelectFrom(mouseX: Int, mouseY: Int): Boolean {
        val deltaX = abs(mouseX - dragX)
        val deltaY = abs(mouseY - dragY)
        return deltaX.f.pow(2) + deltaY.f.pow(2) < SELECTION_RADIUS.f.pow(2)
    }

    override fun setPixelPos(x: Int, y: Int) {
        dragX = x
        dragY = y
    }

    override fun onDeletePressed() {
        editor.ship.doors.remove(door)
    }

    override fun onDropped(x: Int, y: Int) {
        val snap = currentSnap ?: return

        door.x = snap.x
        door.y = snap.y
        door.isVertical = snap.isVertical

        // In the case of doors the player is placing for the first time,
        // they're not in the ship's door list yet. So add it.
    }

    override fun onLeftClick(x: Int, y: Int) {
        if (!isCreating)
            return

        // Left-click places the door, if there's a suitable slot. Otherwise
        // it cancels placing it.
        if (currentSnap != null) {
            onDropped(x, y)
            editor.ship.doors.add(door)
        }
        editor.creatingDoor = null
    }

    companion object {
        // The distance away from the centre position the room can be selected from.
        // Don't use ROOM_SIZE/2, as we don't want the radius from doors
        // at 90° angles connecting to the same cell to overlap.
        const val SELECTION_RADIUS = 13
    }

    private inner class SnapLocation(val x: Int, val y: Int, val isVertical: Boolean) {
        // Pixel centre position
        val px: Int get() = x * Constants.ROOM_SIZE + if (isVertical) 0 else Constants.ROOM_SIZE / 2
        val py: Int get() = y * Constants.ROOM_SIZE + if (isVertical) Constants.ROOM_SIZE / 2 else 0

        fun distSq(): Float {
            val deltaX = abs(editor.mousePixelPos.x - px)
            val deltaY = abs(editor.mousePixelPos.y - py)
            return deltaX.f.pow(2) + deltaY.f.pow(2)
        }
    }
}

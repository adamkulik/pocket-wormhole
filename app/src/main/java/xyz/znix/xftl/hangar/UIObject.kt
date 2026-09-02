package xyz.znix.xftl.hangar

import xyz.znix.xftl.rendering.Graphics

/**
 * This represents something on-screen in the editor, which can optionally be selected.
 */
interface UIObject {
    /**
     * A higher priority will mean something is selected if it's
     * overlapping with another object, for example a drag box and it's room.
     */
    val selectPriority: Int

    /**
     * This is a list of any objects which 'belong' to this object, and
     * become visible when it's selected.
     *
     * Note this is called each update, so it's the implementation's responsibility
     * not to constantly re-create these objects (which may break interactions
     * with them), and only do so as required.
     */
    val subObjects: List<UIObject> get() = emptyList()

    fun draw(g: Graphics)

    fun canSelectFrom(mouseX: Int, mouseY: Int): Boolean
    fun canHover(mouseX: Int, mouseY: Int): Boolean = canSelectFrom(mouseX, mouseY)

    fun onDeletePressed() = Unit
    fun onLeftClick(x: Int, y: Int) = Unit
    fun onRightClick(x: Int, y: Int) = Unit

    /**
     * This is called when [ShipEditor.fullUpdateObjects] is called, and if [subObjects] can
     * change as the ship changes, it should be updated.
     */
    fun updateSubObjects() = Unit
}

/**
 * This represents any on-screen object that can be dragged around, while aligned to the grid.
 */
interface DragObject : UIObject {
    // The room grid position of this object
    val dragX: Int
    val dragY: Int

    fun setGridPos(x: Int, y: Int) = Unit
    fun setPixelPos(x: Int, y: Int) = Unit

    fun onDragStart() = Unit
    fun onDropped(x: Int, y: Int) = Unit

    // Check if a mouse position can be used to start dragging the object.
    fun canStartDragging(mouseX: Int, mouseY: Int): Boolean = canSelectFrom(mouseX, mouseY)
}

package xyz.znix.xftl.hangar

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.systems.Artillery
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import kotlin.math.pow

class SystemObject(
    val editor: ShipEditor,

    // The current room, or null if we're on a palette
    val room: EditableRoom?,

    // The normal position of the centre of this object
    var centreX: Int, var centreY: Int,

    val system: EditableSystem
) : UIObject, DragObject {

    val systemType = system.getBP(editor.state)

    override var dragX: Int = 0
    override var dragY: Int = 0

    override val selectPriority: Int get() = 5

    override fun draw(g: Graphics) {
        if (!editor.isDragging(this)) {
            dragX = centreX
            dragY = centreY
        }

        val iconColour = when {
            editor.isSelected(this) -> Constants.SYSTEM_IONISED
            editor.isHovered(this) -> Constants.SYSTEM_DAMAGED
            system.startingPower == 0 -> Colour.white
            else -> Constants.SYSTEM_NORMAL
        }

        val icon = editor.state.getImg(systemType.roomIconPath)
        icon.draw(
            dragX - icon.width / 2,
            dragY - icon.height / 2,
            iconColour
        )

        // Mark systems which aren't installed by default by drawing
        // them as an outline.
        if (system.startingPower == 0) {
            val outline = editor.state.getImg(systemType.roomIconOutlinePath)
            outline.draw(
                dragX - outline.width / 2,
                dragY - outline.height / 2,
                Colour.lightGray
            )
        }

        // For artillery systems, draw the weapon name.
        if (room != null && !editor.isDragging(this) && systemType.info == Artillery.INFO) {
            val weapon = system.artilleryWeapon?.let { editor.state.blueprints[it] } as AbstractWeaponBlueprint?
            val weaponName = if (weapon == null) "NO WEAPON!" else editor.state.translator[weapon.short!!]
            editor.font.drawStringCentred(dragX.f, dragY + 13f, 0f, weaponName, Colour.black)
        }
    }

    override fun canSelectFrom(mouseX: Int, mouseY: Int): Boolean {
        // Don't let the user select items in the palette, so that if we later
        // add a right-click menu for the artillery system we don't have to
        // deal with this fairly useless case.
        if (room == null)
            return false

        return canStartDragging(mouseX, mouseY)
    }

    override fun canHover(mouseX: Int, mouseY: Int): Boolean {
        return canStartDragging(mouseX, mouseY)
    }

    override fun canStartDragging(mouseX: Int, mouseY: Int): Boolean {
        val distSq = (mouseX - dragX).f.pow(2) + (mouseY - dragY).f.pow(2)
        return distSq < 15f.pow(2)
    }

    override fun setPixelPos(x: Int, y: Int) {
        dragX = x
        dragY = y
    }

    override fun onDropped(x: Int, y: Int) {
        val newRoom = editor.ship.rooms.firstOrNull { it.containsPixel(x, y) }

        if (newRoom == room) {
            return
        }

        // Swap the systems of the new and current rooms. If we're dragged
        // outside the ship, this clears the current room's system.
        // Make a copy of our system here, so that when dragging stuff
        // from the toolbox, it doesn't point to the system info.
        room?.system = newRoom?.system
        newRoom?.system = system.copy()

        // Moving a system to another room means the previous interior image
        // probably won't be suitable any more, so pick a new one.
        if (newRoom != null) {
            newRoom.system!!.interiorImage = newRoom.pickBestInteriorImage(editor)
        }

        // And do the same for the system we swapped with.
        if (room?.system != null) {
            room.system!!.interiorImage = room.pickBestInteriorImage(editor)
        }

        // Clear out duplicate systems, with special exceptions for artillery
        // (which allows multiple).
        // Note we allow both a medbay and clonebay to be installed, though
        // none of the vanilla ships use this.
        // Other than these cases it shouldn't be possible for a ship
        // to have multiple of a system, but this check is here just in case
        // that does somehow happen.
        for (room in editor.ship.rooms) {
            if (room == newRoom)
                continue

            val roomSystem = room.system?.getBP(editor.state) ?: continue

            // Multiple artillery weapons are allowed.
            if (systemType.info == Artillery.INFO)
                continue

            if (roomSystem.type == system.type) {
                room.system = null
            }
        }

        // Update the objects immediately to avoid flickering, as otherwise
        // the system could 'pop back' to the previous room for a frame.
        editor.fullUpdateObjects()
    }

    override fun onDeletePressed() {
        room?.system = null
    }
}

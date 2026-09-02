package xyz.znix.xftl.hangar

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.systems.Artillery
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import kotlin.math.roundToInt

class RoomObject(val editor: ShipEditor, val room: EditableRoom) : UIObject, DragObject {
    override val dragX: Int get() = room.x * Constants.ROOM_SIZE
    override val dragY: Int get() = room.y * Constants.ROOM_SIZE

    override val selectPriority: Int get() = 0

    override val subObjects: List<UIObject> = listOf(
        RoomCornerDragObject(editor, room, false, false),
        RoomCornerDragObject(editor, room, false, true),
        RoomCornerDragObject(editor, room, true, false),
        RoomCornerDragObject(editor, room, true, true)
    )

    var systemObject: SystemObject? = null
    private var lastSystem: EditableSystem? = null

    // This is the list of doors that are attached to the room when we're dragging it.
    private var draggingDoors: List<EditableDoor> = emptyList()

    init {
        updateSubObjects()
    }

    override fun setGridPos(x: Int, y: Int) {
        val deltaX = x - room.x
        val deltaY = y - room.y

        room.x = x
        room.y = y

        for (door in draggingDoors) {
            door.x += deltaX
            door.y += deltaY
        }
    }

    override fun canSelectFrom(mouseX: Int, mouseY: Int): Boolean {
        return room.containsPixel(mouseX, mouseY)
    }

    override fun canStartDragging(mouseX: Int, mouseY: Int): Boolean {
        // Only allow dragging when we're selected, to avoid accidentally
        // moving rooms around while dragging systems etc.
        if (!editor.isSelected(this))
            return false

        return super.canStartDragging(mouseX, mouseY)
    }

    override fun draw(g: Graphics) {
        if (lastSystem != room.system) {
            updateSubObjects()
        }

        // If we're dragged around, we need to update the system icon
        updateSystemPosition()

        if (!editor.isSelected(this))
            return

        g.colour = Colour(0, 255, 0, 100)
        g.fillRect(room.pixelX.f, room.pixelY.f, room.pixelWidth.f, room.pixelHeight.f)
    }

    override fun onDeletePressed() {
        editor.ship.rooms.remove(room)
    }

    override fun onRightClick(x: Int, y: Int) {
        if (!editor.isSelected(this))
            return

        val system = room.system
        val systemType = system?.getBP(editor.state)
        val isArtillery = systemType?.info == Artillery.INFO
        val artilleryWeaponEntry = if (!isArtillery) null else {
            PopupMenu.Entry("Artillery weapon") {
                val controller = object : BlueprintSelector.SelectionController {
                    override val title: String get() = "SELECT ARTILLERY WEAPON"

                    override fun select(blueprint: Blueprint) {
                        // Cast the blueprint back to a weapon
                        require(blueprint is AbstractWeaponBlueprint)

                        room.system?.artilleryWeapon = blueprint.name
                    }
                }

                editor.openMenu(BlueprintSelector(editor, editor.weaponBlueprints, controller))
            }
        }

        val startingPowerEntry = if (system == null || systemType == null) null else {
            val powers = ArrayList<PopupMenu.Entry>()
            for (i in systemType.maxPower downTo 0) {
                var label = when (i) {
                    0 -> "Not installed"
                    else -> i.toString()
                }
                if (i == system.startingPower) {
                    label = "[*] $label"
                }
                powers += PopupMenu.Entry(label) { system.startingPower = i }
            }
            PopupMenu.Entry("Starting power", powers) {}
        }

        val setInteriorImage = if (systemType == null) null else {
            PopupMenu.Entry("Set interior image") {
                editor.openMenu(InteriorImageSelector(editor, room))
            }
        }

        editor.openPopupMenu(
            artilleryWeaponEntry,
            startingPowerEntry,
            setInteriorImage
        )
    }

    override fun updateSubObjects() {
        systemObject = room.system?.let { SystemObject(editor, room, 0, 0, it) }
        lastSystem = room.system
        updateSystemPosition()
    }

    override fun onDragStart() {
        // Find all the doors that are only connected to this room, and move them with us.
        // We only find this when we start dragging, to avoid leaving them behind if the
        // room is dragged past another room.
        val doors = ArrayList<EditableDoor>()

        for (door in editor.ship.doors) {
            if (!door.isRoomNeighbour(room))
                continue

            // If the door connects to more than two rooms (which can only happen if rooms
            // are overlapped), we certainly don't care.
            val otherRoom = door.findNeighbourRoom(editor.ship, room)

            // Make sure we're the only connecting room.
            if (otherRoom != null)
                continue

            doors.add(door)
        }

        draggingDoors = doors
    }

    override fun onDropped(x: Int, y: Int) {
        draggingDoors = emptyList()
    }

    private fun updateSystemPosition() {
        val system = systemObject ?: return
        system.centreX = ((room.x + room.w / 2f) * Constants.ROOM_SIZE).roundToInt()
        system.centreY = ((room.y + room.h / 2f) * Constants.ROOM_SIZE).roundToInt()
    }
}

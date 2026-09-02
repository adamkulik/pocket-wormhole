package xyz.znix.xftl.hangar

import org.jdom2.Element
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Constants.ROOM_SIZE
import xyz.znix.xftl.ISystemConfiguration
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.systems.Weapons
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint

/**
 * A ship that can be rendered in the hangar.
 *
 * This is kept separate from [xyz.znix.xftl.Ship], since what's desirable
 * in-game (room locations etc being immutable) isn't suitable for ship that
 * can be edited.
 *
 * Note this doesn't reference any blueprints or images, so it's safe to use
 * after changing mods or otherwise re-creating the blueprint manager.
 */
class EditableShip(
    val baseBlueprint: String // ShipBlueprint name
) {
    val rooms = ArrayList<EditableRoom>()
    val doors = ArrayList<EditableDoor>()

    val weapons = ArrayList<String>() // AbstractWeaponBlueprint names
    val drones = ArrayList<String>() // DroneBlueprint names

    var weaponSlots: Int = 4
    var droneSlots: Int = 2

    fun draw(g: Graphics, state: SelectShipState, renderAll: Boolean) {
        val blueprint = state.blueprints.getShip(baseBlueprint)

        val hullImage = blueprint.hullImage.firstNotNullOf { state.getImgOrNull(it) }
        val floorImage = blueprint.floorImage?.let { state.getImg(it) }
        val hullOffset = blueprint.hullOffset
        val floorOffset = blueprint.floorOffset

        // Draw the weapons behind the hull images, so it covers up the edge of the texture.
        drawWeapons(g, state, blueprint)

        hullImage.draw(hullOffset.x, hullOffset.y)
        floorImage?.draw(floorOffset.x + hullOffset.x, floorOffset.y + hullOffset.y)

        // Draw the rooms
        for (room in rooms) {
            drawRoom(g, state, room, renderAll)
        }

        // Draw the doors
        val doorImage = state.getImg("img/effects/door_sheet.png")
        if (renderAll) {
            for (door in doors) {
                door.draw(g, doorImage)
            }
        }
    }

    private fun drawWeapons(g: Graphics, state: SelectShipState, shipBlueprint: ShipBlueprint) {
        for ((index, weapon) in weapons.withIndex()) {
            val weaponBp = state.blueprints[weapon] as AbstractWeaponBlueprint
            val hp = shipBlueprint.hardpoints[index]

            val anim = state.animations.weaponAnimations.getValue(weaponBp.launcher)
            val spriteSheet = state.getImg(anim.sheet.sheetPath)
            val launcher = anim.spriteAt(spriteSheet, anim.chargedFrame) ?: state.missingImage

            g.pushTransform()
            g.translate(-shipBlueprint.roomOffset.x.f * ROOM_SIZE, -shipBlueprint.roomOffset.y.f * ROOM_SIZE)
            Weapons.translateHardpoint(g, hp)
            g.translate(-anim.mountPoint.x.f, -anim.mountPoint.y.f)
            launcher.draw(0f, 0f)
            g.popTransform()
        }
    }

    private fun drawRoom(g: Graphics, state: SelectShipState, room: EditableRoom, drawSystems: Boolean) {
        val x = room.pixelX
        val y = room.pixelY

        EditableRoom.drawFloor(g, x, y, room.w, room.h)

        // Draw the room image
        val system = room.system
        if (system != null) {
            val img = system.interiorImage?.let { state.getImg(it) }
            img?.draw(x, y)
        }

        // Draw the system icon.
        // Note that when using the editor, it draws the system icons instead.
        if (system != null && drawSystems) {
            val icon = state.getImg(system.getBP(state).roomIconPath)
            icon.draw(
                x + (room.pixelWidth - icon.width) / 2,
                y + (room.pixelHeight - icon.height) / 2,
                Constants.SYSTEM_NORMAL
            )
        }
    }

    fun saveToXML(elem: Element) {
        SaveUtil.addAttr(elem, "baseBlueprint", baseBlueprint)

        SaveUtil.addAttrInt(elem, "weaponSlots", weaponSlots)
        SaveUtil.addAttrInt(elem, "droneSlots", droneSlots)

        val roomsElem = Element("room")
        for (room in rooms) {
            roomsElem.addContent("${room.x},${room.y},${room.w},${room.h} ")
        }
        elem.addContent(roomsElem)

        val doorElem = Element("door")
        for (door in doors) {
            val orientation = when (door.isVertical) {
                true -> 'v'
                false -> 'h'
            }
            doorElem.addContent("${door.x},${door.y},$orientation ")
        }
        elem.addContent(doorElem)

        for ((roomId, room) in rooms.withIndex()) {
            val system = room.system ?: continue

            val systemElem = Element("system")
            SaveUtil.addAttrInt(systemElem, "roomId", roomId)
            system.saveToXML(systemElem)
            elem.addContent(systemElem)
        }

        for (weapon in weapons) {
            val weaponElem = Element("startingWeapon")
            SaveUtil.addAttr(weaponElem, "name", weapon)
            elem.addContent(weaponElem)
        }
        for (drone in drones) {
            val droneElem = Element("startingDrone")
            SaveUtil.addAttr(droneElem, "name", drone)
            elem.addContent(droneElem)
        }
    }

    companion object {
        fun fromBlueprint(blueprint: ShipBlueprint): EditableShip {
            val ship = EditableShip(blueprint.name)

            val rooms = blueprint.rooms.map { EditableRoom(it.pos.x, it.pos.y, it.size.x, it.size.y) }
            ship.rooms.addAll(rooms)

            val doors = blueprint.doors.map { EditableDoor(it.pos.x, it.pos.y, it.isVertical) }
            ship.doors.addAll(doors)

            for (system in blueprint.systems) {
                val room = ship.rooms[system.room.id]
                val type = system.systemName
                val computerPos = system.slotNumber?.let { idx ->
                    ConstPoint(
                        idx % room.w,
                        idx / room.w
                    )
                }

                // If this room already has a system, don't replace it with a non-default system.
                // This is specifically to handle the case of medbays/clonebays.
                if (room.system != null && !system.availableByDefault) {
                    continue
                }

                room.system = EditableSystem(
                    type, system.interiorImage,
                    computerPos, system.slotDirection,
                    if (system.availableByDefault) system.startingPower else 0
                )

                // If this is an artillery system, set its weapon.
                if (system.weapon != null) {
                    val weapon = system.weapon
                    room.system!!.artilleryWeapon = weapon
                }
            }

            ship.weapons += blueprint.initialWeapons
            ship.drones += blueprint.initialDrones

            blueprint.weaponSlots?.let { ship.weaponSlots = it }
            blueprint.droneSlots?.let { ship.droneSlots = it }

            return ship
        }

        @JvmStatic
        fun loadFromXML(elem: Element): EditableShip {
            val ship = EditableShip(SaveUtil.getAttr(elem, "baseBlueprint"))

            ship.weaponSlots = SaveUtil.getAttrInt(elem, "weaponSlots")
            ship.droneSlots = SaveUtil.getAttrInt(elem, "droneSlots")

            val roomsElem = elem.getChild("room")
            for (roomStr in roomsElem.textTrim.split(' ', '\t', '\n')) {
                val parts = roomStr.split(',')
                require(parts.size == 4)
                val x = parts[0].toInt()
                val y = parts[1].toInt()
                val w = parts[2].toInt()
                val h = parts[3].toInt()
                ship.rooms += EditableRoom(x, y, w, h)
            }

            val doorElem = elem.getChild("door")
            for (doorStr in doorElem.textTrim.split(' ', '\t', '\n')) {
                val parts = doorStr.split(',')
                require(parts.size == 3)
                val x = parts[0].toInt()
                val y = parts[1].toInt()
                val isVertical = when (parts[2]) {
                    "v" -> true
                    "h" -> false
                    else -> error("Invalid door orientation: '${parts[2]}'")
                }
                ship.doors.add(EditableDoor(x, y, isVertical))
            }

            for (systemElem in elem.getChildren("system")) {
                val roomId = SaveUtil.getAttrInt(systemElem, "roomId")
                ship.rooms[roomId].system = EditableSystem.loadFromXML(systemElem)
            }

            for (weaponElem in elem.getChildren("startingWeapon")) {
                val name = SaveUtil.getAttr(weaponElem, "name")
                ship.weapons.add(name)
            }
            for (droneElem in elem.getChildren("startingDrone")) {
                val name = SaveUtil.getAttr(droneElem, "name")
                ship.drones.add(name)
            }

            return ship
        }
    }
}

class EditableRoom(
    // Position
    var x: Int,
    var y: Int,

    // Width/height
    var w: Int,
    var h: Int
) {
    var system: EditableSystem? = null

    val pixelX: Int get() = x * ROOM_SIZE
    val pixelY: Int get() = y * ROOM_SIZE

    val pixelWidth: Int get() = w * ROOM_SIZE
    val pixelHeight: Int get() = h * ROOM_SIZE

    val pixelRight get() = pixelX + pixelWidth
    val pixelBottom get() = pixelY + pixelHeight

    fun containsPixel(px: Int, py: Int): Boolean {
        return px in pixelX..pixelRight && py in pixelY..pixelBottom
    }

    fun findSuitableInteriorImages(editor: ShipEditor): List<String> {
        val system = system?.getBP(editor.state) ?: return emptyList()
        val images = editor.state.roomImageMeta

        val suitable = ArrayList<String>()

        outer@ for (image in images.roomImages) {
            if (!image.matchesSystem(system))
                continue

            if (image.size.x != w || image.size.y != h)
                continue

            // Make sure there's space in the image for all the doors
            for (door in editor.ship.doors) {
                if (!door.isRoomNeighbour(this)) {
                    continue
                }

                val doorX = door.x - x
                val doorY = door.y - y
                if (image.doorways.none { it.pos.x == doorX && it.pos.y == doorY && it.isVertical == door.isVertical }) {
                    continue@outer
                }
            }

            suitable += image.path
        }

        return suitable
    }

    fun pickBestInteriorImage(editor: ShipEditor): String? {
        val suitable = findSuitableInteriorImages(editor)

        // No suitable images?
        if (suitable.isEmpty())
            return null

        // Pick one of the suitable images
        return suitable.random()
    }

    companion object {
        fun drawFloor(g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val pixelWidth = width * ROOM_SIZE
            val pixelHeight = height * ROOM_SIZE

            g.colour = Constants.ROOM_BORDER_COLOUR
            g.fillRect(
                x.f,
                y.f,
                pixelWidth.f,
                pixelHeight.f
            )

            val wallThickness = 2
            g.colour = Constants.FLOOR_COLOUR
            g.fillRect(
                x + wallThickness.f,
                y + wallThickness.f,
                pixelWidth - 2f * wallThickness,
                pixelHeight - 2f * wallThickness
            )

            // Draw the floor grid
            g.colour = Constants.FLOOR_GRID_COLOUR
            for (i in 1 until width) {
                val lineX = x + i * ROOM_SIZE - 1
                g.drawLine(
                    lineX.f,
                    y + wallThickness.f,
                    lineX.f,
                    y + pixelHeight - wallThickness - 1f
                )
            }

            for (i in 1 until height) {
                val lineY = y + ROOM_SIZE * i - 1
                g.drawLine(
                    x + wallThickness.f,
                    lineY.f,
                    (x + pixelWidth - 1) - wallThickness - 1f,
                    lineY.f
                )
            }
        }
    }
}

class EditableDoor(
    var x: Int,
    var y: Int,
    var isVertical: Boolean
) {
    /**
     * The offset from the grid position to the centre of the door image, in the X axis.
     */
    val centreOffsetX: Int get() = if (isVertical) 0 else ROOM_SIZE / 2

    /**
     * The offset from the grid position to the centre of the door image, in the Y axis.
     */
    val centreOffsetY: Int get() = if (isVertical) ROOM_SIZE / 2 else 0

    fun draw(g: Graphics, doorsImage: Image) {
        draw(
            g, doorsImage,
            x * ROOM_SIZE + centreOffsetX,
            y * ROOM_SIZE + centreOffsetY,
            isVertical, null
        )
    }

    fun findNeighbourRoom(ship: EditableShip, exclude: EditableRoom?): EditableRoom? {
        return findNeighbourRoom(ship, x, y, isVertical, exclude)
    }

    fun isRoomNeighbour(room: EditableRoom): Boolean {
        return Companion.isRoomNeighbour(x, y, isVertical, room)
    }

    companion object {
        fun draw(g: Graphics, doorsImage: Image, centreX: Int, centreY: Int, isVertical: Boolean, highlight: Image?) {
            // Broken and level 1 doors use the same sprite, but broken doors
            // have a colour filter applied to them.
            val sheetY = ROOM_SIZE * 0.coerceAtLeast(0)

            // This is used to animate the door opening and closing, selecting the
            // correct frame for its motion.
            val sheetX = 0

            g.pushTransform()

            // Make the centre of the door sprite the origin
            g.translate(centreX.f, centreY.f)

            if (!isVertical) {
                g.rotate(0f, 0f, 90f)
            }

            highlight?.draw(-ROOM_SIZE / 2, -ROOM_SIZE / 2)

            doorsImage.drawSection(
                -ROOM_SIZE / 2, -ROOM_SIZE / 2, ROOM_SIZE, ROOM_SIZE,
                sheetX, sheetY
            )

            g.popTransform()
        }

        fun findNeighbourRoom(
            ship: EditableShip,
            cellX: Int, cellY: Int,
            vertical: Boolean,
            exclude: EditableRoom?
        ): EditableRoom? {
            for (room in ship.rooms) {
                if (room == exclude)
                    continue

                if (isRoomNeighbour(cellX, cellY, vertical, room))
                    return room
            }

            return null
        }

        fun isRoomNeighbour(cellX: Int, cellY: Int, vertical: Boolean, room: EditableRoom): Boolean {
            // Filter out rooms that we aren't in.
            if (cellX !in room.x..room.x + room.w)
                return false
            if (cellY !in room.y..room.y + room.h)
                return false

            // Check we're on a suitable edge.
            if (vertical) {
                if (cellY == room.y + room.h)
                    return false
                if (cellX == room.x || cellX == room.x + room.w)
                    return true
            } else {
                if (cellX == room.x + room.w)
                    return false
                if (cellY == room.y || cellY == room.y + room.h)
                    return true
            }

            return false
        }
    }
}

data class EditableSystem(
    val type: String, // SystemBlueprint name
    var interiorImage: String? = null,
    var computerPoint: ConstPoint? = null,
    var computerDirection: Direction? = null,

    /**
     * The level this system starts at, or 0 for systems that aren't installed by default.
     */
    var startingPower: Int = 0,

    var artilleryWeapon: String? = null // AbstractWeaponBlueprint name
) {
    fun getBP(state: SelectShipState): SystemBlueprint = state.blueprints[type] as SystemBlueprint

    fun saveToXML(elem: Element) {
        SaveUtil.addAttr(elem, "type", type)
        SaveUtil.addAttrInt(elem, "startingPower", startingPower)
        interiorImage?.let { img -> SaveUtil.addAttr(elem, "interiorImage", img) }
        artilleryWeapon?.let { name -> SaveUtil.addAttr(elem, "artilleryWeapon", name) }
        computerPoint?.let { pos ->
            SaveUtil.addAttrInt(elem, "computerX", pos.x)
            SaveUtil.addAttrInt(elem, "computerY", pos.y)
        }
        computerDirection?.let { dir -> SaveUtil.addAttr(elem, "computerDir", dir.toString()) }
    }

    companion object {
        fun loadFromXML(elem: Element): EditableSystem {
            val type = SaveUtil.getAttr(elem, "type")
            val startingPower = SaveUtil.getAttrInt(elem, "startingPower")
            val interiorImage = elem.getAttributeValue("interiorImage")
            val artilleryWeapon = elem.getAttributeValue("artilleryWeapon")

            val computerPoint = if (elem.getAttribute("computerX") == null) null else ConstPoint(
                SaveUtil.getAttrInt(elem, "computerX"),
                SaveUtil.getAttrInt(elem, "computerY")
            )
            val computerDirection = elem.getAttributeValue("computerDir")?.let { Direction.valueOf(it) }

            return EditableSystem(type, interiorImage, computerPoint, computerDirection, startingPower, artilleryWeapon)
        }
    }
}

/**
 * An instance of [EditableSystem] that's been frozen in a ship,
 * with a fixed system index, and anything else that's only known
 * when the ship layout will no longer change.
 */
class FinalisedEditableSystem(
    private val editableSystem: EditableSystem,
    override val systemIndex: Int,
    game: InGameState,
    room: Room
) : ISystemConfiguration {
    private val system = game.blueprintManager[editableSystem.type] as SystemBlueprint

    override val systemName: String get() = system.type

    override val aiMaxPower: Int? get() = null
    override val weapon: String? get() = editableSystem.artilleryWeapon
    override val interiorImage: String? get() = editableSystem.interiorImage

    override val slotNumber: Int? = editableSystem.computerPoint?.let { room.pointToSlot(it) }
    override val slotDirection: Direction? get() = editableSystem.computerDirection

    override val startingPower: Int get() = editableSystem.startingPower
    override val availableByDefault: Boolean get() = editableSystem.startingPower != 0
}

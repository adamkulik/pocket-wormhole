package xyz.znix.xftl.drones

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint

class RepairDrone(type: DroneBlueprint) : AbstractIndoorsDrone(type) {
    override val occupancySlotType get() = AbstractCrew.SlotType.CREW

    override val pawnCodename: String get() = "repair"

    override fun init(ownerShip: Ship) {
        super.init(ownerShip)
        val dronesRoom = ownerShip.rooms.find { it.system is Drones }
        requireNotNull(dronesRoom) { "Owner ship '${ownerShip.name}' spawned a ship repair drone without a drones system" }

        // Don't spawn in a pawn if that's already been done by deserialisation.
        if (pawn == null) {
            spawn(dronesRoom)
        }
    }

    override fun updatePawn(dt: Float) {
        // Check if we're idle and should scan for damaged systems.
        if (pawn!!.pathingTarget != null)
            return
        if (pawn!!.room.system?.damaged == true)
            return

        for (room in ship.rooms) {
            val system = room.system ?: continue
            if (!system.damaged)
                continue

            // Try to path to this room. If we can then end the loop,
            // otherwise continue to the next room.
            if (pawn!!.setTargetRoom(room))
                break
        }
    }

    override fun drawPawn() {
        // TODO the green glow over the drone when it's powered on - that comes from the layer1 image
    }

    override fun makePawn(room: Room): Pawn = RepairPawn(room)

    private inner class RepairPawn(room: Room) : AbstractIndoorsDrone.Pawn(room) {
        override val canRepair: Boolean get() = true
        override val repairSpeed: Float get() = 2f
    }
}

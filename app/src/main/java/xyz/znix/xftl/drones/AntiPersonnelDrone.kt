package xyz.znix.xftl.drones

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * The anti-personnel drone - vanilla's `BATTLE` drone blueprint, rendered as
 * the `battle` crew race (the same sprites as the boarding drone).
 *
 * It lives inside the player's ship and seeks out intruders (including
 * mind-controlled friendly crew, whose [AbstractCrew.mode] flips to
 * [AbstractCrew.SlotType.INTRUDER]) to attack them. When there is nothing to
 * fight it returns to the drone bay, where it heals while the Drone Control
 * system has power.
 *
 * Previously [DroneBlueprint.makeInstance] returned the do-nothing
 * `DummyDrone` for this type, so the drone neither appeared nor did anything.
 *
 * The stats are vanilla's: 150 hit points and the same damage bonus as a
 * boarding drone (1.2x), while moving at half a normal crew member's speed.
 *
 * @param type the `BATTLE` drone blueprint.
 */
class AntiPersonnelDrone(type: DroneBlueprint) : AbstractIndoorsDrone(type) {
    override val occupancySlotType get() = AbstractCrew.SlotType.CREW

    // It looks like anti-personnel and boarding drones use the same images.
    override val pawnCodename: String get() = "battle"

    private val droneRoom: Room?
        get() = ownerShip.rooms.firstOrNull { it.system is Drones }

    override fun init(ownerShip: Ship) {
        super.init(ownerShip)

        val dronesRoom = droneRoom
        requireNotNull(dronesRoom) {
            "Owner ship '${ownerShip.name}' spawned an anti-personnel drone without a drones system"
        }

        // Don't spawn in a pawn if that's already been done by deserialisation.
        if (pawn == null) {
            spawn(dronesRoom)
        }
    }

    override fun updatePawn(dt: Float) {
        val pawn = this.pawn!!

        // Already standing among intruders - AbstractCrew's fighting logic
        // takes over from here, so don't walk away.
        if (pawn.room.crew.any { it.mode == AbstractCrew.SlotType.INTRUDER }) {
            return
        }

        if (pawn.pathingTarget != null)
            return

        // Head for the nearest intruder. If the room is full, try the next
        // one - the drone will re-check everything again next update.
        val intruderRooms = intruderRoomsByDistance(ship, pawn.getPixelPositionCentre())
        if (intruderRooms.isNotEmpty()) {
            for (room in intruderRooms) {
                if (pawn.setTargetRoom(room))
                    return
            }
            return
        }

        // Nothing to fight: go home to the drone bay.
        val home = droneRoom ?: return
        if (pawn.room != home) {
            pawn.setTargetRoom(home)
        }
    }

    override fun drawPawn() {
        // TODO draw the green glow when turned on
    }

    override fun makePawn(room: Room): Pawn = BattlePawn(room)

    private inner class BattlePawn(room: Room) : AbstractIndoorsDrone.Pawn(room) {
        // The battle drone only shoots - it has no punch animations.
        override val canPunch: Boolean get() = false
        override val canFight: Boolean get() = true
        override val canRepair: Boolean get() = false

        override val maxHealth: Float get() = 150f

        // Vanilla's CrewDrone multiplier for BATTLE: the same 1.2x a
        // boarding drone gets.
        override val attackDamageMult: Float get() = 1.2f

        // Crew drones move at half speed.
        override val movementSpeed: Float get() = BASE_MOVEMENT_SPEED * 0.5f

        override fun update(dt: Float) {
            super.update(dt)

            // Crew drones heal in the drone bay, and only the Drone Control
            // system as a whole needs power for that - not this drone's slot
            // (wiki: Drone Control -> Crew Drones). Do this outside of
            // updatePawn so a depowered drone still heals while parked.
            if (health <= 0f || health >= maxHealth)
                return

            val currentRoom = room
            val drones = currentRoom.system as? Drones ?: return
            if (drones.powerSupplied <= 0)
                return

            health = (health + DRONE_BAY_HEAL_RATE * dt).coerceAtMost(maxHealth)
        }
    }

    companion object {
        /**
         * How quickly drones heal in the drone bay.
         *
         * The vanilla rate isn't pinned down; this is the level-1 medbay rate
         * (Medbay's `6.4f * speedForLevel(0)`), which is the game's standard
         * healing speed. Tune this if a vanilla measurement turns up.
         */
        const val DRONE_BAY_HEAL_RATE: Float = 6.4f

        /**
         * All rooms currently containing a living intruder, closest to
         * [from] first.
         *
         * Mind-controlled crew count as intruders because their
         * [AbstractCrew.mode] flips over.
         */
        internal fun intruderRoomsByDistance(ship: Ship, from: IPoint): List<Room> {
            return ship.rooms
                .filter { room ->
                    room.crew.any {
                        it.mode == AbstractCrew.SlotType.INTRUDER &&
                                it.currentAction != AbstractCrew.Action.DYING
                    }
                }
                .sortedBy { it.pixelCentre.distToSq(from) }
        }
    }
}

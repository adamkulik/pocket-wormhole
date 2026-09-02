package xyz.znix.xftl.ai

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.layout.Room

class IntruderCrewAI(private val ship: Ship) {
    private val aiCrew = ArrayList<AbstractCrew>()
    private var hasWorkingDoors: Boolean = false

    fun update() {
        // Update the list of AI-controllable crew
        aiCrew.clear()
        for (crew in ship.crew) {
            // TODO sort out the drone AI
            if (crew is AbstractIndoorsDrone.Pawn)
                continue

            if (crew.mode != AbstractCrew.SlotType.INTRUDER)
                continue

            if (crew.playerControllable)
                continue

            aiCrew += crew
        }

        if (aiCrew.isEmpty()) {
            return
        }

        // Check if we have any level of doors. If they're not completely
        // broken/ioned, then this prevents crew from moving freely.
        // This is probably a bug in vanilla, being intended to check for
        // blast doors only.
        // As with everything, see doc/crew-ai for more details (this is
        // related to PrioritizeIntruderRoom).
        val doors = ship.doorsSystem
        hasWorkingDoors = doors?.let { it.undamagedEnergy > 0 } ?: false

        // Pick the room each crew should attack, and assign that to the crew
        // and all subsequent crew. Thus the subsequent crew will still go to
        // a different room if that's better for them, but otherwise they'll
        // stick together.
        var lastCrewAssignment: Room? = null
        for (crew in aiCrew) {
            val currentlyAssigned = currentTarget(crew)

            // If the room the last crew was in is full, we can't carry across
            // their assignment - revert to this crew's actual current assignment.
            if (lastCrewAssignment?.anySlotsFree(AbstractCrew.SlotType.INTRUDER, crew) == false) {
                lastCrewAssignment = null
            }

            val assignment = pickNextRoom(crew, currentlyAssigned, lastCrewAssignment)
            lastCrewAssignment = assignment

            val target = crew.pathingTarget?.room

            // If we're either stationary in, or currently walking towards
            // the assigned room, we don't have to re-assign them.
            if (target == assignment)
                continue
            if (target == null && crew.room == assignment)
                continue

            crew.setTargetRoom(assignment)
        }
    }

    private fun currentTarget(crew: AbstractCrew): Room {
        return crew.pathingTarget?.room ?: crew.room
    }

    private fun pickNextRoom(crew: AbstractCrew, currentlyAssigned: Room, preferred: Room?): Room {
        // Note that preferred is the room the previous crewmember is assigned
        // to, so use that unless we have a good reason not to.
        val defaultTask = when {
            preferred == null -> currentlyAssigned
            roomPriority(crew, currentlyAssigned) < roomPriority(crew, preferred) -> currentlyAssigned
            else -> preferred
        }

        // For all the rooms, check their priorities and see which ones are
        // more important than the currently assigned tasks. If there's
        // multiple equally-most-important rooms, pick one at random.

        val currentPriority = roomPriority(crew, defaultTask)

        val mostImportant = ArrayList<Room>()
        var mostImportantPriority = 1000

        for (room in ship.rooms) {
            if (!room.anySlotsFree(AbstractCrew.SlotType.INTRUDER, crew))
                continue

            val priority = roomPriority(crew, room)

            // Ignore tasks less or equally important as the one
            // we have assigned now.
            if (priority >= currentPriority)
                continue

            // If this is the first more-important room we've found, select it.
            if (mostImportant.isEmpty()) {
                mostImportant += room
                mostImportantPriority = priority
                continue
            }

            // Otherwise, check if this room is more important than the
            // current best. If it's a tie, add it to the list too, as we'll
            // pick one at random at the end.
            if (mostImportantPriority < priority) {
                continue
            }

            if (priority < mostImportantPriority) {
                mostImportant.clear()
                mostImportantPriority = priority
            }

            mostImportant += room
        }

        if (mostImportant.isEmpty()) {
            // Nothing is more important than the current task
            return defaultTask
        }

        // If the tasks tie with the current task, then keep it to avoid
        // constantly switching tasks.
        if (mostImportantPriority >= currentPriority) {
            return defaultTask
        }

        // If there's one or more rooms that are more important than the
        // current one, then pick one at random.
        return mostImportant.random()
    }

    private fun roomPriority(crew: AbstractCrew, room: Room): Int {
        // See CrewAI::PrioritizeIntruderRoom in doc/crew-ai

        // TODO ion intruder drone handling

        val hasEnemies = room.crew.any { it.mode == AbstractCrew.SlotType.CREW }
        val numBoarders = room.crew.count { it.mode == AbstractCrew.SlotType.INTRUDER }

        // Note that hasWorkingDoors is true for level 1 doors, which
        // is likely a bug in vanilla.
        if (crew.canFight && room == crew.room && hasWorkingDoors && hasEnemies) {
            return -10
        }

        val hasFireDanger = crew.fireDamageMult > 0f && room.fires.any { it != null }
        val hasAirDanger = crew.suffocationMultiplier > 0f && room.oxygen < 0.1f

        var priority = 0

        if (hasFireDanger)
            priority += 100
        if (hasAirDanger)
            priority += 70

        // Add 5 points for completely full rooms, as this prevents the crew
        // from having a numerical advantage.
        // Note this only applies to the crew currently in the room, not the
        // crew pathing towards it.
        if (numBoarders != room.width * room.height) {
            priority += 5
        }

        // Prefer adjacent rooms
        if (crew.room != room && !crew.room.connectedTo(room)) {
            priority += 10
        }

        // Strongly prefer rooms with a system we can attack
        if (room.system?.broken != false) {
            priority += 20
        }

        return priority
    }
}

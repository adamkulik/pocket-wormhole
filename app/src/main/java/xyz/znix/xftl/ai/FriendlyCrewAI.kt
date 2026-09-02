package xyz.znix.xftl.ai

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.layout.FireInstance
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.*

/**
 * This AI controls the crew that are friendly to the ship.
 *
 * Friendly doesn't mean they're friendly to the player, this is used for
 * enemy crew aboard the enemy ship.
 *
 * Note this fairly closely matches the structure of vanilla's AI system,
 * as it's important that we match the subtle behaviour properly since edge
 * cases are important for tactics.
 */
class FriendlyCrewAI(private val ship: Ship) {
    // Set by ShipGenerator
    var initialCrewCount = 0

    // Don't teleport crew over more than 3 times
    private var teleportCount = 0

    private val aiCrew = ArrayList<AbstractCrew>()

    private var assignments = HashMap<AbstractCrew, AITask>()
    private var newAssignments = HashMap<AbstractCrew, AITask>()

    fun update() {
        // Update the list of AI-controllable crew
        aiCrew.clear()
        for (crew in ship.crew) {
            // TODO sort out the drone AI
            if (crew is AbstractIndoorsDrone.Pawn)
                continue

            if (crew.mode != AbstractCrew.SlotType.CREW)
                continue

            // Don't set tasks for dying crew. Vanilla has a separate task
            // for dying, so ignoring them does the same thing.
            if (crew.currentAction == AbstractCrew.Action.DYING)
                continue

            // We have to check playerControllable for mind-controlled crew.
            if (crew.playerControllable)
                continue

            // Ignore crew in the flagship's artillery rooms
            // We assume that any crew can path to any room, and these crew
            // violate that assumption, which can lead to tasks getting locked up.
            if (isRoomDisabled(crew.room))
                continue

            aiCrew += crew
        }

        // Stop here if there's no crew to control, to save a tiny bit of CPU.
        if (aiCrew.isEmpty())
            return

        // Build a new list of tasks each update, rather than having one persistent list.
        // This means that an arbitrary number of crew can be assigned to the same task,
        // if you can find a way to get them into that state.
        // See doc/crew-ai for more information.

        val tasks = ArrayList<AITask>()

        for (room in ship.rooms) {
            if (isRoomDisabled(room))
                continue

            val sys = room.system
            if (sys != null) {
                // Create manning tasks for all systems, even those where crew won't
                // do anything - those just have very low priorities, and serve
                // to disperse thee crew throughout the ship.
                tasks += ManningTask(this, room)

                if (sys.damaged) {
                    tasks += RepairTask(this, room)
                }
            }

            if (room.fires.any { it != null } && room.oxygen > FireInstance.OXYGEN_CUTOFF) {
                tasks += ExtinguishFireTask(this, room)
                tasks += ExtinguishFireTask(this, room)
            }

            if (room.breaches.any { it != null }) {
                tasks += RepairBreachTask(this, room)
            }

            // One task for each intruder
            for (crew in room.crew) {
                if (crew.mode != AbstractCrew.SlotType.INTRUDER)
                    continue

                tasks += CombatTask(this, room)
            }
        }

        // Add tasks for teleporting, but don't teleport mind-controlled enemy
        // crew out in an attempt to board the enemy ship.
        val teleporter = ship.teleporter
        var teleportSend = false
        var teleportForceRecv = false
        if (teleporter != null && !ship.isPlayerShip && ship.sys.getEnemyOf(ship)?.superShield == 0) {
            // Don't create more tasks than the teleporter can fit.
            var toSend = getNumMoreBoarders().coerceAtMost(teleporter.room!!.cellCount)

            // Don't teleport crew over more than twice
            if (teleportCount > 2 && toSend > 0 && !ship.sys.debugFlags.noTeleportLimit.set) {
                toSend = 0
            }

            for (i in 1..toSend) {
                tasks += TeleportingTask(this, teleporter.room!!)
            }
            when {
                toSend < 0 -> teleportForceRecv = true
                toSend > 0 -> teleportSend = true
            }

            val escapeTimer = ship.escapeTimer
            if (escapeTimer != null && escapeTimer < 15f) {
                teleportForceRecv = true
            }

            if (ship.systems.count { it.broken } >= 3) {
                teleportForceRecv = true
            }
        }

        // Figure out if the ship is calm, used for healing crew
        // at nearly-full health.
        val isShipCalm = tasks.none {
            when (it) {
                is CombatTask -> true
                is RepairTask -> true
                is RepairBreachTask -> true
                is ExtinguishFireTask -> true
                else -> false
            }
        }

        // Remove the tasks currently being performed, so we don't
        // assign the same task to multiple crew (unless multiple
        // of it are added to the tasks list).
        // At the same time, this removes any missing crew from
        // the assignments map.
        for (crew in aiCrew) {
            var task = assignments[crew] ?: continue

            // Tasks can automatically reassign their crew, usually back to
            // idle. This matches the CrewAI::UpdateCrewMember logic.
            task = task.nextTask(crew) ?: continue

            // If we're low on health, make our way to the medbay.
            val healingThreshold = if (isShipCalm) 0.99f else 0.25f
            val medbay = ship.medbay
            val medbayDangerous = medbay?.let { AIUtils.isDangerous(crew, medbay.room!!) }
            if (
                task !is HealingTask &&
                crew is LivingCrew &&
                medbay != null &&
                crew.health / crew.maxHealth < healingThreshold &&
                medbay.powerSelected > 0 &&
                medbayDangerous == false
            ) {
                // Try to start pathfinding there immediately, and if there's
                // no space, keep our current task so we can continue
                // doing something useful while we wait for whoever is in
                // there now to heal up and leave.
                val setPath = crew.setTargetRoom(medbay.room!!)

                if (setPath) {
                    task = HealingTask(this, medbay.room!!)
                }
            }

            tasks.remove(task)
            newAssignments[crew] = task
        }

        // Re-use the previous assignments hashmap, being paranoid about the GC.
        val oldAssignments = assignments
        assignments = newAssignments
        oldAssignments.clear()
        newAssignments = oldAssignments

        // Assign each task, if possible.
        for (task in tasks) {
            assignTask(task)
        }

        // Make sure all the crew are pathfinding to their target room.
        for ((crew, task) in assignments) {
            if (crew.room == task.room)
                continue

            if (crew.pathingTarget?.room == task.room)
                continue

            crew.setTargetRoom(task.room)
        }

        // If our boarders on the enemy ship are low on health, teleport them back.
        // Do this first, so receiving low-health crew takes priority over
        // sending more when the teleporter comes off cooldown.
        doTeleportRecv(teleportForceRecv)

        // If the crew are ready, activate the teleporter.
        if (teleportSend) {
            doTeleportSend()
        }
    }

    /**
     * Returns true if a given crewmember will be assigned to a manning task
     * in the next update. This is used to detect if two crew are both manning
     * a system.
     *
     * Since it's called while [newAssignments] is being written, it needs to
     * use that to check, otherwise all crew would have their manning tasks
     * cancelled.
     */
    fun hasAlreadyAssignedManning(crew: AbstractCrew): Boolean {
        return newAssignments[crew] is ManningTask
    }

    private fun assignTask(task: AITask) {
        // Matches up to CrewAI::AttemptToAssign in FTL.
        // I really don't like copying the structure of FTL's executable, but
        // it's hard not to if we're going to get the exact same behaviour.

        // Find the most suitable crewmember for this task.
        // This is based on the priority of the crewmember's current task,
        // so we pick the crewmember who is currently doing the least
        // important task.
        // This also accounts for the danger a crewmember faces in a room.
        var bestCrew: AbstractCrew? = null
        var bestCrewPriority = -1

        for (crew in aiCrew) {
            // Exclude crew that can't do a task
            if (!task.isSuitable(crew))
                continue

            val currentTask = assignments[crew]

            // If a crewmember is already performing this task, don't
            // re-assign them. This lets multiple crew perform identical
            // tasks, for example fighting fires has two tasks.
            val currentPriority: Int
            if (currentTask != null) {
                currentPriority = currentTask.priorityFor(crew)
            } else if (AIUtils.isDangerous(crew, crew.room)) {
                // The room danger does affect idle crew
                currentPriority = 100
            } else {
                currentPriority = 1000
            }

            val newPriority = task.priorityFor(crew)

            // Skip crew who are doing something more important.
            // Don't reject crew with an equal priority here, as that'd
            // change the instant assignment stuff.
            if (currentPriority < newPriority)
                continue

            // TODO instant assignment

            // At this point, ignore crew whose current task is the same
            // priority as the new task, to avoid crew ping-ponging between
            // equally important tasks.
            if (currentPriority == newPriority)
                continue

            // Keep track of the most suitable crew, picking the first
            // one if there's a tie.
            if (currentPriority > bestCrewPriority) {
                bestCrewPriority = currentPriority
                bestCrew = crew
            }
        }

        // Couldn't find a suitable crewmember?
        if (bestCrew == null)
            return

        // Assign this task to the chosen crew.
        assignments[bestCrew] = task
    }

    private fun isRoomDisabled(room: Room): Boolean {
        // Don't set any tasks in inaccessible rooms, since crew can't go there.
        // This prevents flagship crew from getting stuck when sent to the artillery rooms.
        return room.doors.isEmpty()
    }

    private fun getNumMoreBoarders(): Int {
        // See doc/ship-ai for information about this.

        // Find the number of boarders to send, from our initial crew.
        val sendTotal = when (ship.type.boardingStrategy) {
            BoardingStrategy.NONE -> return 0
            BoardingStrategy.SABOTAGE -> initialCrewCount / 2
            BoardingStrategy.INVASION -> initialCrewCount * 3 / 4
        }

        // Use this to figure out how many crew to keep behind.
        val toKeep = initialCrewCount - sendTotal

        // Now use this to calculate how many crew we can safely send.
        // This can be negative if we should recall some boarders, due to
        // crew on our ship dying.
        return aiCrew.size - toKeep
    }

    private fun doTeleportSend() {
        val teleporter = ship.teleporter!!
        val enemy = ship.sys.getEnemyOf(ship) ?: return

        // Check that there aren't any crew still pathing to the teleporter
        for (crew in aiCrew) {
            // Stop if any crew are currently being teleported
            if (crew.currentAction == AbstractCrew.Action.TELEPORTING)
                return

            if (assignments[crew] !is TeleportingTask)
                continue

            // Don't teleport until all the crew are ready
            if (crew.standingPosition?.room != teleporter.room)
                return
        }

        teleporter.selectTeleportAction(true, enemy.rooms.random())
    }

    private fun doTeleportRecv(teleportForceRecv: Boolean) {
        // See doc/ship-ai for this logic

        val teleporter = ship.teleporter ?: return

        val enemy = ship.sys.getEnemyOf(ship) ?: return
        val boarders = enemy.crew.filterIsInstance<LivingCrew>().filter { it.mode == AbstractCrew.SlotType.INTRUDER }

        val minHealthFraction = when (ship.type.boardingStrategy) {
            BoardingStrategy.INVASION -> -1f // Never teleport back
            else -> 0.25f
        }

        for (crew in boarders) {
            val healthFraction = crew.health / crew.maxHealth

            if (healthFraction > minHealthFraction && !teleportForceRecv)
                continue

            // Teleport the crew from this room
            teleporter.selectTeleportAction(false, crew.room)
            return
        }
    }

    fun onCrewTeleportedOut() {
        // Only increment teleportCount when someone has actually been teleported.
        // If we did this before calling selectTeleportAction, anything that blocked
        // teleporting (eg cloaking, super shield) would increment this every frame
        // until we hit the limit.
        teleportCount++
    }

    // TODO serialisation

    enum class BoardingStrategy {
        NONE,
        SABOTAGE, // Most ships
        INVASION, // Flagship
        ;
    }
}

abstract class AITask(val ai: FriendlyCrewAI, val room: Room) {
    /**
     * Find this task's priority.
     */
    fun priorityFor(crew: AbstractCrew): Int {
        if (AIUtils.isDangerous(crew, room))
            return 100

        return priorityWithoutDanger(crew)
    }

    // Doesn't include the danger check
    protected abstract fun priorityWithoutDanger(crew: AbstractCrew): Int

    open fun nextTask(crew: AbstractCrew): AITask? {
        if (crew.currentAction.isIdle) {
            return null
        }

        return this
    }

    // Have one equals/hashCode for all our subclasses

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AITask) return false
        if (other.javaClass != javaClass) return false

        return room == other.room
    }

    override fun hashCode(): Int {
        return room.hashCode() xor javaClass.hashCode()
    }

    abstract fun isSuitable(crew: AbstractCrew): Boolean

    override fun toString(): String {
        return "${javaClass.simpleName}(id=${room.id},sys=${room.system?.codename})"
    }
}

class RepairTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        // Oxygen gets a very high priority when it's low
        if (room.system is Oxygen && room.ship.averageOxygen < 0.25f)
            return 0

        // Shields is special
        if (room.system is Shields)
            return 1

        return AIUtils.systemPriority(room.system!!) + 48
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew.canRepair
    }
}

class RepairBreachTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        return AIUtils.systemPriority(room.system) + 32
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew.canRepair
    }
}

class ExtinguishFireTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        return AIUtils.systemPriority(room.system) + 16
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew.canRepair
    }
}

class ManningTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        val isHard = room.ship.sys.difficulty == Difficulty.HARD

        // See doc/crew-ai
        val systemPriority = when (room.system) {
            is Piloting -> 64
            is Shields -> if (isHard) 67 else 65
            is Engines -> if (isHard) 65 else 67
            is Weapons, is Artillery -> 66
            is Oxygen -> 68
            is Sensors -> 69
            is Doors, is Cloaking -> 70
            is Teleporter -> 71
            is Medbay -> 72
            else -> 1000
        }

        return systemPriority
    }

    override fun nextTask(crew: AbstractCrew): AITask? {
        if (!crew.currentAction.isIdle)
            return this

        // Check if any other crew in the room are assigned to manning.
        for (otherCrew in room.crew) {
            if (ai.hasAlreadyAssignedManning(otherCrew)) {
                return null
            }
        }

        return this
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew.canRepair
    }
}

class CombatTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        // TODO bad at combat penalty
        return AIUtils.systemPriority(room.system) + 16
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew.canFight
    }
}

class HealingTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        return 0
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew is LivingCrew
    }

    override fun nextTask(crew: AbstractCrew): AITask? {
        val medbay = room.system as Medbay

        if (medbay.isHackActive || crew.health == crew.maxHealth || medbay.powerSelected == 0)
            return null

        return this
    }
}

class TeleportingTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        // TODO bad at combat penalty
        // There's an urgent teleporting flag in vanilla which forces
        // a priority of 0, but it seems to never be set.
        return when {
            crew.health / crew.maxHealth < 0.5f -> 100
            room.ship.teleporter!!.ionTimer > 0f -> 65
            else -> 32
        }
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew is LivingCrew && crew.canFight
    }
}

package xyz.znix.xftl.layout

import xyz.znix.xftl.Ship
import xyz.znix.xftl.f
import xyz.znix.xftl.game.LoopHandle
import kotlin.math.min
import kotlin.math.pow

/**
 * This moves oxygen between rooms, matching FTL's fairly complex transfer
 * and leak (for breaches and airlock doors) system.
 *
 * See doc/oxygen for the leak and spread algorithms.
 */
object OxygenTransfer {
    fun update(ship: Ship, dt: Float) {
        // Drain oxygen from open doors and breaches
        for (room in ship.rooms) {
            var drainRate = 0f

            drainRate += 0.16f * room.doors.count { it.isAirlock && it.open }.f

            drainRate += 0.08f * room.breaches.count { it != null }

            // Check if there's any drain other than from anaerobic crew, in
            // which case we'll play the sound leaking noise if appropriate.
            val enableSound = drainRate > 0f

            // Apply anaerobic crew
            for (crew in room.crew) {
                drainRate += crew.anaerobicOxygenDrainRate
            }

            if (drainRate == 0f)
                continue

            var hasAirLoss = false

            for ((otherRoom, distance) in findDistances(room)) {
                val modifier = 0.75f.pow(distance)
                val finalRate = drainRate * modifier
                otherRoom.oxygen -= dt * finalRate

                // Mute the sound effect if the rooms are fully drained.
                if (otherRoom.oxygen > 0.01f) {
                    hasAirLoss = true
                }
            }

            if (hasAirLoss && enableSound) {
                val sound: LoopHandle = ship.sys.sounds.getLoop("airLeak")
                sound.continueLoopPlayerOnly(ship)
            }
        }

        spreadOxygen(ship, dt)
    }

    private fun spreadOxygen(ship: Ship, dt: Float) {
        val remainingRooms = HashSet<Room>()
        remainingRooms.addAll(ship.rooms)

        while (remainingRooms.isNotEmpty()) {
            // Find a cluster of connected rooms
            val startingRoom = remainingRooms.first()
            val cluster = findDistances(startingRoom).keys
            remainingRooms.removeAll(cluster)

            val averageOxygen = cluster.map { it.oxygen }.average().toFloat()

            for (room in cluster) {
                val delta = room.oxygen - averageOxygen

                // Spread oxygen between rooms in the cluster, at 8% of
                // the difference per second.
                room.oxygen -= delta * 0.08f * dt
            }
        }
    }

    private fun findDistances(room: Room): Map<Room, Int> {
        val result = HashMap<Room, Int>()
        result[room] = 0

        var active = HashSet<Room>()
        var next = HashSet<Room>()

        // Add the neighbours of the root room
        active.addAll(openDoorNeighbours(room))

        while (active.isNotEmpty()) {
            for (iterRoom in active) {
                val current = result[iterRoom] ?: 999
                val neighbours = openDoorNeighbours(iterRoom)
                val lowestNeighbour = neighbours
                    .mapNotNull { result[it] }
                    .minOrNull()
                    ?: 999
                val newDist = min(current, lowestNeighbour + 1)

                if (current == newDist) {
                    continue
                }

                result[iterRoom] = newDist

                next.addAll(neighbours)
            }

            val newNext = active
            newNext.clear()
            active = next
            next = newNext
        }

        return result
    }

    // Note this may contain duplicates
    private fun openDoorNeighbours(room: Room): List<Room> {
        return room.doors
            .filter { it.open }
            .mapNotNull { it.other(room) }
    }
}

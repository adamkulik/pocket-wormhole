package xyz.znix.xftl.layout

import xyz.znix.xftl.Ship
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.RoomPoint

class PathFinder(val ship: Ship) {
    private val mutableNodes: MutableMap<RoomPoint, Node> = HashMap()
    val nodes: Map<RoomPoint, Node> = mutableNodes

    init {
        recalculateWeights()
    }

    fun recalculateWeights() {
        val tmpRooms = ArrayList<Node>(4)

        // TODO if perf becomes an issue, clear all the neighbours for each node and only redo those
        mutableNodes.clear()

        for (room in ship.rooms) {
            tmpRooms.clear()
            for (x in 0 until room.width) {
                for (y in 0 until room.height) {
                    val point = RoomPoint(room, x, y)
                    val node = Node(point)
                    check(room.containsRelative(point))
                    mutableNodes[point] = node
                    tmpRooms += node
                }
            }

            for (n1 in tmpRooms) {
                for (n2 in tmpRooms) {
                    if (n1 == n2)
                        continue

                    val diagonal = n1.pos.x != n2.pos.x && n1.pos.y != n2.pos.y

                    // Slightly prefer going straight, so we don't needlessly go diagonally
                    n1.neighbours[n2] = if (diagonal) 1.01f else 1f
                }
            }
        }

        // Add in the doors
        for (door in ship.doors) {
            val n1 = nodes[door.leftPos]
            val n2 = nodes[door.rightPos]

            if (n1 == null || n2 == null)
                continue

            // Prefer to avoid doors
            val weight = 1.2f
            n1.neighbours[n2] = weight
            n2.neighbours[n1] = weight
        }
    }

    fun findShipPos(point: IPoint): Node? {
        for (pair in nodes) {
            if (pair.key.shipPoint posEq point)
                return pair.value
        }

        return null
    }

    fun path(start: RoomPoint) {
        check(ship == start.room.ship)

        for (room in nodes)
            room.value.reset()

        // If the start node is not blocked, seed it
        nodes[start]?.weight = 0f

        val dirty = ArrayList<Node>(nodes.size)
        val dirtyTmp = ArrayList<Node>(dirty.size)
        dirty += nodes.values
        while (dirty.isNotEmpty()) {
            dirtyTmp.clear()
            dirtyTmp += dirty
            dirty.clear()
            for (room in dirtyTmp) {
                val changed = room.recalculate()
                if (changed)
                    dirty += room.neighbours.keys
            }
        }

        val shipPoints = HashMap<ConstPoint, Node>()
        for (room in nodes)
            shipPoints[room.key.shipPoint] = room.value
    }

    class Node(val pos: RoomPoint) {
        companion object {
            // High value we will never reach
            // Don't use MAX_VALUE as it goes weird if we do something with it
            const val MAX_WEIGHT = 10_000f
        }

        var weight: Float = MAX_WEIGHT
        var next: Node? = null

        val neighbours: MutableMap<Node, Float> = HashMap(pos.room.width * pos.room.height - 1)

        fun recalculate(): Boolean {
            var changed = false

            assert(!neighbours.containsKey(this))

            for (pair in neighbours) {
                val nWeight = pair.key.weight + pair.value
                if (nWeight < weight) {
                    weight = nWeight
                    next = pair.key
                    changed = true
                }
            }

            return changed
        }

        fun reset() {
            weight = MAX_WEIGHT
            next = null
        }
    }
}
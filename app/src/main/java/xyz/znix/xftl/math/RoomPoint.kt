package xyz.znix.xftl.math

import xyz.znix.xftl.Constants
import xyz.znix.xftl.layout.Room

class RoomPoint(val room: Room, override val x: Int, override val y: Int) : IPoint {
    constructor(room: Room, point: IPoint) : this(room, point.x, point.y)

    val shipX: Int get() = room.x + x
    val shipY: Int get() = room.y + y
    val shipPoint: ConstPoint
        get() {
            var cache = shipPointCache
            if (cache != null)
                return cache
            cache = ConstPoint(shipX, shipY)
            shipPointCache = cache
            return cache
        }

    val offsetX: Int get() = room.offsetX + x * Constants.ROOM_SIZE
    val offsetY: Int get() = room.offsetY + y * Constants.ROOM_SIZE

    private var shipPointCache: ConstPoint? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RoomPoint

        if (room != other.room) return false
        if (x != other.x) return false
        if (y != other.y) return false

        return true
    }

    override fun hashCode(): Int {
        var result = room.hashCode()
        result = 31 * result + x
        result = 31 * result + y
        return result
    }

    override fun toString(): String {
        return "RoomPoint(room=$room, x=$x, y=$y)"
    }
}

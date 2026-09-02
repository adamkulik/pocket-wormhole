package xyz.znix.xftl.math

data class Point(override var x: Int, override var y: Int) : IPoint {
    constructor(point: IPoint) : this(point.x, point.y)

    override fun equals(other: Any?): Boolean {
        if (other !is IPoint)
            return false

        return other.x == x && other.y == y
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        return result
    }

    override fun toString(): String {
        return "Point(x=$x, y=$y)"
    }

    operator fun plusAssign(other: IPoint) {
        x += other.x
        y += other.y
    }

    operator fun minusAssign(other: IPoint) {
        x -= other.x
        y -= other.y
    }

    fun add(x: Int, y: Int) {
        this.x += x
        this.y += y
    }

    fun sub(x: Int, y: Int) = add(-x, -y)

    fun mult(v: Int) {
        x *= v
        y *= v
    }

    operator fun timesAssign(v: Int) = mult(v)

    fun divide(v: Int) {
        x /= v
        y /= v
    }

    fun divideFloor(v: Int) {
        x = Math.floorDiv(x, v)
        y = Math.floorDiv(y, v)
    }

    fun set(other: IPoint) = set(other.x, other.y)

    fun set(x: Int, y: Int) {
        this.x = x
        this.y = y
    }
}
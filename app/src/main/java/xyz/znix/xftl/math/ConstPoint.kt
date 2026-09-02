package xyz.znix.xftl.math

class ConstPoint(override val x: Int, override val y: Int) : IPoint {
    constructor(p: IPoint) : this(p.x, p.y)

    companion object {
        @JvmField
        val ZERO = ConstPoint(0, 0)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is IPoint)
            return false

        return other.x == x && other.y == y
    }

    override fun toString(): String {
        return "ConstPoint(x=$x, y=$y)"
    }

    override fun hashCode(): Int {
        var result = x
        result = 31 * result + y
        return result
    }

    override val const: ConstPoint get() = this
}

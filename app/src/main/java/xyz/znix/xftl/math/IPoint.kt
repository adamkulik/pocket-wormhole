package xyz.znix.xftl.math

import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.math.sqrt

interface IPoint {
    val x: Int
    val y: Int

    val isVertical: Boolean get() = x == 0 && y != 0
    val isHorizontal: Boolean get() = x != 0 && y == 0
    val isZero: Boolean get() = x == 0 && y == 0
    val isDiagonal: Boolean get() = x != 0 && y != 0
    val isCardinal: Boolean get() = !isZero && (x == 0 || y == 0)

    val const: ConstPoint get() = ConstPoint(this)
    val fConst: ConstFPoint get() = ConstFPoint(this)

    operator fun plus(other: IPoint): ConstPoint {
        return ConstPoint(x + other.x, y + other.y)
    }

    operator fun minus(other: IPoint): ConstPoint {
        return ConstPoint(x - other.x, y - other.y)
    }

    operator fun unaryMinus(): ConstPoint {
        return ConstPoint(-x, -y)
    }

    operator fun times(other: IPoint): ConstPoint {
        return ConstPoint(x * other.x, y * other.y)
    }

    operator fun times(other: Int): ConstPoint {
        return ConstPoint(x * other, y * other)
    }

    /**
     * Return a point where the X and Y are the minimum value between this and the [other] point.
     */
    fun min(other: IPoint): ConstPoint {
        return ConstPoint(kotlin.math.min(x, other.x), kotlin.math.min(y, other.y))
    }

    /**
     * Return this point, but with it's X and Y coordinates each with their sign inverted if they are negative.
     */
    fun abs(): ConstPoint {
        return ConstPoint(x.absoluteValue, y.absoluteValue)
    }

    /**
     * Compare two points, matching if they have the same X or Y, regardless of their type
     */
    infix fun posEq(other: IPoint): Boolean {
        return x == other.x && y == other.y
    }

    fun posEq(otherX: Int, otherY: Int): Boolean {
        return x == otherX && y == otherY
    }

    fun distToSq(other: IPoint): Int {
        val dx = other.x - x
        val dy = other.y - y
        return dx * dx + dy * dy
    }

    fun distToSq(otherX: Int, otherY: Int): Int {
        val dx = otherX - x
        val dy = otherY - y
        return dx * dx + dy * dy
    }

    fun distTo(other: IPoint): Int {
        return sqrt(distToSq(other).toFloat()).roundToInt()
    }

    fun distTo(otherX: Int, otherY: Int): Int {
        return sqrt(distToSq(otherX, otherY).toFloat()).roundToInt()
    }

    fun divideTruncate(n: Float): IPoint {
        return ConstPoint((x / n).toInt(), (y / n).toInt())
    }

    fun divideTruncate(other: IPoint): IPoint {
        return ConstPoint(x / other.x, y / other.y)
    }

    /**
     * Returns true if this position is contained within the rectangle
     * denoted by a given position/size pair.
     */
    fun containedInBox(pos: IPoint, size: IPoint): Boolean {
        return pos.x <= x && pos.y <= y && x < pos.x + size.x && y < pos.y + size.y
    }
}

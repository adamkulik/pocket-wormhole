package xyz.znix.xftl.math

import kotlin.math.sqrt

/**
 * A floating-point version of [IPoint].
 */
interface FPoint : IPoint {
    val xf: Float
    val yf: Float

    override val x: Int get() = xf.toInt()
    override val y: Int get() = yf.toInt()

    val fLengthSq: Float get() = xf * xf + yf * yf
    val fLength: Float get() = sqrt(fLengthSq)

    operator fun plus(other: FPoint): ConstFPoint {
        return ConstFPoint(xf + other.xf, yf + other.yf)
    }

    operator fun minus(other: FPoint): ConstFPoint {
        return ConstFPoint(xf - other.xf, yf - other.yf)
    }

    fun dot(other: IPoint): Float {
        return xf * other.x + yf * other.y
    }

    fun dot(other: FPoint): Float {
        return xf * other.xf + yf * other.yf
    }
}

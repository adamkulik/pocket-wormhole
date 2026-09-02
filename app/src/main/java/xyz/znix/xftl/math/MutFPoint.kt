package xyz.znix.xftl.math

import xyz.znix.xftl.f

class MutFPoint(
    override var xf: Float,
    override var yf: Float
) : FPoint {
    constructor(other: IPoint) : this(other.x.f, other.y.f)
    constructor(other: FPoint) : this(other.xf, other.yf)

    fun set(other: IPoint) {
        xf = other.x.f
        yf = other.y.f
    }

    fun set(other: FPoint) {
        xf = other.xf
        yf = other.yf
    }

    fun set(newX: Float, newY: Float) {
        xf = newX
        yf = newY
    }

    operator fun timesAssign(value: Float) {
        xf *= value
        yf *= value
    }
}

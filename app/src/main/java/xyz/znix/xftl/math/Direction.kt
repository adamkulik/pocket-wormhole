package xyz.znix.xftl.math

import xyz.znix.xftl.f
import kotlin.math.PI
import kotlin.math.atan2

enum class Direction(override val x: Int, override val y: Int, val angle: Int) : IPoint {
    UP(0, -1, 0),
    UP_RIGHT(1, -1, 45),
    RIGHT(1, 0, 90),
    RIGHT_DOWN(1, 1, 135),
    DOWN(0, 1, 180),
    DOWN_LEFT(-1, 1, 225),
    LEFT(-1, 0, 270),
    LEFT_UP(-1, -1, 315);

    val opposite: Direction
        get() = when (this) {
            UP -> DOWN
            UP_RIGHT -> DOWN_LEFT
            RIGHT -> LEFT
            RIGHT_DOWN -> LEFT_UP
            DOWN -> UP
            DOWN_LEFT -> UP_RIGHT
            LEFT -> RIGHT
            LEFT_UP -> RIGHT_DOWN
        }

    companion object {
        val CARDINALS = listOf(UP, DOWN, LEFT, RIGHT)
        val DIAGONALS = listOf(UP_RIGHT, DOWN_LEFT, RIGHT_DOWN, LEFT_UP)

        fun fromPoint(point: IPoint): Direction? {
            if (point is Direction)
                return point

            for (p in values()) {
                if (p posEq point)
                    return p
            }

            return null
        }

        /**
         * Finds the closest direction to an angle in degrees (where zero is up).
         */
        fun fromAngle(angle: Int): Direction {
            // If we divide the angle by 45 degrees, we get the direction
            // where each direction covers its angle until the next one.
            // Since we want to find the closest angle (eg, 44 degrees
            // should be UP_RIGHT rather than UP) we have to add 45/2=22.
            // Do this before taking the modulo, so values between 315 and zero
            // wrap around correctly.
            var moduloAngle = (angle + 22).rem(360)

            // Unfortunately, the rem function leaves negative numbers in.
            if (moduloAngle < 0)
                moduloAngle += 360

            val id = moduloAngle / 45
            return values()[id]
        }

        /**
         * Find the direction closest to that of an arbitrary vector.
         *
         * Returns UP for the input 0,0.
         */
        fun bestFit(deltaX: Float, deltaY: Float): Direction {
            // Find the angle in radians. Note we flip the x,y around (atan2
            // takes the arguments in the y,x order since it effectively performs y/x).
            // We need an angle of zero to point upwards, rather than right, and then
            // positive should be clockwise.
            val angle = atan2(deltaX, -deltaY) / PI * 180

            return fromAngle(angle.toInt())
        }

        /**
         * Finds the direction closest to the vector running from [from] towards [to].
         */
        fun bestFit(from: IPoint, to: IPoint): Direction {
            return bestFit(to.x.f - from.x, to.y.f - from.y.f)
        }
    }
}

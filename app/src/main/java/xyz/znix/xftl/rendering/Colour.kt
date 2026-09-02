package xyz.znix.xftl.rendering

// This is largely copied from Slick's colour class, under the BSD licence.
class Colour(var r: Float, var g: Float, var b: Float, var a: Float) {
    constructor(r: Float, g: Float, b: Float) : this(r, g, b, 1f)

    constructor(red: Int, green: Int, blue: Int) : this(red, green, blue, 255)
    constructor(red: Int, green: Int, blue: Int, alpha: Int) : this(red / 255f, green / 255f, blue / 255f, alpha / 255f)

    constructor(other: Colour) : this(other.r, other.g, other.b, other.a)

    @Suppress("unused")
    companion object {
        fun lerp(colour1: Colour, colour2: Colour, fraction: Float): Colour {
            return Colour(
                r = colour1.r.toFloat() * (1 - fraction) + colour2.r.toFloat() * fraction,
                g = colour1.g.toFloat() * (1 - fraction) + colour2.g.toFloat() * fraction,
                b = colour1.b.toFloat() * (1 - fraction) + colour2.b.toFloat() * fraction,
                a = colour1.a.toFloat() * (1 - fraction) + colour2.a.toFloat() * fraction
            )
        }

        @JvmField
        val transparent = Colour(0.0f, 0.0f, 0.0f, 0.0f)

        @JvmField
        val white = Colour(1f, 1f, 1f, 1f)

        @JvmField
        val yellow: Colour = Colour(1f, 1f, 0f, 1f)

        @JvmField
        val red: Colour = Colour(1f, 0f, 0f, 1f)

        @JvmField
        val blue: Colour = Colour(0f, 0f, 1f, 1f)

        @JvmField
        val green: Colour = Colour(0f, 1f, 0f, 1f)

        @JvmField
        val black: Colour = Colour(0f, 0f, 0f, 1f)

        @JvmField
        val gray = Colour(0.5f, 0.5f, 0.5f, 1f)

        @JvmField
        val cyan: Colour = Colour(0f, 1f, 1f, 1f)

        @JvmField
        val darkGray = Colour(0.3f, 0.3f, 0.3f, 1f)

        @JvmField
        val lightGray = Colour(0.7f, 0.7f, 0.7f, 1f)

        @JvmField
        val pink = Colour(255, 175, 175, 255)

        @JvmField
        val orange = Colour(255, 200, 0, 255)

        @JvmField
        val magenta = Colour(255, 0, 255, 255)
    }
}

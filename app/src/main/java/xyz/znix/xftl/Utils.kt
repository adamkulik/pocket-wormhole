package xyz.znix.xftl

import org.jdom2.Element
import org.lwjgl.opengl.GL11
import xyz.znix.xftl.game.UIUtils
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.sys.Game
import kotlin.math.PI
import kotlin.random.Random

const val PIf = PI.toFloat()
const val TWO_PI = 2 * PIf

/**
 * A [Random] instance for use in purely visual stuff.
 *
 * This makes it easier to find un-seeded random usages, in the effort
 * to properly seed everything.
 *
 * This must thus never be used for anything more than visuals.
 */
val VisualRandom: Random = Random.Default

// Make <int>.f a shorthand for <int>.toFloat(), cleaning things up a lot
val Int.f get() = toFloat()

/**
 * The square of a given float.
 */
val Float.sq get() = this * this

fun Element.requireAttributeValue(name: String): String {
    return getAttributeValue(name) ?: error("Missing mandatory attribute $name on element ${this.name}")
}

fun Element.requireAttributeValueInt(name: String): Int {
    return requireAttributeValue(name).toIntOrNull() ?: error("Could not parse attribute $name as int on ${this.name}")
}

fun Element.mapChildrenText(childName: String): List<String> {
    return children.map {
        check(it.name == childName) { "Mapping child nodes to text, found unknown child ${it.name}" }
        check(it.attributes.isEmpty()) { "Mapping child nodes to text, child ${it.name} contains attributes" }
        it.textTrim
    }
}

fun Float.lerp(other: Float, proportion: Float): Float {
    val diff = other - this
    return this + diff * proportion.coerceAtLeast(0f).coerceAtMost(1f)
}

fun Random.rollChance(percentChance: Int): Boolean {
    return nextInt(100) < percentChance
}

/**
 * Interpolate a colour between [this] and [other]. When [proportion] is 0 it's entirely [this], 1 is entirely [other].
 */
fun Colour.lerp(other: Colour, proportion: Float): Colour {
    if (proportion <= 0f) return this
    if (proportion >= 1f) return other
    return Colour(
        r.lerp(other.r, proportion),
        g.lerp(other.g, proportion),
        b.lerp(other.b, proportion),
        a.lerp(other.a, proportion)
    )
}

fun ClosedFloatingPointRange<Float>.random(rand: Random): Float {
    return start + rand.nextFloat() * (endInclusive - start)
}

fun String.replaceArg(value: Float, index: Int = 1): String {
    return replace("\\$index", UIUtils.formatFloat(value))
}

fun String.replaceArg(value: Int, index: Int = 1): String {
    return replace("\\$index", value.toString())
}

fun String.replaceArg(value: String, index: Int = 1): String {
    return replace("\\$index", value)
}

fun <E> MutableList<E>.pop(): E {
    return removeAt(size - 1)
}

object Utils {
    /**
     * Draw something with stenciling.
     *
     * You draw something in the [stencil] function which is then used to control
     * the rendering of [drawing].
     *
     * If [mode] is [StencilMode.BLOCKING] then any pixels drawn (transparent or not!) in the
     * [stencil] function will prevent pixels from being drawn at the same location. If
     * [mode] is [StencilMode.MASKING] then only areas with non-transparent pixels drawn
     * will appear from [drawing].
     */
    fun drawStenciled(mode: StencilMode, stencil: () -> Unit, drawing: () -> Unit) {
        // Mask out everything except the contents of the panel, where the ship may be drawn
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)
        GL11.glStencilMask(0xff)
        GL11.glEnable(GL11.GL_STENCIL_TEST)

        // Any pixel coming through will fail the stencil test, and it's value will replace the
        // zero initially in the stencil buffer
        GL11.glStencilFunc(GL11.GL_NEVER, 1, 0xFF)
        GL11.glStencilOp(GL11.GL_REPLACE, GL11.GL_KEEP, GL11.GL_KEEP)

        stencil()

        // Find the stencil value that allows the stenciled image to be draw. A value of
        // one only allows drawing if that pixel was stenciled, a value of zero blocks drawing
        // anywhere that was stenciled.
        val requiredValue = when (mode) {
            StencilMode.BLOCKING -> 0
            StencilMode.MASKING -> 1
        }
        GL11.glStencilFunc(GL11.GL_EQUAL, requiredValue, 0xFF)

        // Whatever is drawn does not affect the stencil mask
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)

        drawing()

        // Don't break anything else
        GL11.glDisable(GL11.GL_STENCIL_TEST)
    }

    fun startSlick(game: Game) {
        throw UnsupportedOperationException("Use the AndroidGameContainer on Android")
    }

    /**
     * Given an element with the attributes 'x' and 'y', return a ConstPoint matching them.
     */
    fun parsePosElem(elem: Element): ConstPoint {
        val x = elem.getAttributeValue("x").toInt()
        val y = elem.getAttributeValue("y").toInt()
        return ConstPoint(x, y)
    }

    enum class StencilMode {
        BLOCKING,
        MASKING,
    }
}

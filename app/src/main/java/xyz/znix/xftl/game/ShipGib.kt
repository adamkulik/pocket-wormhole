package xyz.znix.xftl.game

import org.jdom2.Element
import xyz.znix.xftl.TWO_PI
import xyz.znix.xftl.VisualRandom
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.random
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import kotlin.math.cos
import kotlin.math.sin

class ShipGib(val ship: ShipBlueprint, node: Element) {
    val imgPath: String
    val imgPathGlow: String

    val velocityRange = parseRange(node.getChild("velocity"))
    val directionRange = parseRange(node.getChild("direction"))
    val angularVelocityRange = parseRange(node.getChild("angular"))
    val offset = ConstPoint(node.getChildTextTrim("x").toInt(), node.getChildTextTrim("y").toInt())

    init {
        imgPath = "img/ship/${ship.img}_${node.name}.png"
        imgPathGlow = "img/ships_glow/${ship.img}_${node.name}.png"
    }

    fun createInstance(game: InGameState): Instance {
        return Instance(game.getImgIfExists(imgPath) ?: game.getImg(imgPathGlow))
    }

    companion object {
        // Number of seconds the gibs play for
        val GIB_DURATION = 2f

        private fun parseRange(elem: Element): ClosedFloatingPointRange<Float> {
            val min = elem.getAttributeValue("min").toFloat()
            val max = elem.getAttributeValue("max").toFloat()
            return min..max
        }
    }

    /**
     * This represents a gib used on a ship. In contrast to the parent class,
     * this isn't referenced by [ShipBlueprint] and thus can have mutable variables.
     */
    inner class Instance(private val img: Image) {
        private val velocity = velocityRange.random(VisualRandom)
        private val direction = -directionRange.random(VisualRandom) / 360 * TWO_PI // Deg->rad
        private val angular = angularVelocityRange.random(VisualRandom)

        private var time = 0f

        val isFinished: Boolean get() = time >= GIB_DURATION

        fun draw(g: Graphics, basePoint: IPoint) {
            val pos = Point(basePoint)
            pos += offset

            val dist = velocity * time * 25
            val progress = time / GIB_DURATION
            pos += ConstPoint((cos(direction) * dist).toInt(), (sin(direction) * dist).toInt())

            val rotation = progress * angular * Math.PI * 2
            g.pushTransform()
            g.translate(pos.x.f, pos.y.f)
            g.rotate(img.width.f / 2, img.height.f / 2, rotation.toFloat())
            img.draw(0f, 0f)
            g.popTransform()
        }

        fun update(dt: Float) {
            time += dt
        }

        // Currently just used for testing
        fun reset() {
            time = 0f
        }
    }
}

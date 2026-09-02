package xyz.znix.xftl

import org.jdom2.Element
import kotlin.random.Random

class RandomWithChance(elem: Element) {
    val min: Int = elem.getAttributeValue("min").toInt()
    val max: Int = elem.getAttributeValue("max").toInt()

    /**
     * The chance that a zero will be rolled instead of a number between min and max.
     */
    val zeroChance: Float = elem.getAttributeValue("chance")?.toFloat() ?: -1f

    fun pick(rand: Random): Int {
        if (zeroChance > 0f) {
            if (rand.nextFloat() < zeroChance)
                return 0
        }

        return (min..max).random(rand)
    }
}

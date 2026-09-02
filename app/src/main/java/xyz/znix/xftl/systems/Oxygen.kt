package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.game.UIUtils
import xyz.znix.xftl.replaceArg
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import kotlin.math.max
import kotlin.math.roundToInt

class Oxygen(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.OXYGEN

    val refillRate: Float
        get() {
            // Drains 6% per second, including the constant drain rate.
            if (isHackActive)
                return -(0.06f - ROOM_DRAIN_RATE)

            if (powerSelected == 0)
                return 0f

            // The UI is wrong, the refill rates are 1,4,7
            // Note we add 2, since for level 1 we have to offset the
            // drain rate, then also refill at a rate equal to the drain rate.
            val multiplier = 2 + (powerSelected - 1) * REFILL_SCALING
            return multiplier * ROOM_DRAIN_RATE
        }

    // Nothing to serialise
    override fun saveSystem(elem: Element, refs: ObjectRefs) = Unit
    override fun loadSystem(elem: Element, refs: RefLoader) = Unit

    companion object {
        /**
         * 1.2% oxygen drain per second, with the oxygen system off.
         */
        const val ROOM_DRAIN_RATE = 0.012f

        /**
         * The amount of oxygen below which crew take damage and the warning stripes appear.
         */
        const val OXYGEN_CRITICAL_LEVEL = 0.05f

        var REFILL_SCALING = 3f

        val INFO: SystemInfo = OxygenInfo
    }
}

private object OxygenInfo : SystemInfo("oxygen") {
    override val canBeManned: Boolean get() = false

    override fun create(blueprint: SystemBlueprint) = Oxygen(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        val fixedPoint = (max(1f, Oxygen.REFILL_SCALING * level) * 100).roundToInt()
        val speedStr = UIUtils.formatStringFTL(fixedPoint)
        return translator["oxygen_on"].replaceArg(speedStr)
    }
}

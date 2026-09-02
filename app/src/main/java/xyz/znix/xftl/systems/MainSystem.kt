package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Constants
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.game.EnergySource
import xyz.znix.xftl.game.ReactorEnergySource
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.max
import kotlin.math.min

abstract class MainSystem(blueprint: SystemBlueprint) : AbstractSystem(blueprint) {
    /**
     * The amount of power this system is drawing from the reactor, and other sources.
     *
     * It's the amount 'selected' because it's the value you change by
     * adjusting the system's power level.
     *
     * This is simply the sum of all the power in [selectedPowerSources].
     */
    // Note: this is cached since it's used *everywhere*
    var powerSelected: Int = 0
        private set

    /**
     * The amount of power this system is forced to use, and can't decrease below.
     *
     * This is set by Zoltan power sources.
     */
    var forcedPower: Int = 0
        private set

    /**
     * The amount of power the player wants the system to have.
     *
     * After the system is damaged and repaired, or when an ion wears off, this
     * is what's used to determine whether to increase the system's power.
     */
    // TODO implement for weapons and drones
    var targetPower: Int = 0

    /**
     * The power this system is supplied with, broken down by where it's coming from.
     */
    private val selectedPowerSources = HashMap<EnergySource, Int>()

    /**
     * The power sources that were in use before [consumePowerFirst] was called.
     *
     * This must only be used by [consumePowerFirst] and [consumePowerSecond].
     */
    private val previousPowerSources = HashMap<EnergySource, Int>()

    val powerAvailable: Int get() = min(undamagedEnergy, ship.powerAvailable + powerSelected)

    val powerUnused: Int get() = min(undamagedEnergy - powerSelected, ship.powerAvailable)

    open val isPowerLocked: Boolean get() = isIonised || isHackActive

    abstract val sortingType: SortingType

    /**
     * Use a 54-pixel gap in the power bar, to allow a button (eg cloaking)
     * to fit in next to the power icon.
     */
    open val insertButtonSpace: Boolean get() = false

    private val powerBarGlow: Image by onInit { it.getImg("img/icons/bar_backgroundglow.png") }

    override val effectiveIonPowerLimit: Int?
        get() {
            val limit = ionPowerLimit ?: return null
            val zoltanPower = EnergySource.PER_SYSTEM_TYPES
                .sumOf { if (it.isIonProof) it.getSystemPower(this) else 0 }
            return max(zoltanPower, limit)
        }

    init {
        check(!blueprint.info!!.isSubSystem)
    }


    override fun drawPowerBars(g: Graphics, x: Int, yOfBottom: Int, hoverGlow: Boolean): Int {
        // The top of the bottom bar
        val baseY = yOfBottom - 6

        // Need to grab this as a local so the compiler knows it won't change
        val scriptedPowerLimit = this.scriptedPowerLimit

        var nextLevel = 0
        var nextBarY = baseY
        var prevBarY = nextBarY

        var repairY = 0
        var sabotageY = 0

        fun nextBar() {
            // The repair bar
            if (nextLevel == energyLevels - damagedEnergyLevels) {
                repairY = nextBarY
            }

            // The sabotage bar
            if (nextLevel == energyLevels - damagedEnergyLevels - 1) {
                sabotageY = nextBarY
            }

            prevBarY = nextBarY
            nextBarY -= 6 + getPowerBarSpacing(nextLevel)
            nextLevel++
        }

        fun drawGlow() {
            if (!hoverGlow) {
                return
            }

            // Vanilla draws this image twice to make the glow stronger
            powerBarGlow.drawAlignedCentred(x + 16 / 2, nextBarY + 6 / 2)
            powerBarGlow.drawAlignedCentred(x + 16 / 2, nextBarY + 6 / 2)
        }

        // Draw the power sources in priority order - the highest-priority
        // sources go at the bottom, lower priorities go higher up.
        for (type in EnergySource.TYPES) {
            val amount = selectedPowerSources[type] ?: 0
            if (amount == 0)
                continue

            for (i in 0 until amount) {
                drawGlow()

                // Use the generic power bar visuals
                type.drawSystemPowerBar(g, this, x, nextBarY, 16, 6)

                nextBar()
            }
        }

        // Draw the remaining, non-powered bars
        while (nextLevel < energyLevels) {
            val y = nextBarY

            when {
                nextLevel >= energyLevels - damagedEnergyLevels -> {
                    // System damaged/broken
                    g.colour = Constants.SYS_ENERGY_BROKEN
                    g.drawRect(x, y, 16 - 1, 6 - 1)
                    g.drawLine(x, y + 6, x + 16, y)
                }

                scriptedPowerLimit != null && nextLevel >= scriptedPowerLimit -> {
                    // System power limited by a scripted event
                    g.colour = Constants.SYS_ENERGY_EVENT_LOCKED
                    g.drawRect(x, y, 16 - 1, 6 - 1)
                    g.drawLine(x, y + 6, x + 16, y)
                }

                else -> {
                    // System depowered
                    drawGlow()
                    g.colour = Constants.SYS_ENERGY_DEPOWERED
                    g.drawRect(x, y, 16 - 1, 6 - 1)
                }
            }

            nextBar()
        }

        // The repair bar
        g.colour = Constants.SYS_ENERGY_REPAIR
        val repairWidth = (16 * repairProgress).toInt()
        g.fillRect(x + 16 - repairWidth, repairY, repairWidth, 6)

        // The sabotage bar
        g.colour = Constants.SYS_ENERGY_SABOTAGE
        val sabotageWidth = (16 * damageProgress).toInt()
        g.fillRect(x, sabotageY, sabotageWidth, 6)

        return prevBarY
    }

    override fun isMannableBy(crew: AbstractCrew): Boolean {
        if (powerSelected == 0)
            return false

        return super.isMannableBy(crew)
    }

    /**
     * Get the spacing (in pixels) between a power bar and the one above it.
     *
     * This is used to separate the shield power into blocks of two.
     */
    protected open fun getPowerBarSpacing(powerLevel: Int): Int {
        return 2
    }

    override fun powerLimitChanged() {
        if (undamagedEnergy < powerSelected) {
            // This ultimately calls consumePower, which will reduce our selected
            // power if there isn't enough, in turn calling powerStateChanged.
            ship.updateAvailablePower()
        }

        if (powerSelected < targetPower && powerSelected < undamagedEnergy) {
            // We've been repaired (or the ion wore off), try and return to
            // our original power level.
            val systemRequested = min(targetPower, undamagedEnergy)
            val nextValue = min(powerAvailable, systemRequested)
            if (!setSystemPower(nextValue) || nextValue < systemRequested) {
                // We didn't have enough reactor power to restore this level.
                // TODO show a not-enough-power warning here.
                targetPower = powerSelected
            }
        }
    }

    /**
     * Increase this system's consumed power.
     *
     * This remembers the power value, which is set as the player's selected
     * target value to be restored after the system is damaged and repaired,
     * so it shouldn't be called without the player's input.
     */
    open fun increasePower() {
        if (isPowerLocked)
            return

        setSystemPower(powerSelected + 1)

        targetPower = powerSelected
    }

    /**
     * Increase this system's consumed power.
     *
     * This remembers the power value, which is set as the player's selected
     * target value to be restored after the system is damaged and repaired,
     * so it shouldn't be called without the player's input.
     */
    open fun decreasePower() {
        if (isPowerLocked)
            return

        setSystemPower(powerSelected - 1)

        targetPower = powerSelected
    }

    /**
     * Attempt to set the power to a given level.
     *
     * When increasing the power, this is atomic - the power will either be
     * increased to [level], or it will be left the same - it won't be partially
     * increased if it's not possible to increase it all the way.
     *
     * This does NOT check [isPowerLocked] - that must be checked by the caller
     * if appropriate.
     *
     * Returns true if the change was made, or false if that was not possible.
     */
    protected fun setSystemPower(level: Int): Boolean {
        // Are we already there?
        if (level == powerSelected)
            return true

        // Increasing power is atomic, eg for shields we need to increase or
        // decrease it by 2, or do nothing.
        val available = powerAvailable
        if (level > available && level > powerSelected)
            return false

        if (level < 0 || level > undamagedEnergy)
            return false

        // Decreasing power is not atomic, to avoid getting stuck in a state
        // where we can neither increase nor decrease power.
        val clamped = level.coerceAtLeast(forcedPower)

        // Already clamped.
        if (clamped == powerSelected)
            return false

        powerSelected = clamped

        // This indirectly calls powerStateChanged.
        ship.updateAvailablePower()

        return true
    }

    /**
     * Deduct this system's power usage from the ship's available power, and reduce
     * this system's power usage if there's not enough left.
     *
     * This function must not take more power than the system was previously using
     * from any source, as that may deprive other systems of power.
     *
     * This should only be called by Ship.updateAvailablePower.
     */
    fun consumePowerFirst(powerAvailable: HashMap<EnergySource, Int>) {
        previousPowerSources.clear()
        previousPowerSources.putAll(selectedPowerSources)
        selectedPowerSources.clear()

        var totalRemaining = powerSelected.coerceIn(0, undamagedEnergy)

        // How much more power we can accept until the system is fully powered.
        var remainingUntilFull = undamagedEnergy

        // First grab per-room power, eg from Zoltans, since it can't be
        // turned on or off.
        // This means that if a Zoltan walks into the room, it'll displace
        // some other source of power.
        for (type in EnergySource.PER_SYSTEM_TYPES) {
            val bonusPower = min(type.getSystemPower(this), remainingUntilFull)
            if (bonusPower == 0)
                continue

            remainingUntilFull -= bonusPower
            totalRemaining -= bonusPower
            selectedPowerSources[type] = bonusPower

            require(bonusPower >= 0)
            require(remainingUntilFull >= 0)
        }

        // We can end up with a negative totalRemaining if we're getting more
        // zoltan power than we want.
        totalRemaining = totalRemaining.coerceAtLeast(0)

        // TYPES is in order of priority, so we'll use stuff like the reactor
        // before battery power.
        for (type in EnergySource.GLOBAL_TYPES) {
            var remaining = previousPowerSources[type] ?: 0

            // If we've got some new power from one source (eg a Zoltan), that
            // should reduce the amount of power we pull from the
            // lowest-priority source.
            remaining = min(remaining, totalRemaining)

            if (remaining == 0)
                continue

            // If that's not enough, pull this type of power from the ship.
            val available = powerAvailable[type] ?: continue
            val toDeduct = min(available, remaining)
            powerAvailable[type] = available - toDeduct

            remaining -= toDeduct

            selectedPowerSources[type] = toDeduct
            totalRemaining -= toDeduct

            // These shouldn't be negative
            require(toDeduct >= 0)
            require(remaining >= 0)
            require(available >= 0)
            require(totalRemaining >= 0)
        }
    }

    /**
     * Like [consumePowerFirst], but this function can grab extra power since
     * it won't steal it from other systems.
     *
     * This should only be called by Ship.updateAvailablePower.
     */
    fun consumePowerSecond(powerAvailable: HashMap<EnergySource, Int>) {
        // Separately track how much power to add, so we can switch between
        // sources should one run out.
        // This comes from powerSelected, so that setSystemPower can use that
        // to indicate how much power we actually want.
        val previousDemand = powerSelected.coerceIn(0, undamagedEnergy)
        val currentAmount = selectedPowerSources.values.sum()
        var totalRemaining = previousDemand - currentAmount

        // Maybe one or more power sources couldn't supply as much as they
        // used to, or setSystemPower is demanding a bit more.
        // In either case, grab as much of it as we can in order of priority.
        for (type in EnergySource.GLOBAL_TYPES) {
            if (totalRemaining <= 0)
                break

            val prevAmount = selectedPowerSources[type] ?: 0

            val available = powerAvailable[type] ?: continue
            val toDeduct = min(available, totalRemaining)
            powerAvailable[type] = available - toDeduct

            totalRemaining -= toDeduct
            selectedPowerSources[type] = prevAmount + toDeduct

            require(toDeduct >= 0)
        }

        updateCachedSelectedPower()

        // Alert the subclass if anything has changed.
        if (selectedPowerSources != previousPowerSources) {
            powerStateChanged()
        }
    }

    private fun updateCachedSelectedPower() {
        powerSelected = selectedPowerSources.values.sum()

        // Store our forced power value, which we can't decrease below
        forcedPower = selectedPowerSources.entries.filter { it.key.isPerSystem }.sumOf { it.value }
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        // Put the reactor power in an attribute, since it's by far the most
        // common, and it lets most of the system elements collapse.
        val reactorPower = selectedPowerSources[ReactorEnergySource] ?: 0
        SaveUtil.addAttrInt(elem, "power", reactorPower)

        for (type in EnergySource.TYPES) {
            // Don't add reactor power in both an attribute and element
            if (type == ReactorEnergySource)
                continue

            val amount = selectedPowerSources[type] ?: 0
            if (amount == 0)
                continue

            val powerElem = Element("powerSource")
            SaveUtil.addAttrInt(powerElem, "amount", amount)
            SaveUtil.addAttr(powerElem, "type", type.serialisationId)
            elem.addContent(powerElem)
        }
    }

    override fun loadFromXML(elem: Element, refs: RefLoader) {
        // Load the power sources, split into their different types.
        selectedPowerSources.clear()
        selectedPowerSources[ReactorEnergySource] = SaveUtil.getAttrInt(elem, "power")

        for (powerElem in elem.getChildren("powerSource")) {
            val amount = SaveUtil.getAttrInt(powerElem, "amount")
            val typeId = SaveUtil.getAttr(powerElem, "type")

            val type = EnergySource.TYPES.first { it.serialisationId == typeId }
            selectedPowerSources[type] = amount
        }

        updateCachedSelectedPower()

        // Load our stuff before calling the super-method, so that when
        // the system loading code runs it has the correct power level.
        super.loadFromXML(elem, refs)
    }

    // List of the default systems, for sorting purposes
    // Modded systems can just use one of the vanilla systems, since if two
    // systems share the same sorting type they'll use the room ID as a tie-breaker.
    enum class SortingType {
        SHIELD,
        ENGINES,
        MEDBAY,
        CLONEBAY,
        OXYGEN,
        TELEPORTER,
        CLOAKING,
        ARTILLERY,
        MIND_CONTROL,
        HACKING,
        WEAPONS,
        DRONES;
    }
}

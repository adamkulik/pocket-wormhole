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
     * The amount of power this system is drawing from the reactor (and other
     * global sources like batteries).
     *
     * It's the amount 'selected' because it's the value you change by
     * adjusting the system's power level.
     *
     * This is deliberately NOT the system's total power: per-room power (eg
     * Zoltan bars) occupies bars of the system's capacity on its own, and the
     * player's selection fills the room left on top of it (vanilla: the +/-
     * clicks take and give back reactor bars through the ship's power
     * manager, and a system's total can never exceed its capacity).
     */
    // Note: this is cached since it's used *everywhere*
    var powerSelected: Int = 0
        protected set

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
     * The power this system is actually running at: the sum of everything in
     * [selectedPowerSources]. This is at most [powerSelected] - the two differ
     * when the ship's power supply can't keep up, e.g. in an ion storm, where
     * the demand keeps the player's selection (so it restores automatically
     * when the storm passes) while only the supplied power functions
     * (GitHub issue #95; wiki: Environmental Hazards - "Power will be removed
     * automatically from systems").
     *
     * All gameplay behaviour must read THIS, not [powerSelected] - that's the
     * demand, used for button state, power-bar highlights and re-powering.
     */
    var powerSupplied: Int = 0
        protected set

    /**
     * The power sources that were in use before [consumePowerFirst] was called.
     *
     * This must only be used by [consumePowerFirst] and [consumePowerSecond].
     */
    private val previousPowerSources = HashMap<EnergySource, Int>()

    /**
     * The room this system has for player-selected power: the undamaged
     * capacity minus any per-room power (eg Zoltan bars), which occupies bars
     * of the capacity by itself.
     */
    protected fun reactorRoom(): Int = (undamagedEnergy - forcedPower).coerceAtLeast(0)

    val powerAvailable: Int get() = min(reactorRoom(), ship.powerAvailable + powerSelected)

    val powerUnused: Int get() = min(reactorRoom() - powerSelected, ship.powerAvailable)

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
        // A Zoltan-powered system functions without selected power, so it
        // can be manned (vanilla counts the manning bonus whenever the
        // system's total power is non-zero).
        if (powerSupplied == 0)
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
        if (reactorRoom() < powerSelected + forcedPower) {
            // This ultimately calls consumePower, which will reduce our selected
            // power if there isn't enough, in turn calling powerStateChanged.
            // The forced power is included in case the capacity shrinkage
            // (damage) took the room the Zoltan bars were occupying.
            ship.updateAvailablePower()
        }

        restoreTargetPowerIfPossible()
    }

    /**
     * Runs whenever our power sources change, which includes a Zoltan
     * entering or leaving the room. Besides the repair/ion recovery in
     * [powerLimitChanged], this is what refills the player's selection after
     * a Zoltan leaves and frees its bar's worth of capacity again (vanilla's
     * auto-repower runs for every system except weapons and drones).
     */
    override fun powerStateChanged() {
        restoreTargetPowerIfPossible()
    }

    private fun restoreTargetPowerIfPossible() {
        if (powerSelected >= targetPower || powerSelected >= reactorRoom())
            return

        // We've been repaired, the ion wore off or a Zoltan left the room:
        // try and return to our original power level.
        val systemRequested = (targetPower - forcedPower).coerceIn(0, reactorRoom())
        val nextValue = min(powerAvailable, systemRequested)
        if (!setSystemPower(nextValue)) {
            // We didn't have enough reactor power to restore this level.
            // TODO show a not-enough-power warning here.
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

        // Update the target before changing the power: the allocation inside
        // setSystemPower runs the restore logic, which must see the level we
        // are asking for rather than the previous one.
        val wanted = powerSelected + 1
        targetPower = wanted + forcedPower
        if (!setSystemPower(wanted))
            targetPower = powerSelected + forcedPower
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

        val wanted = powerSelected - 1
        targetPower = wanted + forcedPower
        if (!setSystemPower(wanted))
            targetPower = powerSelected + forcedPower
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

        // Increasing power is atomic - the power will either be taken from
        // the reactor in full, or not at all.
        val available = powerAvailable
        if (level > available && level > powerSelected)
            return false

        // The player's selection can never push the system's total power
        // (selection + per-room power) past its undamaged capacity.
        if (level < 0 || level > reactorRoom())
            return false

        // Decreasing power is not atomic, to avoid getting stuck in a state
        // where we can neither increase nor decrease power.
        powerSelected = level

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

        // First grab per-room power, eg from Zoltans. This power is ADDITIVE:
        // it occupies bars of the system's capacity on its own, and the
        // player's selection fills the room left on top of it. This means a
        // Zoltan walking into a powered system shrinks the room below, so
        // some of the player's selection can no longer be allocated and its
        // bars return to the reserve (vanilla's reconcile loop), while a
        // Zoltan leaving grows the room back and the selection re-fills.
        var remainingUntilFull = undamagedEnergy
        for (type in EnergySource.PER_SYSTEM_TYPES) {
            val bonusPower = min(type.getSystemPower(this), remainingUntilFull)
            if (bonusPower == 0)
                continue

            remainingUntilFull -= bonusPower
            selectedPowerSources[type] = bonusPower

            require(bonusPower >= 0)
            require(remainingUntilFull >= 0)
        }

        // The player's selection is the reactor/battery share, clamped so the
        // system's total power stays within its (undamaged) capacity.
        var totalRemaining = powerSelected.coerceIn(0, remainingUntilFull)

        // TYPES is in order of priority, so we'll use stuff like the reactor
        // before battery power.
        for (type in EnergySource.GLOBAL_TYPES) {
            var remaining = min(previousPowerSources[type] ?: 0, totalRemaining)

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
        // Only the player-selected (reactor/battery) share is topped up here -
        // the per-room power was added in consumePowerFirst and occupies bars
        // of the capacity on its own.
        var roomForSelection = undamagedEnergy
        var currentAmount = 0
        for ((type, amount) in selectedPowerSources) {
            if (type.isPerSystem) {
                roomForSelection -= amount
            } else {
                currentAmount += amount
            }
        }
        roomForSelection = roomForSelection.coerceAtLeast(0)

        // This comes from powerSelected, so that setSystemPower can use that
        // to indicate how much power we actually want.
        var totalRemaining = powerSelected.coerceIn(0, roomForSelection) - currentAmount

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

    protected open fun updateCachedSelectedPower() {
        var zoltanPower = 0
        var demand = 0
        for ((type, amount) in selectedPowerSources) {
            if (type.isPerSystem) {
                zoltanPower += amount
            } else {
                demand += amount
            }
        }

        // The cached selection is our demand: what the player (or the AI)
        // asked for in reactor power, clamped to the room the per-room power
        // left. It is deliberately NOT the raw sum of selectedPowerSources -
        // that would let a Zoltan's per-room bonus bar leak INTO the selection
        // when it walks in, and leave the demand permanently inflated (a bar
        // drawn from the reactor while doing nothing) when it walks back out
        // (GitHub issue #66).
        powerSelected = max(demand, powerSelected.coerceIn(0, (undamagedEnergy - zoltanPower).coerceAtLeast(0)))

        // The functional power level: everything we actually got allocated,
        // including per-system sources like Zoltan bars.
        powerSupplied = selectedPowerSources.values.sum()

        // Store our forced power value, which we can't decrease below
        forcedPower = zoltanPower
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

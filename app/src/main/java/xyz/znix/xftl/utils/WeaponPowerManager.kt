package xyz.znix.xftl.utils

import xyz.znix.xftl.systems.MainSystem

/**
 * This class is responsible for managing the power to weapons-like systems,
 * namely weapons and drones (and it's also usable for mods).
 *
 * These weapons/drones/whatever are referred to as 'items' here.
 *
 * The logic mirrors vanilla's weapon-power distributor (decompiled from
 * FTLGame.exe 1.6.14, see issue-135-zoltan-power-plan.md):
 *
 * - Per-room (Zoltan) power is additive: it occupies bars of the system's
 *   capacity on its own, and is handed out to items in slot order, first
 *   slot first (vanilla's own tooltip says "Always powers the first weapon.
 *   Click and drag to reorder").
 * - An item fully covered by Zoltan power turns on for free; one partially
 *   covered keeps the bar reserved but stays off.
 * - An armed item whose Zoltan share shrinks (a Zoltan leaving, or a
 *   reorder moving the bar elsewhere) turns OFF, even if the reactor could
 *   cover the difference - items are never auto-repowered.
 * - The player's + click arms the first unpowered item, paying only its
 *   non-Zoltan remainder; the - click disarms the last armed item that has
 *   any non-Zoltan power.
 *
 * Unlike vanilla we run the distributor from [MainSystem.powerStateChanged]
 * (on every power-source change) plus on item changes, rather than every
 * frame - the outcome is the same, as the algorithm is idempotent.
 */
class WeaponPowerManager(private val system: MainSystem, private val items: ItemAccess) {
    /**
     * The amount of Zoltan power each slot receives.
     *
     * This is effectively just for caching, as it can be found purely
     * from the current [MainSystem.forcedPower] value, and iterating through
     * all the weapons.
     */
    private val forcedPower = IntArray(items.count)

    /**
     * The Zoltan share each ITEM currently receives, tracked by item
     * identity. Slot indices change when the player reorders weapons, but
     * the share belongs to the item - tracking per-item is what makes a
     * reorder re-target the Zoltan bar (vanilla re-runs its distributor
     * with the new slot order to the same effect).
     */
    private val itemForced = HashMap<Any, Int>()

    /**
     * How much reactor power the currently-armed items need.
     *
     * This is the system's selected power, minus any per-room power: the
     * Zoltan share of an armed item is additive and free, so it isn't part
     * of the reactor demand.
     */
    val currentPower: Int
        get() {
            var reactorPower = 0
            for (slot in forcedPower.indices) {
                if (!items.isItemPowered(slot))
                    continue

                reactorPower += items.getItemPowerDraw(slot) - forcedPower[slot]
            }
            return reactorPower
        }

    /**
     * Update the armed items, to accommodate the system's new power state.
     */
    fun powerStateChanged() {
        // Hand out the Zoltan power to the slots in order - the first slot's
        // item takes what it needs, then the next one, and so on.
        forcedPower.fill(0)
        var remainingForcedPower = system.forcedPower
        for (slot in forcedPower.indices) {
            if (!items.hasItem(slot))
                continue

            val powerDraw = items.getItemPowerDraw(slot)

            forcedPower[slot] = remainingForcedPower.coerceAtMost(powerDraw)

            // Multiverse adds 'battery' weapons, which use a negative amount
            // of power, which powers the next weapon in the list.
            // This mostly 'just works', but we need to make sure we don't
            // stop the loop as soon as we run out of zoltan power - otherwise
            // battery weapons in any slot other than the first (assuming no
            // Zoltans in the room) wouldn't do anything, as this check would
            // always be skipped.
            remainingForcedPower -= forcedPower[slot]
        }

        // Vanilla's distributor shrink branch: an armed item whose Zoltan
        // share shrank loses its power entirely. Track the share per ITEM,
        // so reordering items moves the bar with the slots instead of
        // misreading the new occupant as a shrink.
        for (slot in forcedPower.indices) {
            if (!items.hasItem(slot))
                continue

            val item = items.getItemId(slot)
            val previous = itemForced[item] ?: 0
            itemForced[item] = forcedPower[slot]

            if (items.isItemPowered(slot) && forcedPower[slot] < previous)
                items.setItemPowered(slot, false)
        }

        // Forget the shares of items that are gone, to keep the map bounded.
        if (itemForced.size > forcedPower.size * 2) {
            val present = HashSet<Any>()
            for (slot in forcedPower.indices) {
                if (items.hasItem(slot))
                    present.add(items.getItemId(slot))
            }
            itemForced.keys.retainAll(present)
        }

        // First, turn on any items that are fully powered by Zoltans.
        // These ones can't be powered off by the player.
        for (slot in forcedPower.indices) {
            if (!items.hasItem(slot) || items.isItemPowered(slot))
                continue

            val powerDraw = items.getItemPowerDraw(slot)
            if (powerDraw != 0 && forcedPower[slot] == powerDraw)
                items.setItemPowered(slot, true)
        }

        // The items are arranged in order of priority, so turn the last ones off if possible.
        // This covers eg an ion storm cutting the reactor's output.
        for (slot in forcedPower.indices.reversed()) {
            if (!items.isItemPowered(slot))
                continue

            if (system.powerSupplied >= currentPower + system.forcedPower)
                break

            // Force-turn-off the item, even if we have ion damage.
            // This is required since otherwise we could end up powering
            // more items than we're allowed to, for example if
            // we took damage while ion-locked.
            items.setItemPowered(slot, false)
        }

        // Keep the system's selected power in sync with the armed items.
        // This only ever lowers the selection here: raising it happens in
        // [setItemPower], which first checks the power is actually
        // available. Lowering is what releases an un-armed item's bar back
        // to the reactor (vanilla: items are sticky-off, the system bar
        // goes back to the reactor).
        if (system.powerSelected != currentPower)
            items.setSystemPower(currentPower)
    }

    fun increasePower() {
        if (system.isPowerLocked)
            return

        for (slot in 0 until items.count) {
            if (items.isItemPowered(slot) || !items.hasItem(slot))
                continue

            val powerRequired = items.getItemPowerDraw(slot) - forcedPower[slot]
            if (powerRequired > system.powerUnused)
                continue

            setItemPower(slot, true)
            return
        }
    }

    fun decreasePower() {
        if (system.isPowerLocked)
            return

        for (slot in items.count - 1 downTo 0) {
            if (!items.isItemPowered(slot))
                continue

            // Purely Zoltan-powered, can't disable manually.
            if (forcedPower[slot] == items.getItemPowerDraw(slot))
                continue

            setItemPower(slot, false)
            return
        }
    }

    /**
     * Turns an item on or off, as requested by the player or AI.
     *
     * Returns true if successful.
     */
    fun setItemPower(slot: Int, newPower: Boolean): Boolean {
        if (!items.hasItem(slot))
            return false

        if (items.isItemPowered(slot) == newPower)
            return true

        // Can't power weapons on or off with ion damage.
        if (system.isPowerLocked)
            return false

        val powerDraw = items.getItemPowerDraw(slot)

        // Purely Zoltan-powered, can't disable manually.
        val nonZoltanPower = powerDraw - forcedPower[slot]
        if (!newPower && nonZoltanPower == 0)
            return false

        if (newPower) {
            // Try to increase the system power to accommodate this weapon.
            // This increase will be instantly reverted by powerStateChanged,
            // as the weapon isn't actually turned on yet, but it lets us
            // check if we have the available power or not.
            if (!items.setSystemPower(currentPower + nonZoltanPower))
                return false
        }

        items.setItemPowered(slot, newPower)
        items.setSystemPower(currentPower)
        return true
    }

    /**
     * Get the amount of Zoltan (or similar) power being forced into
     * a weapon, which can't be disabled.
     */
    fun getForcedPower(slot: Int): Int {
        return forcedPower[slot]
    }

    /**
     * Interface for reading and setting the power state of items.
     */
    interface ItemAccess {
        /**
         * Returns the maximum possible number of items in this system.
         */
        val count: Int

        /**
         * Returns true if there's an item in the given slot.
         */
        fun hasItem(slot: Int): Boolean

        /**
         * Get the power draw of an item in a given slot, or 0 if there isn't one.
         */
        fun getItemPowerDraw(slot: Int): Int

        /**
         * An identity for the item in the given slot. Used to track each
         * item's Zoltan share across reorders, where an item's slot changes.
         *
         * Only called when [hasItem] is true for the slot.
         */
        fun getItemId(slot: Int): Any

        /**
         * Check if an item is turned on, or false if that item doesn't exist.
         */
        fun isItemPowered(slot: Int): Boolean

        /**
         * Turns an item on/off, does nothing if that item isn't there.
         */
        fun setItemPowered(slot: Int, powered: Boolean)

        /**
         * Set the system's selected (reactor) power level.
         *
         * Returns true if successful.
         */
        fun setSystemPower(level: Int): Boolean
    }
}

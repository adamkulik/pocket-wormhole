package xyz.znix.xftl.utils

import xyz.znix.xftl.systems.MainSystem

/**
 * This class is responsible for managing the power to weapons-like systems,
 * namely weapons and drones (and it's also usable for mods).
 *
 * These weapons/drones/whatever are referred to as 'items' here.
 */
class WeaponPowerManager(private val system: MainSystem, private val items: ItemAccess) {
    /**
     * The amount of Zoltan power each item receives.
     *
     * This is effectively just for caching, as it can be found purely
     * from the current [forcedPower] value, and iterating through
     * all the weapons.
     */
    private val forcedPower: IntArray = IntArray(items.count)

    /**
     * Shows how much power the currently-powered items are using.
     *
     * This should match [MainSystem.powerSelected], except when weapons are being
     * turned on and off, hence why this is only used for power management.
     */
    val currentPower: Int
        get() {
            // Only add up the non-forced power, so we don't have to account
            // for power that's put into powered-off weapons.
            var reactorPower = 0
            for (slot in forcedPower.indices) {
                if (items.isItemPowered(slot))
                    reactorPower += items.getItemPowerDraw(slot) - forcedPower[slot]
            }

            // And add the forced power back on at the end.
            return reactorPower + system.forcedPower
        }


    /**
     * Update the powered weapons, to accommodate the system's new power state.
     */
    fun powerStateChanged() {
        // First, turn on any items that are fully powered by Zoltans.
        // These ones can't be powered off by the player.
        // This also updates forcedPower.
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
            if (forcedPower[slot] != powerDraw)
                continue

            // TODO does this match vanilla behaviour with ions?
            items.setItemPowered(slot, true)
        }

        // The items are arranged in order of priority, so turn the last ones off if possible.
        for (slot in items.count - 1 downTo 0) {
            if (!items.isItemPowered(slot))
                continue

            if (system.powerSelected >= currentPower)
                break

            // Force-turn-off the item, even if we have ion damage.
            // This is required since otherwise we could end up powering
            // more items than we're allowed to, for example if
            // we took damage while ion-locked.
            items.setItemPowered(slot, false)
        }

        // If the system has too much power - more than the items are
        // using - then get rid of that excess.
        if (system.powerSelected != currentPower) {
            items.setSystemPower(currentPower)
        }
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
         * Check if an item is turned on, or false if that item doesn't exist.
         */
        fun isItemPowered(slot: Int): Boolean

        /**
         * Turns an item on/off, does nothing if that item isn't there.
         *
         * This is a no-op if the item is already powered as specified.
         */
        fun setItemPowered(slot: Int, powered: Boolean)

        /**
         * Calls [MainSystem.setSystemPower]
         */
        fun setSystemPower(level: Int): Boolean
    }
}

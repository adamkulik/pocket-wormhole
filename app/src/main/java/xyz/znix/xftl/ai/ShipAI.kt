package xyz.znix.xftl.ai

import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.Weapons
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon
import kotlin.random.Random

/**
 * The overall enemy ship AI.
 *
 * This controls stuff like firing the weapons, activating systems/drones,
 * and other stuff like that.
 *
 * It specifically does not handle crew - see [FriendlyCrewAI] for that.
 */
class ShipAI(val ship: Ship, val player: Ship) {
    private val fireAtChargeLevel = HashMap<AbstractWeaponInstance, Int>()

    private var hadFirstUpdate = !ship.sys.isCurrentlyLoadingSave

    /**
     * A snapshot of everything that affects how power should be allocated.
     *
     * Vanilla's enemy AI completely rebuilds its power allocation every
     * frame (ShipAI::CheckPowerLevels), but xftl's systems react to power
     * changes immediately (engine sounds, drone deployment, etc), so doing
     * that here would produce a burst of bogus on/off events every frame.
     * Instead the same allocation is rebuilt whenever the inputs change -
     * which is exactly when the result could differ.
     */
    private var powerAllocationState: String? = null

    fun update(dt: Float) {
        // If the ship is dying, don't do anything.
        if (ship.isDead)
            return

        if (!hadFirstUpdate) {
            hadFirstUpdate = true

            // Immediately turn the ship's shields on, so the player can't get a sneaky shot in
            // FIXME do this properly
            val shields = ship.shields
            if (shields != null) {
                for (i in 1..shields.energyLevels) {
                    shields.increasePower()
                    shields.update(10f) // Horrible hack
                }
            }
        }

        // Rebuild the power allocation if the power situation changed
        // (a storm arriving/passing, ion damage, a system being damaged or
        // repaired, a weapon/drone being added or used up, etc).
        val powerState = powerStateSignature()
        if (powerState != powerAllocationState) {
            checkPowerLevels()
            powerAllocationState = powerStateSignature()
        }

        updateWeapons()
        updateDrones()

        // Constantly try to trigger cloaking, it'll be ignored
        // whenever it's unavailable.
        ship.cloaking?.activateCloak()

        // Same for the backup battery.
        ship.backupBattery?.startBattery()

        updateHacking()

        updateMindControl()

        // Check if we need to escape or surrender
        if (ship.health <= ship.surrenderHealth) {
            // Don't surrender repeatedly
            ship.surrenderHealth = 0

            // The surrender event must exist, it's XML tag did
            // for surrenderHealth to be set.
            ship.sys.shipUI.showEventDialogue(ship.spec!!.surrender!!.resolve(), Random.nextInt())

            // Use an else-if so we don't try and surrender and escape in
            // the same update cycle.
        } else if (ship.health <= ship.escapeHealth) {
            // Show the event and start the timer running
            ship.escapeHealth = 0
            ship.sys.shipUI.showEventDialogue(ship.spec!!.escape!!.resolve(), Random.nextInt())
            ship.escapeTimer = ship.spec.escapeTimer
        }

        val escapeTimer = ship.escapeTimer
        if (escapeTimer != null) {
            if (ship.canChargeFTL) {
                // The player's FTL Jammer scrambles this ship's FTL computer,
                // slowing the escape countdown by the augment's value - vanilla
                // (value=2): "doubling the time it takes for them to jump".
                // Slowing the countdown rather than inflating the timer keeps
                // timer-value comparisons (e.g. crew AI) vanilla-consistent.
                val jammer = player.getAugmentValue(AugmentBlueprint.FTL_JAMMER)
                val rate = if (jammer > 0f) dt / jammer else dt
                ship.escapeTimer = escapeTimer - rate
            }

            // SlickGame will check the escape timer, and remove
            // the ship if it runs out.
        }
    }

    /**
     * A snapshot of all the inputs that affect the power allocation.
     *
     * The current power levels are included so external changes (a weapon
     * running out of missiles and powering itself down, a drone being
     * destroyed, etc) also trigger a rebuild.
     */
    private fun powerStateSignature(): String {
        val sig = StringBuilder()
        sig.append(ship.powerSupply).append('|')
        sig.append(ship.crew.count { it.health < it.maxHealth }).append('|')

        for (system in ship.mainSystems) {
            sig.append(system.codename).append(':')
                .append(system.powerSelected).append(',')
                .append(system.forcedPower).append(',')
                .append(system.energyLevels).append(',')
                .append(system.damagedEnergyLevels).append(',')
                .append(system.isPowerLocked).append(',')
                .append(system.effectiveIonPowerLimit ?: -1).append(';')
        }

        sig.append('|')
        for (hardpoint in ship.hardpoints) {
            sig.append(hardpoint.weapon?.type?.power ?: -1).append(',')
        }

        sig.append('|')
        for (info in ship.drones?.drones ?: emptyList()) {
            sig.append(info?.type?.power ?: -1).append(',')
        }

        return sig.toString()
    }

    /**
     * Re-allocate the ship's power the way vanilla's enemy AI does (the
     * decompiled FTLGame.exe 1.6.14 `ShipAI::CheckPowerLevels`, mid-function
     * around 0x6259b0). Vanilla drops every system to zero, then puts power
     * back in this order:
     *
     *  1. One bar each to oxygen, the teleporter and the clone bay, plus the
     *     medbay if any crew are hurt.
     *  2. Rounds of one weapon, one drone, one shield bar, one cloak bar and
     *     one engine bar.
     *  3. Whatever is left goes to the remaining systems in system-list order.
     *
     * This is what lets an enemy in a plasma storm keep its weapons and
     * teleporter running instead of starving them behind its shields and
     * engines (GitHub issue #99; wiki: "Enemy ships' reactors are also
     * halved... They can re-allocate power when systems are ionised or
     * damaged").
     */
    private fun checkPowerLevels() {
        // Vanilla starts from a blank slate. We only get here when the power
        // situation actually changed, so the on/off callbacks this fires are
        // a one-off rather than a per-frame loop.
        for (system in ship.mainSystems) {
            while (system.powerSelected > 0) {
                val before = system.powerSelected
                system.decreasePower()
                if (system.powerSelected >= before)
                    break
            }
        }

        // The systems vanilla gives a single bar to before anything else.
        tryIncreasePower(ship.oxygen)
        tryIncreasePower(ship.teleporter)
        tryIncreasePower(ship.clonebay)
        if (ship.crew.any { it.health < it.maxHealth })
            tryIncreasePower(ship.medbay)

        // Then allocate one weapon, one drone, one shield bar, one cloak bar
        // and one engine bar per round until nothing more fits.
        while (true) {
            var progressed = false
            progressed = tryIncreasePower(ship.weapons) || progressed
            progressed = tryIncreasePower(ship.drones) || progressed
            progressed = tryIncreasePower(ship.shields) || progressed
            progressed = tryIncreasePower(ship.cloaking) || progressed
            progressed = tryIncreasePower(ship.engines) || progressed
            if (!progressed)
                break
        }

        // Vanilla's final pass: hand out any remaining power in the order the
        // systems appear in the ship's system list.
        for (system in ship.systems) {
            if (system !is MainSystem)
                continue

            while (tryIncreasePower(system)) {
                // Keep filling this system until it's full or the power runs out.
            }
        }
    }

    /**
     * Try to add one unit of power (one bar, or one weapon/drone item) and
     * report whether anything actually changed.
     */
    private fun tryIncreasePower(system: MainSystem?): Boolean {
        if (system == null)
            return false

        val powerBefore = system.powerSelected
        val itemsBefore = poweredItemCount(system)
        system.increasePower()

        return system.powerSelected > powerBefore || poweredItemCount(system) > itemsBefore
    }

    private fun poweredItemCount(system: MainSystem): Int {
        return when (system) {
            is Weapons -> ship.hardpoints.count { it.weapon?.isPowered == true }
            is Drones -> system.drones.count { it?.instance?.isPowered == true }
            else -> 0
        }
    }

    private fun updateWeapons() {
        val weapons = ship.weapons ?: return

        for ((slot, hp) in ship.hardpoints.withIndex()) {
            val weapon = hp.weapon ?: continue

            // Try powering up all the weapons
            // Do it this way rather than by using weapons.increasePower, since
            // if there's an out-of-ammo missile that'd block any later weapons
            // from turning on.
            if (!weapon.isPowered && weapon.hasEnoughMissiles) {
                weapons.powerManager.setItemPower(slot, true)
                continue
            }

            if (!weapon.isCharged)
                continue

            if (weapon.maxTotalCharges > 1) {
                // Pick a random level to charge up to
                var fireAt = fireAtChargeLevel[weapon]
                if (fireAt == null) {
                    fireAt = (1..weapon.maxTotalCharges).random()
                    fireAtChargeLevel[weapon] = fireAt
                }

                if (weapon.totalReadyCharges < fireAt)
                    continue

                // Pick a new number of shots next time
                fireAtChargeLevel.remove(weapon)
            }

            if (ship.sys.debugFlags.noEnemyFire.set)
                continue

            when (weapon) {
                is IRoomTargetingWeapon -> {
                    val remainingRooms = ArrayList(player.rooms)
                    weapon.fire { pickTarget(remainingRooms) }
                }

                is BeamBlueprint.BeamInstance -> {
                    val remainingRooms = ArrayList(player.rooms)
                    val startRoom = pickTarget(remainingRooms)
                    val endRoom = pickTarget(remainingRooms)
                    val aim = weapon.buildAim(startRoom, endRoom, false)

                    // Set the weapon to start firing
                    weapon.fire(aim)

                    // Play the beam sound effect
                    weapon.type.launchSounds?.get()?.play()
                }
            }
        }
    }

    private fun updateDrones() {
        val drones = ship.drones ?: return

        // Try and deploy all the drones we can, except for boarders
        // when there's an enemy super-shield, as they just explode when
        // they fly into it.
        for ((i, drone) in drones.drones.withIndex()) {
            if (drone == null)
                continue

            if (drone.type.type == DroneBlueprint.DroneType.BOARDER && player.superShield > 0) {
                continue
            }

            drones.setDronePower(i, true)
        }
    }

    private fun pickTarget(remainingRooms: ArrayList<Room>): Room {
        // If we run out of rooms, start over
        if (remainingRooms.isEmpty()) {
            remainingRooms.addAll(player.rooms)
        }

        // Enemies shoot at different targets with each shot
        // TODO hard-mode targeting
        val room = remainingRooms.random()
        remainingRooms.remove(room)
        return room
    }

    private fun updateHacking() {
        val hacking = ship.hacking ?: return

        // Fire the probe at a random system, if
        // it's not already launched.
        if (!hacking.droneLaunched) {
            hacking.selectTarget(player.systems.random().room!!)
            return
        }

        // Run this all the time and it'll only start one
        // when it's ready.
        hacking.startHackingPulse()
    }

    private fun updateMindControl() {
        val mindControl = ship.mindControl ?: return

        if (!mindControl.ready)
            return

        // Go through all the rooms, and try triggering on them all.
        // The system will ignore calls once it starts, so it won't
        // control multiple crew.
        val shuffled = player.rooms.shuffled()
        for (room in shuffled) {
            mindControl.selectRoom(room)
        }
    }
}

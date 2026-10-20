package xyz.znix.xftl.ai

import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon
import kotlin.math.min
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

        // Power up all the systems, most important first: when the reactor
        // can't cover everything (a plasma storm, a small reactor) the
        // shields and guns get it before the medbay or oxygen do, rather than
        // in the power bar's display order (GitHub issue #99).
        for (sys in ship.mainSystems.sortedBy { powerPriority(it) }) {
            for (i in 1..sys.energyLevels) sys.increasePower()
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

    private fun updateWeapons() {
        val weapons = ship.weapons ?: return

        for ((slot, hp) in ship.hardpoints.withIndex()) {
            val weapon = hp.weapon ?: continue

            // Try powering up all the weapons
            // Do it this way rather than by using weapons.increasePower, since
            // if there's an out-of-ammo missile that'd block any later weapons
            // from turning on.
            if (!weapon.isPowered && weapon.hasEnoughMissiles) {
                if (!weapons.powerManager.setItemPower(slot, true)) {
                    // Not enough spare power: take it from the support
                    // systems, so the ship doesn't sit there unarmed
                    // (GitHub issue #99). Only worth it if it's the reactor
                    // that's short, not the (damaged) weapons system.
                    val needed = weapon.type.power - weapons.powerManager.getForcedPower(slot)
                    val room = weapons.undamagedEnergy - weapons.forcedPower - weapons.powerSelected
                    if (needed <= room && freePowerFor(needed))
                        weapons.powerManager.setItemPower(slot, true)
                }
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

    /**
     * The order the AI shares its reactor out in: shields, then the
     * offensive systems, then the support systems.
     */
    private fun powerPriority(sys: MainSystem): Int = when (sys.sortingType) {
        MainSystem.SortingType.SHIELD -> 0
        MainSystem.SortingType.WEAPONS -> 1
        MainSystem.SortingType.DRONES -> 2
        MainSystem.SortingType.TELEPORTER -> 3
        MainSystem.SortingType.HACKING -> 4
        MainSystem.SortingType.MIND_CONTROL -> 5
        MainSystem.SortingType.CLOAKING -> 6
        MainSystem.SortingType.ARTILLERY -> 7
        MainSystem.SortingType.ENGINES -> 8
        MainSystem.SortingType.OXYGEN -> 9
        MainSystem.SortingType.MEDBAY -> 10
        MainSystem.SortingType.CLONEBAY -> 11
    }

    /**
     * Free up [needed] bars of reactor power for a weapon by powering down
     * the support systems ([POWER_DONORS]), least important first. Oxygen is
     * never touched, so the crew don't suffocate over a long storm fight.
     *
     * Nothing is changed unless enough power can be freed. Returns true if
     * the power is now available.
     */
    private fun freePowerFor(needed: Int): Boolean {
        if (ship.powerAvailable >= needed)
            return true

        val donors = ship.mainSystems
            .filter { it.sortingType in POWER_DONORS && !it.isPowerLocked && it.powerSelected > 0 }
            .sortedByDescending { powerPriority(it) }

        // Only the power these systems are actually being supplied with
        // goes back to the reactor (in a storm some of it may be unmet
        // demand).
        val freeable = donors.sumOf { min(it.powerSelected, it.powerSupplied) }
        if (ship.powerAvailable + freeable < needed)
            return false

        for (sys in donors) {
            while (sys.powerSelected > 0 && ship.powerAvailable < needed) {
                val before = sys.powerSelected
                sys.decreasePower()
                if (sys.powerSelected == before)
                    break
            }
        }
        return ship.powerAvailable >= needed
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

    companion object {
        private val POWER_DONORS = setOf(
            MainSystem.SortingType.CLONEBAY,
            MainSystem.SortingType.MEDBAY,
            MainSystem.SortingType.ENGINES,
            MainSystem.SortingType.ARTILLERY,
            MainSystem.SortingType.CLOAKING,
        )
    }
}

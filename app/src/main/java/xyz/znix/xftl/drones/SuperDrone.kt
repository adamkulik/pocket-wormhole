package xyz.znix.xftl.drones

import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * A drone spawned by the flagship's stage-2 Power Surge.
 *
 * These are vanilla's `ShipManager::superDrones`: from FTLGame.exe 1.6.14,
 * `ShipManager::PrepareSuperDrones` (0x004a5b00) creates seven ordinary
 * combat drones, each randomly picked between `COMBAT_1` and `COMBAT_BEAM`,
 * with `powerRequired = 0` and `lifespan = 2`. Every surge re-arms the
 * first 4/6/7 of them (easy/normal/hard) by setting `lifespan = 2` again;
 * a drone disappears once it has taken its two shots.
 *
 * They're an independent hazard: they use no reactor power and no drone
 * parts, and hacking or destroying the drone system has no effect on them
 * (FTL wiki, The Rebel Flagship - "Hacking or destroying the drone system
 * does not affect the power surge drones"). So they don't occupy a slot in
 * the ship's Drones system.
 */
class SuperDrone(type: DroneBlueprint) : CombatDrone(type) {
    /**
     * The number of shots this drone has left before it disappears.
     * Vanilla's `SpaceDrone::lifespan`, re-armed to 2 on every surge.
     */
    var shotsRemaining: Int = SHOTS_PER_SURGE
        private set

    // The surge is independent of the drone system, so drone-system
    // hacking (and ion stun) must never disable these drones.
    override val isStunned: Boolean get() = false

    override fun onFiredAtTarget() {
        // Vanilla counts *attempts*: if the target is cloaked the drone
        // still positions for a shot, which uses up one of its two
        // (FTL wiki: "If you are cloaked, they will position for taking a
        // shot, and that will count as one of their allowed two shots").
        shotsRemaining--
        if (shotsRemaining > 0)
            return

        // No DroneInfo backs a surge drone, so destroy() won't set a
        // Drones-system cooldown - it plays the small explosion and
        // removes the drone from the ship it's circling.
        destroy()
        ownerShip.superDrones.remove(this)
    }

    companion object {
        const val SHOTS_PER_SURGE: Int = 2
    }
}

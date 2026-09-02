package xyz.znix.xftl.devutil

class DebugFlagManager {
    val showProjectileHitboxes = DebugFlag(
        "phb", "Projectile HitBoxes", true,
        "Draw a red circle around projectile hitboxes (which are actually circles)"
    )

    val showHardpoints = DebugFlag(
        "showhp", "Show hardpoints", true,
        "Draw a red marker on all player and enemy weapon mounts"
    )

    val showRoomNumbers = DebugFlag(
        "srn", "Show room numbers", true,
        "Draw the room ID of each room, which is useful for understanding debugger data"
    )

    val showFireTimers = DebugFlag(
        "sft", "Show fire timers", true,
        "Show the timers for fire spread, extinguishing, and oxygen deprivation."
    )

    val showBeamVectors = DebugFlag(
        "sbv", "Show beam/shield vectors", true,
        "Show the vectors representing the beam/shield contact"
    )

    val showSunTimer = DebugFlag(
        "sst", "Show sun/pulsar/asteroid timer", true,
        "Show how much time is left until the next solar flare/ion pulse"
    )

    val showMapGrid = DebugFlag(
        "smg", "Show the grid in the beacon map", true,
        "Show the 6x4 grid that all the beacons in the jump map fit within"
    )

    val showEverything = DebugFlag(
        "se", "Show everything (max level sensors)", true,
        "Equivalent to manned max-level sensors"
    )

    val fastWeaponCharge = DebugFlag(
        "fwc", "Fast Weapon Charge", false,
        "Weapons charge at ten times their normal speed"
    )

    val noEnemyFire = DebugFlag(
        "noef", "No Enemy weapon Firing", false,
        "The enemy ship can't fire their weapons"
    )

    val noDmg = DebugFlag(
        "nodmg", "No Damage", false,
        "Ships don't take damage"
    )

    val noCrewDamage = DebugFlag(
        "nocd", "No crew damage", false,
        "Disable all crew damage"
    )

    val noIon = DebugFlag(
        "noi", "No ion damage", false,
        "Auto-clear ion damage, including system cooldowns"
    )

    val continuousSaveLoad = DebugFlag(
        "cont-save-load", "Continuous save and load", false,
        "Save and load the game state every update, to test the serialisation logic"
    )

    // This is useful for working on the clonebay UI
    val noClone = DebugFlag(
        "noclone", "Don't clone or kill crew", false,
        "When the clonebay finishes it's clone or kill animation, don't do anything."
    )

    val anyJump = DebugFlag(
        "aj", "Any Jumps", false,
        "Allow the player to jump to any beacon or sector in the map"
    )

    val noTeleportLimit = DebugFlag(
        "notl", "No enemy teleport limit", false,
        "Remove the three-teleport limit from AI ships sending boarders"
    )

    val infiniteMissiles = DebugFlag(
        "infm", "Infinite Missiles", false,
        "Ships (most importantly enemy ships) don't subtract missiles when they fire things."
    )

    val infiniteDrones = DebugFlag(
        "infd", "Infinite Drones", false,
        "Ships (most importantly enemy ships) have infinite drones."
    )

    val all = listOf(
        showProjectileHitboxes,
        showHardpoints,
        showRoomNumbers,
        showFireTimers,
        showBeamVectors,
        showSunTimer,
        showMapGrid,
        showEverything,
        fastWeaponCharge,
        noEnemyFire,
        noDmg,
        noCrewDamage,
        noIon,
        continuousSaveLoad,
        noClone,
        anyJump,
        noTeleportLimit,
        infiniteMissiles,
        infiniteDrones
    )

    class DebugFlag(val shortName: String, val fullName: String, val isVisual: Boolean, val description: String) {
        var set = false
    }
}

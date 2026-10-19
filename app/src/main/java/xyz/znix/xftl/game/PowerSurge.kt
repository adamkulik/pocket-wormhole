package xyz.znix.xftl.game

import xyz.znix.xftl.Ship
import xyz.znix.xftl.TWO_PI
import xyz.znix.xftl.drones.SuperDrone
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.LaserBlueprint
import xyz.znix.xftl.weapons.PowerSurgeLaserProjectile
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Rebel Flagship's Power Surge (GitHub issue #15).
 *
 * Stage 2 deploys a swarm of combat/beam drones (GitHub issue #176), while
 * stage 3 fires a seven-laser barrage and restores its Zoltan shield on
 * every fourth surge.
 *
 * Vanilla behaviour pinned from FTLGame.exe 1.6.14:
 * - `BossShip` owns a repeating timer (`TimerHelper` at +0x138) with a
 *   min of 20000 ms and a max of 30000 ms (BossShip constructor 0x00406250:
 *   max=30000, min=20000, duration=30.0 s, repeat=true). `StartStage`
 *   (0x00406980) starts it, rolling a new 20-30 s delay; every expiry
 *   re-rolls another 20-30 s (FUN_005cb200).
 * - `BossShip::OnLoop` (0x00407d40) calls `ShipManager::PrepareSuperDrones`
 *   (0x004a5b00) whenever the timer expires and `currentStage == 2`.
 * - `PrepareSuperDrones` creates seven combat drones, each randomly
 *   `COMBAT_1` or `COMBAT_BEAM` (50/50), with `powerRequired = 0` and
 *   `lifespan = 2`, and every surge re-arms (and re-deploys) the first
 *   4/6/7 of them on easy/normal/hard. The drones persist between surges,
 *   which is why the beam/combat split is fixed for the fight.
 * - The surge drones take two shots each and then disappear, and are an
 *   independent hazard: no reactor power, no drone parts, and neither
 *   hacking nor destroying the drone system affects them (FTL wiki, The
 *   Rebel Flagship - Power Surge).
 * - A warning sounds exactly five seconds before each surge
 *   (FTL wiki; the sound is `surgeWarning` = ui/bp_SFX_FlagshipPowerSurge
 *   Warning.ogg, registered next to the `warning_power_surge` banner
 *   "POWER SURGE DETECTED" in the warning-UI constructor FUN_005005d0).
 *
 * The split is re-rolled whenever a new [PowerSurge] is created, i.e. each
 * time the player arrives at the stage (the wiki: the split stays fixed
 * "unless you jump away and come back").
 */
open class PowerSurge(
    private val game: InGameState,
    private val ship: Ship,
    private val stage: Int = DRONE_SURGE_STAGE,
) {
    /**
     * The seven drone types that make up this fight's surge, in vanilla's
     * creation order. Only the first [droneCount] are deployed each surge.
     */
    val droneTypeNames: List<String> = List(SURGE_DRONE_POOL) {
        if (Random.nextBoolean()) COMBAT_NAME else BEAM_NAME
    }

    /** The number of drones deployed per surge, for this game's difficulty. */
    val droneCount: Int = droneCountFor(game.difficulty)

    private var timer: Float = rollDelay()

    /**
     * How many surges have fired during this stage - vanilla's
     * `BossShip::powerCount`, which `StartStage` resets to zero. Only stage
     * 3 reads it: every fourth surge restores the Zoltan shield.
     */
    private var surgeCount: Int = 0

    /** True while the five-second warning banner/alarm is showing. */
    var isWarningActive: Boolean = false
        private set

    private var warningPlayed = false

    /** Seconds until the next surge (exposed for the test kit). */
    val timeUntilSurge: Float get() = timer

    private var font: xyz.znix.xftl.SILFontLoader? = null

    /**
     * Advance the surge timer. Only called while this ship is the active
     * enemy and the game isn't paused.
     */
    fun update(dt: Float) {
        if (ship.isDead)
            return

        timer -= dt

        if (!warningPlayed && timer <= WARNING_TIME) {
            warningPlayed = true
            isWarningActive = true
            game.sounds.getSampleOrWarn(WARNING_SOUND)?.play()
        }

        if (timer <= 0f) {
            deploySurge()
            isWarningActive = false
            warningPlayed = false
            timer = rollDelay()
        }
    }

    private fun deploySurge() {
        if (stage == BARRAGE_SURGE_STAGE) {
            // `BossShip::OnLoop` (0x00407d40): `powerCount % 4 == 3` calls
            // `PrepareSuperShield` (0x0049cb50), otherwise
            // `PrepareSuperBarrage` (0x004a6a90), then powerCount++.
            // So surges 1-3 are barrages and the 4th restores the shield,
            // repeating every four surges.
            if (surgeCount % SURGES_PER_SHIELD_RESTORE == SURGES_PER_SHIELD_RESTORE - 1) {
                restoreSuperShield()
            } else {
                fireSuperBarrage()
            }
            surgeCount++
            return
        }

        for (name in droneTypeNames.take(droneCount))
            deploySurgeDrone(name)
    }

    /**
     * Deploy a single surge drone (by blueprint name). This is a seam for
     * the test kit, which can't load the drone sprites - the real version
     * creates a live drone.
     */
    open fun deploySurgeDrone(name: String) {
        val blueprint = game.blueprintManager[name] as DroneBlueprint
        val drone = SuperDrone(blueprint)
        drone.init(ship)
        drone.isPowered = true
        ship.superDrones.add(drone)
    }

    /**
     * Fire the stage-3 laser barrage - vanilla's
     * `ShipManager::PrepareSuperBarrage` (0x004a6a90).
     *
     * Seven `LASER_HEAVY_1` lasers are created at the flagship's centre,
     * each aimed at a random room of the player's ship, with a random
     * launch heading (the spray direction in the flagship's space) and a
     * random entry angle into the player's space. Their damage is
     * hard-coded to 1 by [PowerSurgeLaserProjectile], while the status
     * chances stay the Heavy Laser Mk I's (30% fire / 21% effective
     * breach / 20% stun).
     */
    protected open fun fireSuperBarrage() {
        val targetShip = game.getEnemyOf(ship) ?: return
        if (targetShip.rooms.isEmpty())
            return

        val blueprint = game.blueprintManager[WEAPON_BLUEPRINT] as LaserBlueprint
        val spawnPos = ConstPoint(ship.hullCentreX.toInt(), ship.hullCentreY.toInt())

        repeat(BARRAGE_LASERS) {
            val target = targetShip.rooms.random()
            val projectile = PowerSurgeLaserProjectile(blueprint, target)

            // Vanilla randomises both: `heading` (the exe sets
            // `projectile->heading = random() % 360`) is the direction the
            // shot sprays out of the flagship's centre, and the entry
            // angle into the destination space is chosen when it arrives.
            projectile.entryAngle = Random.nextFloat() * TWO_PI
            val heading = Random.nextFloat() * TWO_PI
            val endPos = ConstPoint(
                spawnPos.x + (cos(heading) * BARRAGE_SPRAY_DISTANCE).toInt(),
                spawnPos.y + (sin(heading) * BARRAGE_SPRAY_DISTANCE).toInt(),
            )
            projectile.setInitialPath(spawnPos, endPos)

            ship.projectiles.add(projectile)
        }
    }

    /**
     * Restore the flagship's Zoltan shield - stage 3's every-fourth surge.
     *
     * Vanilla's `PrepareSuperShield` (0x0049cb50) sets both the current and
     * maximum super-shield to 12; the maximum is already 12 on a stage-3
     * ship (see `FlagshipBoss.applyStageSuperShield`).
     */
    protected open fun restoreSuperShield() {
        ship.superShield = ship.maxSuperShield
    }

    /**
     * Draw the vanilla "POWER SURGE DETECTED" warning banner. The text and
     * alarm are exe-pinned; the placement/font mirror the ASB warning
     * banner, which is drawn by the same vanilla warning-UI object.
     */
    fun renderWarning(g: Graphics) {
        if (!isWarningActive)
            return

        val font = font ?: game.getFont(BANNER_FONT).also { font = it }
        val text = game.translator.get("warning_power_surge")
        val lines = text.split("\n")

        font.scale = BANNER_SCALE
        val lineSpacing = font.lineSpacing + 6
        var y = BANNER_BASE_Y
        for (line in lines) {
            font.drawStringCentred(BANNER_X.toFloat(), y.toFloat(), 0f, line, BANNER_COLOUR)
            y += lineSpacing
        }
        font.scale = 1f
    }

    private fun rollDelay(): Float {
        // Vanilla: rand() % (30000 - 20000) + 20000 milliseconds.
        return Random.nextInt(DELAY_MIN_MS, DELAY_MAX_MS) / 1000f
    }

    companion object {
        /** Stage 2's Power Surge deploys drones. */
        const val DRONE_SURGE_STAGE: Int = 2

        /** Stage 3's Power Surge fires the laser barrage/shield restore. */
        const val BARRAGE_SURGE_STAGE: Int = 3

        /** Stage 3's every-fourth surge restores the Zoltan shield. */
        const val SURGES_PER_SHIELD_RESTORE: Int = 4

        /** Stage 3's barrage fires seven heavy lasers (exe 0x004a6a90). */
        const val BARRAGE_LASERS: Int = 7

        /** The blueprint the stage-3 barrage lasers use (hard-coded in the exe). */
        const val WEAPON_BLUEPRINT: String = "LASER_HEAVY_1"

        // The intermediate path distance used to make the barrage shots
        // spray out of the flagship before switching to the player's space
        // (the same technique AbstractProjectileWeaponInstance uses).
        private const val BARRAGE_SPRAY_DISTANCE = 5000

        // Vanilla's superDrones pool is always seven drones; the
        // difficulty only decides how many of them are armed.
        const val SURGE_DRONE_POOL: Int = 7

        const val COMBAT_NAME = "COMBAT_1"
        const val BEAM_NAME = "COMBAT_BEAM"

        const val WARNING_TIME = 5f

        private const val DELAY_MIN_MS = 20_000
        private const val DELAY_MAX_MS = 30_000

        private const val WARNING_SOUND = "surgeWarning"

        // Same banner layout as the ASB lock-on warning (ASBController),
        // which comes from the same vanilla warning UI.
        private const val BANNER_FONT = "HL1"
        private const val BANNER_SCALE = 2f
        private const val BANNER_X = 640
        private const val BANNER_BASE_Y = 130
        private val BANNER_COLOUR = Colour(255, 90, 60, 255)

        /** 4 drones on easy, 6 on normal, 7 on hard (exe 0x004a5b00). */
        fun droneCountFor(difficulty: Difficulty): Int {
            return when (difficulty) {
                Difficulty.EASY -> 4
                Difficulty.NORMAL -> 6
                Difficulty.HARD -> 7
            }
        }
    }
}

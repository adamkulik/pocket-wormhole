package xyz.znix.xftl.game

import xyz.znix.xftl.Ship
import xyz.znix.xftl.drones.SuperDrone
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.random.Random

/**
 * The Rebel Flagship's stage-2 Power Surge (GitHub issue #176).
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
open class PowerSurge(private val game: InGameState, private val ship: Ship) {
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

package xyz.znix.xftl.environment

import org.jdom2.Element
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.random
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rollChance
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.Damage
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

class SunEnvironment(game: InGameState, beacon: Beacon) : AbstractEnvironment(game, beacon) {
    override val type: Beacon.EnvironmentType get() = Beacon.EnvironmentType.SUN

    private val realBackground = game.getImg("img/stars/bg_dullstars.png")

    private val sunImage = game.getImg("img/stars/planet_sun1.png")
    private val glowImage = game.getImg("img/stars/planet_sun2.png")
    private val flareImage = game.getImg("img/stars/planet_sun_flare.png")

    private val warningSound = game.sounds.getSample("environWarning")
    private val flareSound = game.sounds.getSample("solarFlare")

    // Time since we jumped into the level
    private var time = 0f

    private var nextFlareTime = 0f
    private var lastFlareTime = -100f // Don't show a flare as we jump in
    private var hasPlayedSound = false
    private var hasPlayedWarning = false

    val showWarning: Boolean get() = (nextFlareTime - time) < 5f

    init {
        prepareNextFlare()
    }

    override fun renderBackground(gc: GameContainer, g: Graphics) {
        val ps = pauseShade(game)

        // This draws the normal background with all its adjustments
        realBackground.draw(0f, 0f, ps)

        val backGlow = alphaCycle(7f, 0.1f, 1f)
        val frontGlow = alphaCycle(10f, 0.05f, 0.4f)
        val sunOpacity = alphaCycle(5f, 0.7f, 1f)

        glowImage.draw(
            0f, 0f,
            1000f, 1000f,
            Colour(ps.r, ps.g, ps.b, backGlow)
        )

        g.pushTransform()
        g.translate(100f, 109f)
        g.rotate(sunImage.width / 2f, sunImage.height / 2f, 132f)
        sunImage.draw(
            0f, 0f,
            Colour(ps.r, ps.g, ps.b, sunOpacity)
        )
        g.popTransform()

        glowImage.draw(
            0f, 0f,
            1000f, 1000f,
            Colour(1f, 1f, 1f, frontGlow)
        )

        if (game.debugFlags.showSunTimer.set) {
            game.getFont("HL2", 2f).drawString(
                550f, 20f,
                "Flare in " + (nextFlareTime - time),
                Colour.white
            )
        }
    }

    override fun renderOverlay(gc: GameContainer, g: Graphics) {
        // Find how long it's been since/until the middle of this flare
        val timeFromLast = time - lastFlareTime
        val timeToNext = nextFlareTime - time
        val flareTimer = min(timeFromLast, timeToNext)

        // The flare is 1sec long in total, 0.5sec on either side.
        val progress = 1 - flareTimer / 0.5f
        if (progress < 0)
            return

        // The alpha is the cube of how far we are from the flare
        val alpha = progress.pow(3)

        val filter = Colour(Constants.SOLAR_FLARE_FILTER)
        filter.a = alpha
        flareImage.draw(
            -360f, -640f,
            flareImage.width * 4f, flareImage.height * 4f,
            filter
        )
    }

    override fun update(dt: Float) {
        super.update(dt)

        time += dt

        val timeToFlare = nextFlareTime - time
        if (timeToFlare < 1.2f && !hasPlayedSound) {
            flareSound.play()
            hasPlayedSound = true
        }
        if (showWarning && !hasPlayedWarning) {
            warningSound.play()
            hasPlayedWarning = true
        }
        if (timeToFlare < 0f) {
            lastFlareTime = nextFlareTime

            dealFlareDamage(game.player)
            game.enemy?.let { dealFlareDamage(it) }
            prepareNextFlare()
        }
    }

    private fun alphaCycle(period: Float, min: Float, max: Float): Float {
        // Triangle wave
        val progress = time.rem(period) / period
        val changing = when {
            progress < 0.5f -> progress * 2
            else -> 2 - progress * 2
        }

        return min + changing * (max - min)
    }

    private fun prepareNextFlare() {
        nextFlareTime = time + (28f..34f).random(Random)
        hasPlayedSound = false
        hasPlayedWarning = false
    }

    private fun dealFlareDamage(ship: Ship) {
        val normalShieldUp = ship.shields?.activeShields?.let { it > 0 } ?: false
        val hasShields = ship.superShield > 0 || normalShieldUp

        var numFires = when (hasShields) {
            true -> (1..2).random()
            false -> (3..6).random()
        }

        val damagedRooms = HashSet<Room>()

        while (numFires > 0) {
            val room = ship.rooms.random()

            // Start 1 or 2 fires, if we have enough remaining
            var numStarted = 1
            room.spawnFire()
            if (numFires > 1 && Random.rollChance(50)) {
                room.spawnFire()
                numStarted++
            }

            // 33%/66% chance for damage with 1/2 fires. If we do damage
            // a room and randomly select it again, we can't damage it twice.
            if (Random.rollChance(numStarted * 33) && !damagedRooms.contains(room)) {
                damagedRooms.add(room)

                // Note no crew damage
                ship.damage(room, Damage.hullAndSys(1))
            }

            numFires -= numStarted
        }
    }

    override fun saveToXML(elem: Element) {
        super.saveToXML(elem)

        SaveUtil.addAttrFloat(elem, "sunTimer", time)
        SaveUtil.addAttrFloat(elem, "nextFlare", nextFlareTime)
        SaveUtil.addAttrFloat(elem, "lastFlare", lastFlareTime)
    }

    override fun loadFromXML(elem: Element) {
        super.loadFromXML(elem)

        time = SaveUtil.getAttrFloat(elem, "sunTimer")
        nextFlareTime = SaveUtil.getAttrFloat(elem, "nextFlare")
        lastFlareTime = SaveUtil.getAttrFloat(elem, "lastFlare")
        hasPlayedSound = (nextFlareTime - time) < 1.2f
        hasPlayedWarning = showWarning
    }
}

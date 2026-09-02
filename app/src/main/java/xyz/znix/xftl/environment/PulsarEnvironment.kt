package xyz.znix.xftl.environment

import org.jdom2.Element
import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.random
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.systems.SubSystem
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

class PulsarEnvironment(game: InGameState, beacon: Beacon) : AbstractEnvironment(game, beacon) {
    override val type: Beacon.EnvironmentType get() = Beacon.EnvironmentType.PULSAR

    private val realBackground = game.getImg("img/stars/bg_dullstars.png")

    private val sunBlack = game.getImg("img/effects/pulsar_black.png")
    private val sunWhite = game.getImg("img/effects/pulsar_white.png")
    private val waveBack = game.getImg("img/effects/pulsar_backL.png")
    private val waveFront = game.getImg("img/effects/pulsar_frontL.png")

    // This is white, and is filtered differently for the sun and pulsar
    private val flareImage = game.getImg("img/stars/planet_sun_flare.png")

    private val warningSound = game.sounds.getSample("environWarning")
    private val pulseSound = game.sounds.getSample("pulsar")

    // Time since we jumped into the level
    private var animationTimer = 0f

    private var timeToPulse = 0f
    private var pulseTimer: Float? = null
    private var hasPlayedSound = false
    private var hasPlayedWarning = false

    val showWarning: Boolean
        get() = timeToPulse < 5f

    init {
        prepareNextFlare()
    }

    override fun renderBackground(gc: GameContainer, g: Graphics) {
        val ps = pauseShade(game)

        // This draws the normal background with all its adjustments
        realBackground.draw(0f, 0f, ps)

        val whiteAlpha = alphaCycle(7f, 0.1f, 1f)

        val centreX = 463
        val centreY = 203

        val waveAlpha = pulseTimer?.let { 1 - it }
        val waveFilter = waveAlpha?.let { Colour(ps.r, ps.g, ps.b, it) }
        val waveScale = (pulseTimer ?: 0f) * 3

        if (waveFilter != null) {
            waveBack.drawAlignedCentred(
                centreX, centreY,
                waveBack.width * waveScale,
                waveBack.height * waveScale,
                waveFilter
            )
        }

        sunBlack.drawAlignedCentred(centreX, centreY, ps)
        val filter = Colour(ps.r, ps.g, ps.b, whiteAlpha)
        sunWhite.drawAlignedCentred(centreX, centreY, filter)

        if (waveFilter != null) {
            waveFront.drawAlignedCentred(
                centreX, centreY,
                waveBack.width * waveScale,
                waveBack.height * waveScale,
                waveFilter
            )
        }

        if (game.debugFlags.showSunTimer.set) {
            game.getFont("HL2", 2f).drawString(
                550f, 20f,
                "Pulse in $timeToPulse",
                Colour.white
            )
        }
    }

    override fun renderOverlay(gc: GameContainer, g: Graphics) {
        val pulseTimer = pulseTimer ?: return

        // Find how long it's been since/until the middle of this flare
        val middleTimer = abs(pulseTimer - 0.5f)

        // The flare is 1sec long in total, 0.5sec on either side.
        val progress = 1 - middleTimer / 0.5f

        // The alpha is the cube of how far we are from the flare
        val alpha = progress.pow(3)

        val filter = Colour(Constants.PULSAR_PULSE_FILTER)
        filter.a = alpha
        flareImage.draw(
            -360f, -640f,
            flareImage.width * 4f, flareImage.height * 4f,
            filter
        )
    }

    override fun update(dt: Float) {
        super.update(dt)

        animationTimer += dt
        timeToPulse -= dt

        val oldPT = pulseTimer
        if (oldPT != null) {
            val newPT = oldPT + dt
            if (newPT > 1f) {
                pulseTimer = null
            } else {
                pulseTimer = newPT
            }
        }

        if (timeToPulse < 1.2f && !hasPlayedSound) {
            pulseSound.play()
            hasPlayedSound = true
        }
        if (timeToPulse < 0.5f && pulseTimer == null) {
            pulseTimer = 0f
        }
        if (timeToPulse < 0f) {
            dealIonDamage(game.player)
            game.enemy?.let { dealIonDamage(it) }
            prepareNextFlare()
        }

        if (showWarning && !hasPlayedWarning) {
            warningSound.play()
            hasPlayedWarning = true
        }
    }

    private fun alphaCycle(period: Float, min: Float, max: Float): Float {
        // Triangle wave
        val progress = animationTimer.rem(period) / period
        val changing = when {
            progress < 0.5f -> progress * 2
            else -> 2 - progress * 2
        }

        return min + changing * (max - min)
    }

    private fun prepareNextFlare() {
        timeToPulse = (11f..18f).random(Random)
        hasPlayedSound = false
        hasPlayedWarning = false
    }

    private fun dealIonDamage(ship: Ship) {
        val ionArmour = game.blueprintManager["ION_ARMOR"] as AugmentBlueprint
        val count = ship.augments.count { it == ionArmour }
        val armourAmount = ionArmour.value * count
        val resistDamage = armourAmount > Random.nextFloat()

        // Super-shields block all incoming damage
        if (ship.superShield > 0 && !resistDamage) {
            ship.superShield -= (3..4).random()
            return
        }

        // Always attack shields if they're up
        val shields = ship.shields
        if (shields != null && shields.powerSelected > 0) {
            dealSystemIon(shields, resistDamage)
        } else {
            dealSystemIon(ship.systems.random(), resistDamage)
        }

        dealSystemIon(ship.systems.random(), resistDamage)
    }

    private fun dealSystemIon(system: AbstractSystem, resist: Boolean) {
        val currentPower = when (system) {
            is MainSystem -> system.powerSelected
            else -> (system as SubSystem).effectivePower
        }

        val damage = 1 + (currentPower / 2f).toInt()

        if (resist) {
            system.room!!.showDamageText("text_resist", Colour.white)
        } else {
            system.dealDamage(0, damage)
            system.room!!.showDamageText(damage, Constants.DAMAGE_COLOUR_ION)
        }
    }

    override fun saveToXML(elem: Element) {
        super.saveToXML(elem)

        // Don't bother saving the glow animation progress
        SaveUtil.addAttrFloat(elem, "nextPulse", timeToPulse)
        SaveUtil.addTagFloat(elem, "pulseTimer", pulseTimer, null)
    }

    override fun loadFromXML(elem: Element) {
        super.loadFromXML(elem)

        timeToPulse = SaveUtil.getAttrFloat(elem, "nextPulse")
        pulseTimer = SaveUtil.getOptionalTagFloat(elem, "pulseTimer")

        hasPlayedSound = timeToPulse < 1.2f
        hasPlayedWarning = showWarning
    }
}

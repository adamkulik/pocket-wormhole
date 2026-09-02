package xyz.znix.xftl.environment

import xyz.znix.xftl.*
import xyz.znix.xftl.game.EnergySource
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.ReactorEnergySource
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sys.GameContainer
import kotlin.random.nextInt

class NebulaEnvironment(game: InGameState, beacon: Beacon, val ionStorm: Boolean) : AbstractEnvironment(game, beacon) {
    override val type: Beacon.EnvironmentType
        get() = when (ionStorm) {
            true -> Beacon.EnvironmentType.ION_STORM
            false -> Beacon.EnvironmentType.NEBULA
        }

    private val clouds = ArrayList<Cloud>()

    private val normalImage = game.getImg("img/stars/nebula_large_c.png")
    private val stormImage = game.getImg("img/stars/nebula_large_b.png")
    private val stormFlashImage = game.getImg("img/stars/nebula_large_a.png")
    private val lightningImage = game.getImg("img/stars/lightning.png")

    init {
        for (xIdx in 0 until 5) {
            for (yIdx in 0 until 4) {
                val pos = ConstPoint(
                    xIdx * 350 + VisualRandom.nextInt(-60..60),
                    yIdx * 230 + VisualRandom.nextInt(-40..40)
                )
                clouds += Cloud(pos)
            }
        }

        // Don't use a left-to-right order, as this determines which clouds
        // are drawn on over each other.
        clouds.shuffle()

        // Do a few initial updates so the clouds start in a steady state
        for (i in 0 until 50) {
            update(0.5f)
        }
    }

    override fun renderBackground(gc: GameContainer, g: Graphics) {
        for (cloud in clouds) {
            g.pushTransform()
            g.translate(cloud.centre.x.f, cloud.centre.y.f)
            g.scale(cloud.scale, cloud.scale)

            val lightning = cloud.lightingTimer > 0f && ionStorm
            val cloudImage = when {
                lightning -> stormFlashImage
                ionStorm -> stormImage
                else -> normalImage
            }

            val filter = pauseShade(game)
            filter.a = cloud.alpha
            cloudImage.draw(-cloudImage.width / 2, -cloudImage.height / 2, filter)

            // Draw the lightning image on top
            if (lightning) {
                g.rotateRadians(0f, 0f, cloud.lightningRotation)
                lightningImage.draw(-lightningImage.width / 2, -lightningImage.height / 2, filter)
            }

            g.popTransform()
        }
    }

    override fun update(dt: Float) {
        super.update(dt)

        // Use indices so we can add new clouds
        var offset = 0
        for (idx in clouds.indices) {
            val cloud = clouds[idx + offset]
            cloud.update(dt)

            // Does this cloud need to be replaced?
            if (!cloud.isFadingIn && !cloud.isReplaced && cloud.alpha < cloud.replaceThreshold) {
                cloud.isReplaced = true

                clouds.add(0, Cloud(cloud.centre))
                offset++
            }
        }

        clouds.removeIf { it.alpha == 0f && !it.isFadingIn }
    }

    override fun adjustShipPower(ship: Ship, powerAvailableTypes: HashMap<EnergySource, Int>) {
        if (!ionStorm)
            return

        var reactorPower = powerAvailableTypes[ReactorEnergySource] ?: 0
        val toDeduct = reactorPower / 2 // Rounds down
        reactorPower -= toDeduct
        powerAvailableTypes[ReactorEnergySource] = reactorPower
    }

    private class Cloud(
        val centre: IPoint
    ) {
        var alpha = 0f
        var scale = (3f..4f).random(VisualRandom)

        var isFadingIn: Boolean = true
        var isReplaced: Boolean = false

        var nextLightning = 0f
        var lightingTimer = 0f
        var lightningRotation = 0f

        val replaceThreshold = (0.60f..0.90f).random(VisualRandom)

        fun update(dt: Float) {
            scale += 0.048f * dt

            if (isFadingIn) {
                alpha += 0.80f * dt
                if (alpha >= 1f) {
                    isFadingIn = false
                }
            } else if (isReplaced) {
                alpha -= 0.16f * dt
            } else {
                alpha -= 0.032f * dt
            }

            // Always run the lightning code, and just don't render it if
            // we're not in a plasma storm.
            nextLightning -= dt
            if (nextLightning <= 0) {
                lightingTimer = 0.25f
                nextLightning = (6.25f..18.75f).random(VisualRandom)
                lightningRotation = (0..3).random() * (PIf / 2f)
            }
            lightingTimer = (lightingTimer - dt).coerceAtLeast(0f)

            alpha = alpha.coerceIn(0f..1f)
        }
    }

    // Don't bother serialising the clouds, since they're only visual.
}

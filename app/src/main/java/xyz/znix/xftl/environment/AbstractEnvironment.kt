package xyz.znix.xftl.environment

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.EnergySource
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.EnvironmentImage
import xyz.znix.xftl.sector.FleetBackground
import xyz.znix.xftl.sector.ImageList
import xyz.znix.xftl.sys.GameContainer
import kotlin.math.roundToInt
import kotlin.random.Random

abstract class AbstractEnvironment(val game: InGameState, val beacon: Beacon) {
    abstract val type: Beacon.EnvironmentType

    private var backgroundImage: EnvironmentImage? = null
    private var planetImage: EnvironmentImage? = null
    private val backgroundShips = ArrayList<BackgroundShip>()

    init {
        var backgroundList: ImageList = game.eventManager.getImageList("BACKGROUND")
        var planetList: ImageList = game.eventManager.getImageList("PLANET")

        beacon.event.backImg?.let { backgroundList = it }
        beacon.event.planetImg?.let { planetList = it }

        val rand = Random(beacon.environmentSeed)

        backgroundImage = backgroundList.getRandom(rand.nextInt())
        planetImage = planetList.getRandom(rand.nextInt())

        backgroundShips.clear()
        val fleet = beacon.event.fleetBackground
        if (fleet != null) {
            initFleet(fleet, rand)
        }
    }

    private fun initFleet(fleet: FleetBackground, rand: Random) {
        val rebelShips = listOf(
            game.getImg("img/ship/fleet/fleet_1_small.png"),
            game.getImg("img/ship/fleet/fleet_2_small.png"),
            game.getImg("img/ship/fleet/fleet_1_med.png"),
            game.getImg("img/ship/fleet/fleet_2_med.png")
        )
        val federationShips = listOf(
            game.getImg("img/ship/fleet/fleetfed_1_small.png"),
            game.getImg("img/ship/fleet/fleetfed_2_small.png"),
            game.getImg("img/ship/fleet/fleetfed_1_med.png"),
            game.getImg("img/ship/fleet/fleetfed_2_med.png")
        )

        fun addShip(row: Int, column: Int, fed: Boolean) {
            val image = when (fed) {
                true -> federationShips.random(rand)
                else -> rebelShips.random(rand)
            }

            backgroundShips.add(BackgroundShip(image, column, row, rand.nextFloat(), rand.nextFloat()))
        }

        for (x in 0 until 3) {
            for (y in 0 until 3) {
                val isFed = when (fleet) {
                    FleetBackground.REBEL -> false
                    FleetBackground.FEDERATION -> true
                    FleetBackground.BOTH -> when (x) {
                        0 -> false
                        2 -> true
                        else -> rand.nextBoolean()
                    }
                }
                addShip(y, x, isFed)
            }
        }
    }

    open fun pauseShade(game: InGameState): Colour {
        val s = game.player // s for ship
        val paused = game.isPaused
        val shade = if (paused) (1.0f - s.health.toFloat() / s.maxHealth.toFloat()) * 0.4f + 0.4f else 1.0f

        return Colour(shade, shade, shade)
    }

    open fun renderBackground(gc: GameContainer, g: Graphics) {
        // Here the background scenery is rendered with a
        // pause-darkness that depends on the hull health.
        val ps = pauseShade(game)
        backgroundImage?.getImg(game)?.draw(0f, 0f, ps)
        planetImage?.getImg(game)?.draw(0f, 0f, ps)

        // Background rebel/federation ships live on a 3x3 grid
        // We can't pick a random position within that grid when we populate
        // this list, as we don't know the screen size, so instead we store
        // a float of it's position within that cell in each axis.
        val bgShipColumnWidth = gc.width / 3
        val bgShipRowHeight = gc.height / 3
        for (ship in backgroundShips) {
            val x = ((ship.gridX + ship.offsetX) * bgShipColumnWidth).roundToInt()
            val y = ((ship.gridY + ship.offsetY) * bgShipRowHeight).roundToInt()

            ship.image.drawAlignedCentred(x, y, ps)
        }

        // TODO: Get the big fleet ships working (those are unaffected by the pause darkness)
        // Mods like Multiverse use quite a lot of them with hyperspace,
        // so this will sooner or later need attention. Example image here:
        // https://cdn.discordapp.com/attachments/859493631215534110/1256333357986480220/Bildschirmfoto_2024-06-28_um_21.40.07.png
    }

    /**
     * Render something on top of everything else, including the UI.
     *
     * Used for solar flares and pulsars.
     */
    open fun renderOverlay(gc: GameContainer, g: Graphics) {
    }

    open fun update(dt: Float) {
    }

    /**
     * Adjust a ship's available power, before systems use it.
     *
     * This is how plasma storms reduce a ship's power.
     */
    open fun adjustShipPower(ship: Ship, powerAvailableTypes: HashMap<EnergySource, Int>) {
    }

    open fun saveToXML(elem: Element) {
    }

    open fun loadFromXML(elem: Element) {
    }

    private class BackgroundShip(
        val image: Image,
        val gridX: Int,
        val gridY: Int,
        val offsetX: Float,
        val offsetY: Float
    )
}

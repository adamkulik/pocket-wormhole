package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.DroneBlueprint

class HullRepairDrone(type: DroneBlueprint) : AbstractExternalDrone(type, false) {
    override val flightController = CombatFlightController(this)

    private lateinit var offImage: Image
    private lateinit var onImage: Image

    private lateinit var repairWave: Image

    // Unlike FTL, we don't run the animation when paused.
    // It's unlikely anyone cares, and it's very slightly easier.
    private var animationProgress: Float = 0f

    private var stopTimer: Float = 0f

    // The hull points this drone will provide before being destroyed.
    private var remainingRepairs = (3..5).random()

    override fun onInit() {
        offImage = game.getImg("img/ship/drones/${type.droneImage}_base.png")
        onImage = game.getImg("img/ship/drones/${type.droneImage}_on.png")
        // There's also a charged image, but it's the same as the on image.

        repairWave = game.getImg("img/effects/drone_ship_repair_wave.png")

        flightController.onReachedDestination = this::onReachedDestination
    }

    override fun onRender(g: Graphics) {
        if (isRunning && flightController.paused) {
            val colour = Colour(Colour.white)

            // This animation is guessed and measured from FTL, it's
            // not properly reverse-engineered out.

            val numSteps = 4
            for (i in 0 until numSteps) {
                val progress = i.f / numSteps + (animationProgress / numSteps)

                g.pushTransform()

                g.translate(0f, -100f * progress)

                g.scale(progress, progress)

                colour.a = 1 - progress

                g.rotate(0f, 0f, 90f)
                drawCentred(repairWave, colour)
                g.popTransform()
            }
        }

        val image = if (isRunning) onImage else offImage
        drawCentred(image)
    }

    override fun update(dt: Float) {
        super.update(dt)

        if (!isRunning)
            return

        if (!flightController.paused) {
            animationProgress = 0f
            return
        }

        val duration = 0.2f
        animationProgress = (animationProgress + dt / duration).rem(1f)

        stopTimer -= dt

        if (stopTimer <= 0) {
            targetShip.health++
            flightController.paused = false
            remainingRepairs--

            if (remainingRepairs <= 0) {
                removeInstance()
            }
        }
    }

    private fun onReachedDestination() {
        flightController.paused = true
        stopTimer = 0.5f
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        SaveUtil.addAttrFloat(elem, "animation", animationProgress)
        SaveUtil.addAttrFloat(elem, "stopTimer", stopTimer)
        SaveUtil.addAttrInt(elem, "remainingRepairs", remainingRepairs)
    }

    override fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        super.loadFromXML(elem, refs, containingShip)

        animationProgress = SaveUtil.getAttrFloat(elem, "animation")
        stopTimer = SaveUtil.getAttrFloat(elem, "stopTimer")
        remainingRepairs = SaveUtil.getAttrInt(elem, "remainingRepairs")
    }
}

package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.f
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * The Shield Overcharger drone (vanilla AE: DRONE_SHIELD and the
 * 2-power DRONE_SHIELD_PLAYER Stealth-C variant, both droneType SHIELD).
 *
 * Wiki (Drone Control page): "Periodically adds 1 point of Zoltan Shield",
 * with a cooldown of 8s/10s/13s/16s/20s for 0/1/2/3/4 existing layers, and
 * "each timer resets should the drone become unpowered". The overcharged
 * shields themselves are lost on jumping, which is already how
 * [Ship.resetAfterJump] works.
 */
class ShieldDrone(type: DroneBlueprint) : AbstractExternalDrone(type, false) {
    override val flightController = OrbitFlightController(this)

    private lateinit var offImage: Image
    private lateinit var chargedImage: Image
    private lateinit var glowImage: Image
    private lateinit var engine: Image

    /**
     * Time until the next super-shield point is granted. [UNSTARTED] means
     * the drone hasn't started charging yet - it's initialised on the first
     * update, as [onInit] also runs when deserialising (before loadFromXML
     * has restored the saved timer).
     */
    private var chargeTimer = UNSTARTED

    // How much longer to show the _glow sprite after a grant.
    private var glowTimer = 0f

    override fun onInit() {
        offImage = game.getImg("img/ship/drones/${type.droneImage}_off.png")
        chargedImage = game.getImg("img/ship/drones/${type.droneImage}_charged.png")
        glowImage = game.getImg("img/ship/drones/${type.droneImage}_glow.png")
        engine = game.getImg("img/ship/drones/drone_engine.png")
    }

    private fun chargeDuration(): Float {
        return when (max(targetShip.superShield, 0)) {
            0 -> 8f
            1 -> 10f
            2 -> 13f
            3 -> 16f
            else -> 20f
        }
    }

    override fun onRender(g: Graphics) {
        val image = when {
            !isRunning -> offImage
            glowTimer > 0f -> glowImage
            else -> chargedImage
        }

        // Try to keep everything as an integer to make the sprites sharper.
        drawCentred(image)

        // Draw the little thruster flame, pointing against our direction
        // of travel like the defence drones do.
        if (isRunning) {
            val movementAngle = atan2(flightController.velocity.xf, flightController.velocity.yf)
            g.pushTransform()
            g.rotate(0f, 0f, Math.toDegrees(movementAngle.toDouble()).toFloat() + 90)
            drawCentred(engine)
            g.popTransform()
        }
    }

    override fun update(dt: Float) {
        super.update(dt)

        if (!isRunning) {
            // Wiki: the timer resets when the drone is unpowered.
            chargeTimer = chargeDuration()
            glowTimer = 0f
            return
        }

        if (glowTimer > 0f)
            glowTimer -= dt

        // Once the ship is at the super-shield cap the drone sits fully
        // charged, waiting for a layer to be lost. Don't decrement the
        // timer, but keep it clipped to the current layer's schedule so a
        // multi-point drop (e.g. a strong ion hit taking two points) can't
        // leave us with an over-long timer.
        if (targetShip.superShield < targetShip.maxSuperShield) {
            if (chargeTimer == UNSTARTED)
                chargeTimer = chargeDuration()

            chargeTimer = min(chargeTimer, chargeDuration())
            chargeTimer -= dt

            if (chargeTimer <= 0f) {
                targetShip.superShield++
                chargeTimer = chargeDuration()
                glowTimer = GRANT_FLASH_TIME
            }
        }
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        SaveUtil.addAttrFloat(elem, "chargeTimer", chargeTimer)
        SaveUtil.addAttrFloat(elem, "glowTimer", glowTimer)
    }

    override fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        super.loadFromXML(elem, refs, containingShip)

        chargeTimer = SaveUtil.getAttrFloat(elem, "chargeTimer")
        glowTimer = SaveUtil.getAttrFloat(elem, "glowTimer")
    }

    companion object {
        private const val UNSTARTED = -1f

        // How long the _glow sprite flashes when a point is granted.
        // Guessed - vanilla's exact flash duration isn't documented.
        private const val GRANT_FLASH_TIME = 0.5f
    }
}

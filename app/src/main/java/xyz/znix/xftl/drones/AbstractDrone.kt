package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.AnimationSpec
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.FTLSound
import xyz.znix.xftl.rollChance
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.random.Random

abstract class AbstractDrone(val type: DroneBlueprint) {
    var isPowered: Boolean = false
        set(value) {
            val changed = value != field
            field = value
            if (changed)
                onPowerChanged()
        }

    /**
     * Like [isPowered], but also false if the drone is stunned.
     */
    val isRunning: Boolean get() = isPowered && !isStunned

    lateinit var ownerShip: Ship
        private set

    protected var initialised: Boolean = false
        private set

    protected lateinit var explodeAnimation: AnimationSpec
    protected lateinit var explodeSound: FTLSound

    open val isStunned: Boolean get() = ownerShip.drones!!.isHackActive

    private var stunTotalTimer: Float = 0f
    private var stunDestroyTimer: Float = 0f

    open fun init(ownerShip: Ship) {
        if (initialised)
            error("Cannot re-initialise drone ${type.name}")

        explodeAnimation = ownerShip.sys.animations["explosion_random"]
        explodeSound = ownerShip.sys.sounds.getSample("smallExplosion")

        this.ownerShip = ownerShip
        initialised = true
    }

    /**
     * Remove this drone instance, setting a cooldown before
     * it can be re-deployed.
     *
     * This should be called when a drone is destroyed by something,
     * for example a repair drone destroyed by boarders.
     */
    open fun destroy() {
        // Do this before calling removeInstance, otherwise we won't
        // be able to find the info instance.
        val drones = ownerShip.drones!!
        val info = drones.drones.firstOrNull { it?.instance == this }
        info?.cooldown = DRONE_DESTROYED_COOLDOWN

        removeInstance()
    }

    /**
     * Remove this instance of this drone.
     *
     * This does less than [destroy]: it doesn't set a cooldown or show
     * an explosion animation or anything like that, it only removes
     * the drone instance.
     */
    open fun removeInstance() {
        // Drones that have been moved to cargo won't have an associated
        // info any more, so we have to use firstOrNull.
        val drones = ownerShip.drones!!
        val info = drones.drones.firstOrNull { it?.instance == this }
        info?.instance = null
    }

    open fun update(dt: Float) {
        if (isStunned) {
            stunTotalTimer += dt

            // After one second, there's a 15% chance to destroy
            // the drone once per second.
            // It appears this is how both hacking and the anti-drone
            // can destroy drones.
            if (stunTotalTimer >= 1f) {
                // Add a small margin so level 1 hacking can get three
                // attempts at it.
                val waitPeriod = 0.99f
                stunDestroyTimer += dt
                if (stunDestroyTimer >= waitPeriod) {
                    stunDestroyTimer -= waitPeriod

                    if (Random.rollChance(15)) {
                        destroy()
                    }
                }
            }

            return
        }
        stunTotalTimer = 0f
        stunDestroyTimer = 0f
    }

    protected open fun onPowerChanged() {
    }

    /**
     * Called whenever something potentially important happens to the enemy
     * ship, such as it being destroyed or changing as we jump away.
     */
    open fun onEnemyShipUpdated() {
    }

    open fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttr(elem, "type", type.name)
        SaveUtil.addAttrRef(elem, "owner", refs, ownerShip)
        SaveUtil.addAttrBool(elem, "powered", isPowered)

        SaveUtil.addTagFloat(elem, "stunTotalTimer", stunTotalTimer, 0f)
        SaveUtil.addTagFloat(elem, "stunDestroyTimer", stunDestroyTimer, 0f)

        // Save a reference to the slot of our owning drones system that we're in.
        // This is how we link ourselves back up with the DroneInfo object.
        val slot = ownerShip.drones!!.drones.indexOfFirst { it?.instance == this }
        SaveUtil.addAttrInt(elem, "slot", if (slot == -1) null else slot)
    }

    // containingShip is the ship that the drone was serialised inside of.
    // This is not always the same as ownerShip - for example, boarding drones
    // or combat drones are serialised as part of the ship they're attacking,
    // but obviously belong to the other ship.
    open fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        require(type.name == SaveUtil.getAttr(elem, "type"))

        isPowered = SaveUtil.getAttrBool(elem, "powered")

        stunTotalTimer = SaveUtil.getOptionalTagFloat(elem, "stunTotalTimer") ?: 0f
        stunDestroyTimer = SaveUtil.getOptionalTagFloat(elem, "stunDestroyTimer") ?: 0f

        // This runs after all the other initialisation
        val slot = SaveUtil.getAttrIntOrNull(elem, "slot")
        SaveUtil.getAttrRef(elem, "owner", refs, Ship::class.java) {
            init(it!!)

            if (slot == null) {
                return@getAttrRef
            }

            val info = it.drones!!.drones[slot]!!

            // A couple of simple checks to make sure we're not ending up in the wrong slot.
            require(info.type == type)
            require(info.instance == null)

            info.instance = this
        }
    }

    companion object {
        const val DRONE_DESTROYED_COOLDOWN: Float = 10f
    }
}

package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.drones.AbstractDrone
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.*
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

abstract class AbstractProjectile(
    /**
     * If non-null, the ship this projectile is aimed at.
     *
     * If this is set and the projectile leaves one ship's
     * space, it jumps to this ship's space.
     */
    val targetShip: Ship?
) : IProjectile {

    /**
     * The projectile's speed, in pixels per second.
     */
    abstract val speed: Int

    // The angle we are approaching the target at, in radians.
    // This is only used if and when we exit the space of the
    // ship that fired us, and enter the target's space.
    var entryAngle: Float = (Math.random() * Math.PI * 2).toFloat()

    private var hasReachedTarget: Boolean = false
    private var hasPassedShields: Boolean = false

    /**
     * If true, this projectile will be removed on the next update.
     */
    protected var dead: Boolean = false

    // Helper for maths
    val Float.squared get() = this * this

    // The position this projectile is flying towards.
    var targetPos: IPoint = ConstPoint.ZERO

    // The rotation of this projectile.
    // In radians, where 0 is pointing right.
    // Note this can't be trivially computed from the current
    // and target positions - if we miss, for example, that
    // would point in the wrong direction.
    var rotation: Float = 0f
        private set

    // The position of this projectile, whether it's inside
    // the player or enemy ship space.
    protected val pos = MutFPoint(0f, 0f)

    override val position: FPoint get() = pos

    // This is continually updated from the speed variable.
    protected val vel = MutFPoint(0f, 0f)
    override val velocity: FPoint get() = vel

    override fun update(dt: Float, currentSpace: Ship) {
        if (dead) {
            currentSpace.projectiles.remove(this)
            return
        }

        val isDeparting = targetShip != null && targetShip != currentSpace

        val hitRoom = updateMovement(dt)

        // If we're in the target ship's space, check if we're now inside its shields.
        // This must happen after updateMovement, to prevent the projectile
        // from bypassing shields with high frametimes.
        if (!hasPassedShields && !isDeparting) {
            val rel = Point(position)
            rel -= currentSpace.shieldOrigin

            val shieldSize = currentSpace.shieldHalfSize

            if (rel.x.f.squared / shieldSize.x.f.squared + rel.y.f.squared / shieldSize.y.f.squared < 1) {
                hasPassedShields = true
                crossedShieldLine()
            }
        }

        if (hitRoom) {
            reachedTarget()
        }

        // Check if we're out-of-bounds. If so, either switch to the target
        // ship (if we're a departing projectile), or destroy ourselves.
        // See doc/projectiles
        if (pos.xf < -800 || pos.xf > 800 || pos.yf < -800 || pos.yf > 800) {
            currentSpace.projectiles.remove(this)

            // Are we a departing projectile? If so, switch ourselves to
            // the ship we're aimed at.
            if (targetShip != null && currentSpace != targetShip) {
                switchToTarget()
            }
        }
    }

    override fun render(g: Graphics, currentSpace: Ship) {
        g.pushTransform()
        g.translate(position.x.f, position.y.f)
        g.rotate(0f, 0f, Math.toDegrees(rotation.toDouble()).toFloat())

        renderPreTranslated(g)

        if (currentSpace.sys.debugFlags.showProjectileHitboxes.set) {
            val r = hitboxRadius.f
            g.colour = Colour.red
            g.drawOval(-r, -r, r * 2, r * 2)
        }

        g.popTransform()
    }

    protected abstract fun renderPreTranslated(g: Graphics)

    /**
     * Update the position, and return true if we hit
     * the target room (this isn't set if missed=true).
     */
    private fun updateMovement(dt: Float): Boolean {
        // Make sure we're consistent if a subclass does something silly
        // with their speed, so we don't use different speeds for x/y.
        val currentSpeed = speed

        // Update our position. If we've missed, use the rotation to
        // find the correct path. Otherwise use the difference in
        // position to make sure we're always properly aligned.
        if (hasReachedTarget) {
            vel.xf = cos(rotation) * currentSpeed
            vel.yf = sin(rotation) * currentSpeed

            pos.xf += vel.xf * dt
            pos.yf += vel.yf * dt
            return false
        }

        val deltaX = targetPos.x - pos.xf
        val deltaY = targetPos.y - pos.yf

        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)

        // Would we overshoot the target position?
        if (distance < currentSpeed * dt) {
            pos.set(targetPos)
            hasReachedTarget = true
            return true
        }

        // Find the unit-vector of the direction we have to travel in.
        val unitX = deltaX / distance
        val unitY = deltaY / distance

        vel.xf = unitX * currentSpeed
        vel.yf = unitY * currentSpeed

        pos.xf += vel.xf * dt
        pos.yf += vel.yf * dt

        return false
    }

    /**
     * Called once when the projectile reaches the target room.
     */
    protected abstract fun reachedTarget()

    /**
     * Called once when the projectile crosses the enemy shields,
     * regardless of whether they're up (or even turned on) or not.
     */
    protected open fun crossedShieldLine() {
    }

    /**
     * Called when this projectile exits the space of one ship
     * and enters that of its target.
     */
    protected open fun onSwitchedToTarget() {
    }

    /**
     * Calculate the position on the enemy ship this projectile
     * should fly towards.
     *
     * This is called after the projectile has swapped from
     * the shooter's ship to the target's ship.
     */
    open fun calculateTargetPosition(): IPoint {
        error("Cannot cross ship spaces without implementing calculateTargetPosition")
    }

    private fun switchToTarget() {
        targetShip!!.projectiles.add(this)

        // Jump into the outside of the enemy ship space.
        // See doc/projectiles for this logic.

        val radius = (800 * 0.75).toInt()

        pos.xf = 200 + cos(entryAngle) * radius
        pos.yf = 200 + sin(entryAngle) * radius

        targetPos = calculateTargetPosition()

        setRotationFromTarget()

        onSwitchedToTarget()
    }

    fun setInitialPath(initialPos: IPoint, targetPos: IPoint) {
        pos.set(initialPos)

        this.targetPos = targetPos

        setRotationFromTarget()
    }

    private fun setRotationFromTarget() {
        rotation = atan2(targetPos.y - pos.yf, targetPos.x - pos.xf)
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrFloat(elem, "x", pos.xf)
        SaveUtil.addAttrFloat(elem, "y", pos.yf)

        SaveUtil.addAttrFloat(elem, "entryAngle", entryAngle)
        SaveUtil.addAttrFloat(elem, "rotation", rotation)

        SaveUtil.addTagBoolIfTrue(elem, "reachedTarget", hasReachedTarget)
        SaveUtil.addTagBoolIfTrue(elem, "passedShields", hasPassedShields)

        SaveUtil.addPoint(elem, "targetPos", targetPos)

        // Just in case this gets set on the last update before serialisation,
        // after our update function was run.
        SaveUtil.addTagBoolIfTrue(elem, "dead", dead)
    }

    /**
     * Load back in all the parameters we saved in [saveToXML].
     *
     * Note this is different to [IProjectile.loadFromXML], as it only updates
     * the properties of a previously-created instance, rather than creating
     * a new one.
     */
    open fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
        pos.xf = SaveUtil.getAttrFloat(elem, "x")
        pos.yf = SaveUtil.getAttrFloat(elem, "y")

        entryAngle = SaveUtil.getAttrFloat(elem, "entryAngle")
        rotation = SaveUtil.getAttrFloat(elem, "rotation")

        hasReachedTarget = SaveUtil.getOptionalTagBool(elem, "reachedTarget") ?: false
        hasPassedShields = SaveUtil.getOptionalTagBool(elem, "passedShields") ?: false

        targetPos = SaveUtil.getPoint(elem, "targetPos")

        // Just in case this gets set on the last update before serialisation,
        // after our update function was run.
        dead = SaveUtil.getOptionalTagBool(elem, "dead") ?: false
    }
}

abstract class AbstractWeaponProjectile(val type: AbstractWeaponBlueprint, val target: Room) :
    AbstractProjectile(target.ship) {

    /**
     * See [Ship.pickMissed] for details.
     */
    protected open val missDeadEnemies: Boolean get() = true

    override val serialisationType: String get() = SERIALISATION_TYPE

    /**
     * The default speed for this projectile, if it's not set in the blueprint.
     *
     * Note this speed is given in pixels-per-second, divided by 16.
     * We have to multiply it by 16, since it's based on the SpeedFactor thing.
     * See doc/reveng-general.md for notes on this.
     */
    abstract val defaultSpeed: Int

    @Suppress("LeakingThis")
    override val speed = (type.speed ?: defaultSpeed) * 16

    // When we're leaving the ship that fired this weapon, draw underneath it.
    override var drawUnderShip: Boolean = true

    override val antiDroneBP: AbstractWeaponBlueprint get() = type
    override val antiDroneExemption: Ship? get() = ship.sys.getEnemyOf(ship)

    private val defaultMissSound = target.ship.sys.sounds.getSample("miss")

    private var missed: Boolean? = null

    // Used by defence drones.
    var firedByDrone: Boolean = false

    // Used by the chain ion
    var chainDamage: Int = 0

    val ship: Ship get() = target.ship

    override fun reachedTarget() {
        // First check if we're already dead. With large enough
        // timesteps, the projectile can go from outside the
        // shields to it's target in a single frame. This prevents that
        // from happening, since dead is set when we hit the shields.
        if (dead) {
            return
        }

        resolveMissed()

        if (missed == true)
            return

        hitHull()

        dead = true
    }

    override fun crossedShieldLine() {
        // We're inside the shield!

        val activeShields = ship.shields?.activeShields ?: 0
        if (activeShields == 0 && ship.superShield == 0)
            return

        resolveMissed()

        if (missed == true)
            return

        val damage = computeDamage()

        // Check for shield piercing, which seems to work the same
        // way across all weapons. Missiles for example just have
        // a very high shieldPiercing of 5.
        // This doesn't apply when the player has a super-shield
        // active, which blocks everything.
        // (except the following: resisted ion shots and sys+pers only projectiles)
        if (type.shieldPiercing >= activeShields && ship.superShield == 0) {
            // Projectiles with ion and shield piercing do their ion damage twice:
            // once as they pass the shields layer (ionising the shields system),
            // and again when they hit their target room (ionising that).
            // The same thing applies for system damage, surprisingly
            // enough (though it's only applied when there's ion damage).
            ship.attackShieldsIon(damage)
            return
        }

        hitShields()

        dead = true
    }

    override fun onSwitchedToTarget() {
        super.onSwitchedToTarget()

        // Now we're in the target ship's space, draw on top of the ship.
        drawUnderShip = false
    }

    override fun calculateTargetPosition(): IPoint {
        // Aim for the centre of the target room.
        return target.pixelCentre
    }

    protected open fun hitShields() {
        ship.attackShields(computeDamage(), position)

        ship.playDamageEffect(type, position)
        type.hitShieldSounds?.get()?.play()
    }

    override fun hitOtherProjectile(currentSpace: Ship) {
        currentSpace.playDamageEffect(type, position)
    }

    open fun computeDamage(): Damage {
        val damage = Damage(type)
        damage.applyWeaponChaining(chainDamage)
        return damage
    }

    protected open fun hitHull() {
        ship.damage(target, computeDamage())
        ship.playDamageEffect(type, target.pixelCentre)
        type.hitShipSounds?.get()?.play()
    }

    private fun resolveMissed() {
        if (missed != null)
            return

        missed = target.ship.pickMissed(-type.accuracyModifier, missDeadEnemies)

        if (missed == true) {
            val missSound = type.missSounds?.get() ?: defaultMissSound
            missSound.play()

            target.ship.showDamageTextAt(position, "text_miss", Colour.white)

            return
        }
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        // Used to figure out what blueprint creates the projectile instance
        // during deserialisation.
        SaveUtil.addAttr(elem, "launcher", type.name)

        SaveUtil.addTagBoolIfTrue(elem, "drawUnderShip", drawUnderShip)
        SaveUtil.addTagBoolIfTrue(elem, "firedByDrone", firedByDrone)
        SaveUtil.addTagInt(elem, "chainDamage", chainDamage, 0)

        if (missed != null) {
            SaveUtil.addTagBool(elem, "missed", missed!!)
        }
    }

    override fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
        super.loadPropertiesFromXML(elem, refs)

        drawUnderShip = SaveUtil.getOptionalTagBool(elem, "drawUnderShip") ?: false
        firedByDrone = SaveUtil.getOptionalTagBool(elem, "firedByDrone") ?: false
        chainDamage = SaveUtil.getOptionalTagInt(elem, "chainDamage") ?: 0

        missed = SaveUtil.getOptionalTagBool(elem, "missed")
    }

    companion object {
        fun loadFromXML(game: InGameState, elem: Element, refs: RefLoader, callback: ProjectileLoadCallback) {
            val launcher = SaveUtil.getAttr(elem, "launcher")
            val type = game.blueprintManager[launcher] as AbstractWeaponBlueprint
            type.loadProjectileFromXML(game, elem, refs, callback)
        }

        const val SERIALISATION_TYPE = "weaponProjectile"
    }
}

/**
 * This represents a drone flying on its way to its destination, such as a boarding drone.
 *
 * (This class exists to make adding new types of flying drones easier, Vanilla only has
 *  the boarding drone. Note that we don't count the hacking probe as a drone at all.)
 */
abstract class FlyingDroneProjectile(targetShip: Ship) : AbstractProjectile(targetShip) {
    abstract val drone: AbstractDrone

    override val serialisationType: String get() = SERIALISATION_TYPE

    companion object {
        const val SERIALISATION_TYPE = "flyingDrone"
    }
}

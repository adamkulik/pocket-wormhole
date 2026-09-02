package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.drones.CombatDrone
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.cos
import kotlin.math.sin

class BombBlueprint(xml: Element) : AbstractWeaponBlueprint(xml) {
    override val explosion: String = super.explosion ?: "explosion_random"

    override fun buildInstance(ship: Ship): AbstractWeaponInstance {
        return BombInstance(ship)
    }

    private fun makeBomb(target: Room, missed: Boolean, hitSuperShield: Boolean): FiredBomb {
        val animation = target.ship.sys.animations[projectile!!].startSingle(target.ship.sys, 0.5f, true)
        return FiredBomb(this@BombBlueprint, target, animation, missed, hitSuperShield)
    }

    inner class BombInstance(ship: Ship) : AbstractWeaponInstance(this, ship), IRoomTargetingWeapon {
        private var firingAnimationTimer: Float = 0f
        private var target: Room? = null
        private var hasFired = false

        override val isFiring: Boolean get() = target != null
        override val canTargetOwnShip: Boolean get() = true

        private val fireAnimationFrame: Int
            get() {
                require(target != null)
                return animation.fireIndex(firingAnimationTimer, Animations.BOMB_FIRE_TIME)
            }

        override fun render(g: Graphics) {
            if (target != null) {
                val frame = animation.spriteAt(ship.sys, fireAnimationFrame)
                frame.draw()
            } else {
                super.render(g)
            }
        }

        override fun update(dt: Float, chargeTime: Float, canCharge: Boolean) {
            super.update(dt, chargeTime, canCharge)

            val target = this.target ?: return

            if (!isPowered) {
                // Instantly stop the firing animation if we're depowered
                // The player has already been charged a missile part, so that's
                // lost, which is fine as it matches vanilla.
                stopFiring()
                return
            }

            firingAnimationTimer += dt

            if (!hasFired && fireAnimationFrame >= animation.fireFrame) {
                doBombFire(target)
                hasFired = true
            }

            if (firingAnimationTimer >= Animations.BOMB_FIRE_TIME) {
                stopFiring()
            }
        }

        private fun stopFiring() {
            target = null
            hasFired = false
            firingAnimationTimer = 0f
        }

        private fun doBombFire(target: Room) {
            val firedAtSelf: Boolean = target.ship == ship

            // Bombs we fire at ourselves can't miss or hit the super-shield.
            val missed = !firedAtSelf && target.ship.pickMissed(-type.accuracyModifier)
            val hitSuperShield = !firedAtSelf && target.ship.superShield > 0 && !missed

            target.ship.projectiles += makeBomb(target, missed, hitSuperShield)
        }

        override fun fire(targetSource: () -> Room) {
            fire()
            this.target = targetSource()

            type.launchSounds?.get()?.play()
        }

        override fun fireFromDrone(drone: CombatDrone, target: Room) {
            // Should we be subtracting a bomb when used on a drone?
            fire()

            doBombFire(target)
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)

            SaveUtil.addTagFloat(elem, "firingAnimationTimer", firingAnimationTimer, 0f)
            SaveUtil.addTagBoolIfTrue(elem, "hasFired", hasFired)
            if (target != null) {
                SaveUtil.addRoomRef(elem, "target", refs, target!!)
            }
        }

        override fun loadFromXML(elem: Element, refs: RefLoader) {
            super.loadFromXML(elem, refs)

            firingAnimationTimer = SaveUtil.getOptionalTagFloat(elem, "firingAnimationTimer") ?: 0f
            hasFired = SaveUtil.getOptionalTagBool(elem, "hasFired") ?: false

            if (elem.getChild("target") != null) {
                SaveUtil.getRoomRef(elem, "target", refs) { target = it }
            }
        }
    }

    class FiredBomb(
        val type: BombBlueprint,
        val target: Room,
        val animation: FTLAnimation,
        val missed: Boolean,
        val hitSuperShield: Boolean
    ) : IProjectile {

        override var position: ConstFPoint = ConstFPoint.ZERO
            private set

        override val velocity: FPoint get() = ConstFPoint.ZERO

        override val serialisationType: String get() = SERIALISATION_TYPE

        // Can't collide with drones or other projectiles
        override val collisionsEnabled: Boolean get() = false
        override val antiDroneBP: AbstractWeaponBlueprint? get() = null
        override val antiDroneExemption: Ship? get() = null

        /**
         * If non-null, the bomb has already exploded and
         * the explosion animation is ongoing.
         *
         * This is used to provide vision until the animation stops.
         */
        private var explosionTimer: Float? = null

        init {
            val ship = target.ship
            position = when {
                missed -> {
                    // Currently just pick anywhere in a rectangle around their shield
                    // TODO implement this in some better way, and find out how FTL does it
                    val halfSize = ship.shieldHalfSize
                    val size = halfSize * 2
                    val rand = ConstPoint((Math.random() * size.x).toInt(), (Math.random() * size.y).toInt())
                    val shipCentre = ship.hullImage.let { ConstPoint(it.width / 2, it.height / 2) }
                    (shipCentre + rand - halfSize).fConst
                }

                hitSuperShield -> {
                    // Pick a random point on the ship's shield line
                    // We can't collide with anything, so it's safe to use VisualRandom.
                    val halfSize = ship.shieldHalfSize
                    val angle = VisualRandom.nextFloat() * TWO_PI
                    ship.shieldOrigin.fConst + ConstFPoint(
                        halfSize.x * cos(angle),
                        halfSize.y * sin(angle)
                    )
                }

                else -> target.pixelCentre.fConst
            }

            if (missed) {
                ship.showDamageTextAt(position, "text_miss", Colour.white)
            }
        }

        override fun update(dt: Float, currentSpace: Ship) {
            if (explosionTimer != null) {
                // We've already exploded, wait until that animation ends.
                val newValue = explosionTimer!! - dt
                explosionTimer = newValue

                if (newValue <= 0f) {
                    currentSpace.projectiles.remove(this)
                }
                return
            }

            animation.update(dt)

            if (!animation.isStopped)
                return

            val damage = Damage(type)
            // TODO implement chain damage for bombs

            if (missed) {
                // Don't deal any damage
            } else if (hitSuperShield) {
                // Add sys to hull damage for super shield damage, and for popping
                // shield bubbles otherwise.
                // (Note that if a bomb is fired while the super shield is still
                //  up, but it goes down before the bomb explodes, then it'll
                //  damage the regular shields)
                damage.hullDamage += damage.pureSysDamage
                currentSpace.attackShields(damage, position)
            } else {
                currentSpace.damage(target, damage)
                type.hitShipSounds?.get()?.play()
            }

            // Play the explosion animation, and note how long it'll take.
            // We'll stay alive for that long, to keep the room vision available.
            explosionTimer = currentSpace.playDamageEffect(type, position).spec.totalTime
        }

        override fun render(g: Graphics, currentSpace: Ship) {
            if (explosionTimer != null)
                return

            animation.draw(position.x.f - animation.width / 2, position.y.f - animation.height / 2)
        }

        override fun hitOtherProjectile(currentSpace: Ship) = error("Bombs have collision disabled")

        override fun providesPlayerVision(room: Room): Boolean {
            return room == target && !missed && !hitSuperShield
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            SaveUtil.addAttr(elem, "type", type.name)

            SaveUtil.addAttr(elem, "targetShip", refs[target.ship])
            SaveUtil.addAttrInt(elem, "targetRoomId", target.id)
            SaveUtil.addAttrFloat(elem, "animationTimer", animation.timer)

            SaveUtil.addTagBoolIfTrue(elem, "missed", missed)
            SaveUtil.addTagBoolIfTrue(elem, "hitSuperShield", hitSuperShield)

            SaveUtil.addTagFloat(elem, "explosionTimer", explosionTimer, null)

            // The position will always be the same if we hit, but if we miss
            // or hit a zoltan shield then it's randomised.
            SaveUtil.addPoint(elem, "position", position)
        }

        fun loadFromXML(elem: Element) {
            position = SaveUtil.getPoint(elem, "position").fConst

            animation.timer = SaveUtil.getAttrFloat(elem, "animationTimer")

            explosionTimer = SaveUtil.getOptionalTagFloat(elem, "explosionTimer")
        }
    }

    companion object {
        fun loadProjectileFromXML(
            game: InGameState,
            elem: Element, refs: RefLoader,
            callback: ProjectileLoadCallback
        ) {
            val typeName = SaveUtil.getAttr(elem, "type")
            val type = game.blueprintManager[typeName] as BombBlueprint

            val targetShip = SaveUtil.getAttr(elem, "targetShip")
            val targetRoomId = SaveUtil.getAttrInt(elem, "targetRoomId")

            val missed = SaveUtil.getOptionalTagBool(elem, "missed") ?: false
            val hitSuperShield = SaveUtil.getOptionalTagBool(elem, "hitSuperShield") ?: false

            refs.asyncResolve(Ship::class.java, targetShip) {
                val room = it!!.rooms[targetRoomId]
                val bomb = type.makeBomb(room, missed, hitSuperShield)
                bomb.loadFromXML(elem)
                callback(bomb)
            }
        }

        const val SERIALISATION_TYPE = "firedBomb"
    }
}

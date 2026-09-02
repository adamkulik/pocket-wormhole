package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Implementation for flak-style weapons, including the swarm missiles.
class FlakBlueprint(xml: Element) : AbstractWeaponBlueprint(xml) {
    override val explosion: String = super.explosion ?: "explosion_missile1"
    val radius: Int = xml.getChildTextTrim("radius").toInt()

    private val projectileSpecs: List<ProjectileSpec>
    val projectileCount: Int

    init {
        projectileSpecs = ArrayList()
        xml.getChild("projectiles")?.getChildren("projectile")?.forEach { proj ->
            projectileSpecs += ProjectileSpec(
                projectileSpecs.size,
                proj.getAttributeValue("count")!!.toInt(),
                proj.textTrim,
                proj.getAttributeValue("fake")!!.toBoolean()
            )
        }

        projectileCount = projectileSpecs.filter { !it.fake }.sumOf { it.count }
    }

    override fun buildInstance(ship: Ship): AbstractWeaponInstance = FlakInstance(ship)

    override fun loadProjectileFromXML(
        game: InGameState,
        elem: Element, refs: RefLoader,
        callback: ProjectileLoadCallback
    ) {
        val specId = SaveUtil.getAttrInt(elem, "specId")

        SaveUtil.getRoomRef(elem, "target", refs) { target ->
            val projectile = FlakProjectile(target, projectileSpecs[specId])
            projectile.loadPropertiesFromXML(elem, refs)
            callback(projectile)
        }
    }

    inner class FlakInstance(ship: Ship) : AbstractProjectileWeaponInstance(this, ship) {
        override fun buildProjectile(target: Room) = error("Building a single flak projectile isn't supported")

        // TODO implement firing from drones, as some mods do this (notably IIRC Multiverse)

        override fun fireFrameHit() {
            for (spec in projectileSpecs) {
                for (i in 0 until spec.count) {
                    val projectile = FlakProjectile(waitingToFireAt!!, spec)

                    // For the swarm missile this function will be called multiple
                    // times, so we can't pick our own angle in this function.
                    projectile.entryAngle = entryAngle

                    launchProjectile(projectile)
                }
            }

            type.launchSounds?.get()?.play()
        }

        override fun fireFromArtillery(possibleTargets: List<Room>, origin: IPoint) {
            // Unlike the other projectile-based weapons, flak doesn't require
            // the target rooms are unique (it allows two projectiles to target
            // the same room).

            // Depending on whether we're the player or enemy ship, we need
            // to fly in different directions as they're angled differently.
            val endPos = origin + ship.weaponFireDirection * 5000

            // Make sure all the projectiles come in from the same angle
            val angle: Float = (Math.random() * Math.PI * 2).toFloat()

            for (spec in projectileSpecs) {
                for (i in 0 until spec.count) {
                    val projectile = FlakProjectile(possibleTargets.random(), spec)
                    projectile.entryAngle = angle
                    projectile.setInitialPath(origin, endPos)
                    ship.projectiles += projectile
                }
            }

            type.launchSounds?.get()?.play()
        }
    }

    private inner class FlakProjectile(room: Room, val spec: ProjectileSpec) :
        AbstractWeaponProjectile(this@FlakBlueprint, room) {

        private val animation = room.ship.sys.animations[spec.animation].startLooping(ship.sys)

        private var destinationOffset: IPoint

        // It seems to use the same initialisation code as a laser,
        // so it probably copies its default speed.
        override val defaultSpeed: Int get() = 60

        override val isMissileForDD: Boolean get() = true

        override val collisionsEnabled: Boolean get() = !spec.fake

        init {
            // Pick where in the circle this bit of flack will land
            // Put the random radius value in a square root so the
            // points don't clump up around the centre.
            val angle = 2 * Math.PI * Random.nextFloat()
            val randomRadius = radius * sqrt(Random.nextFloat())
            destinationOffset = ConstPoint(
                (randomRadius * cos(angle)).toInt(),
                (randomRadius * sin(angle)).toInt()
            )
        }

        override fun update(dt: Float, currentSpace: Ship) {
            super.update(dt, currentSpace)
            animation.update(dt)
        }

        override fun renderPreTranslated(g: Graphics) {
            animation.draw(-animation.width / 2f, -animation.height / 2f)
        }

        override fun hitShields() {
            if (!spec.fake) {
                ship.attackShields(computeDamage(), position)

                // Surely there would be way too much sound if all the fake
                // bits of flak hit.
                type.hitShieldSounds?.get()?.play()
            }
            playAnimation()
        }

        override fun hitHull() {
            // Check what room (if any) we've actually hit.
            // Fake projectiles are there for the visuals only, so exclude
            // them too.
            val targetPos = calculateTargetPosition()
            val hitRoom = ship.rooms.firstOrNull { it.containsShipSpace(targetPos) }
            if (!spec.fake && hitRoom != null) {
                ship.damage(hitRoom, computeDamage())
            }

            // We always have to use our custom animation, so the explosion
            // animation isn't in the centre of the room.
            playAnimation()

            type.hitShipSounds?.get()?.play()
        }

        private fun playAnimation() {
            val baseAnimation = target.ship.sys.animations[explosion]

            if (spec.fake) {
                // Fake explosions are scaled down by about 5 times, though
                // this isn't an exact measurement (though I have confirmed
                // they're the same animation).
                val scaling = 0.2f

                // Find the position to place the image at to centre it on our position
                val firstFrame = baseAnimation.spriteAt(ship.sys, 0)
                val offsetPos = ConstPoint(
                    position.x - (firstFrame.width / 2 * scaling).roundToInt(),
                    position.y - (firstFrame.height / 2 * scaling).roundToInt()
                )

                target.ship.animations += Ship.FloatingAnimation(ship.sys, baseAnimation, offsetPos, scaling)
            } else {
                target.ship.playCentredAnimation(baseAnimation, position)
            }
        }

        override fun calculateTargetPosition(): IPoint {
            return super.calculateTargetPosition() + destinationOffset
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)
            SaveUtil.addRoomRef(elem, "target", refs, target)
            SaveUtil.addAttrInt(elem, "specId", spec.specId)
            SaveUtil.addAttrFloat(elem, "spinAnimation", animation.timer)

            // We only need to save this because it is (or will be) shown in the UI.
            // We only use it here when we switch to the target ship space, so the
            // UI is the only reason you could notice it changing.
            SaveUtil.addPoint(elem, "destOffset", destinationOffset)
        }

        override fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
            super.loadPropertiesFromXML(elem, refs)
            destinationOffset = SaveUtil.getPoint(elem, "destOffset")
            animation.timer = SaveUtil.getAttrFloat(elem, "spinAnimation")
        }
    }

    // Corresponds to a <projectile> tag in the XML
    private class ProjectileSpec(val specId: Int, val count: Int, val animation: String, val fake: Boolean)
}

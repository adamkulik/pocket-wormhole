package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.drones.DefenceDrone
import xyz.znix.xftl.environment.AsteroidProjectile
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.sq
import xyz.znix.xftl.systems.Hacking

interface IProjectile {
    /**
     * The position of this projectile on the screen, relative
     * to the ship in whose projectiles list it's in.
     */
    val position: FPoint

    /**
     * The current velocity of this projectile, in pixels per second.
     */
    val velocity: FPoint

    /**
     * If true, this projectile should be drawn underneath the
     * ship whose space it occupies.
     *
     * This is intended for departing shots.
     */
    val drawUnderShip: Boolean get() = false

    /**
     * Does this projectile count as a missile to a defence drone?
     */
    val isMissileForDD: Boolean get() = false

    /**
     * Does this projectile count as a laser to a defence drone?
     */
    val isLaserForDD: Boolean get() = false

    /**
     * If true, collisions are enabled for this projectile.
     */
    val collisionsEnabled: Boolean get() = true

    /**
     * The radius of this projectile's circular hitbox.
     *
     * This is used for checking for collisions between projectiles
     * and other projectiles or drones.
     */
    val hitboxRadius: Int get() = 3

    /**
     * If this projectile can collide with drones, this is the blueprint
     * representing the weapon it applies the effect of.
     *
     * Null means it can't collide with drones.
     */
    val antiDroneBP: AbstractWeaponBlueprint?

    /**
     * If this projectile can collide with drones, this is the ship
     * whose drones are exempt from collisions.
     *
     * This is used to avoid ships shooting down their own drones.
     *
     * Null means it will collide with all drones.
     */
    val antiDroneExemption: Ship?

    /**
     * The ID used to describe what object must deserialise this projectile
     * once it's saved to XML.
     */
    val serialisationType: String

    /**
     * Called to update this projectile.
     *
     * [currentSpace] is the ship in whose space this projectile
     * is currently residing.
     */
    fun update(dt: Float, currentSpace: Ship)

    fun render(g: Graphics, currentSpace: Ship)

    /**
     * True if this projectile provides player vision for the given room.
     *
     * This is used by bombs.
     */
    fun providesPlayerVision(room: Room): Boolean = false

    /**
     * Called when this projectile hit another projectile, immediately
     * before it's destroyed.
     */
    fun hitOtherProjectile(currentSpace: Ship)

    /**
     * Serialise this projectile to XML.
     */
    fun saveToXML(elem: Element, refs: ObjectRefs)

    companion object {
        fun loadFromXML(
            game: InGameState,
            elem: Element, refs: RefLoader,
            serialisationType: String,
            callback: ProjectileLoadCallback
        ) {
            when (serialisationType) {
                AbstractWeaponProjectile.SERIALISATION_TYPE -> AbstractWeaponProjectile.loadFromXML(
                    game, elem, refs, callback
                )

                BombBlueprint.SERIALISATION_TYPE -> BombBlueprint.loadProjectileFromXML(game, elem, refs, callback)

                Hacking.PROBE_SERIALISATION_TYPE -> Hacking.loadProjectileFromXML(game, elem, refs, callback)

                DefenceDrone.LASER_SERIALISATION_TYPE -> DefenceDrone.loadProjectileFromXML(game, elem, refs, callback)

                FlyingDroneProjectile.SERIALISATION_TYPE -> {
                    error("Cannot deserialise flying drones as projectiles - they must be stored as a drone!")
                }

                AsteroidProjectile.SERIALISATION_TYPE -> AsteroidProjectile.loadFromXML(elem, refs, callback)

                else -> {
                    error("Invalid serialised projectile with serialisation type '$serialisationType'")
                }
            }
        }

        /**
         * Check if two circles will touch at any point during the next frame.
         *
         * This implements CCD (continuous collision detection), which can tell if
         * two projectiles passed through each other between frames. For example,
         * if you have two lasers flying head-on with a high delta-time, it's quite
         * possible to have them never overlap on any given frame.
         */
        fun checkCollision(
            dt: Float,
            posA: FPoint, posB: FPoint,
            velA: FPoint, velB: FPoint,
            radiusA: Float, radiusB: Float
        ): Boolean {
            // Run a CCD check
            // In 3D physics systems you'd normally do some initial checks
            // to see if the objects are definitely intersecting or definitely
            // not intersecting to save time, but here it's actually about as
            // fast to just run the CCD in all cases.

            // Find the time of the closest contact point.
            // This is a minimisation problem from the distance between the
            // two projectiles. Setting the derivative to zero ultimately
            // gets the result:
            // t = -dot(deltaP, deltaV) / dot(deltaV, deltaV)
            // Which is equivalent to:
            // t = -1/len(deltaV) * dot(deltaP, unitDeltaV)
            // So it's projecting the difference in position onto the line
            // of the difference in velocity, which seems reasonable - if
            // the projectiles are shifted in a direction perpendicular to
            // their relative velocity, it won't change the time of
            // nearest approach.
            val dVx = velB.xf - velA.xf
            val dVy = velB.yf - velA.yf
            val dPx = posB.xf - posA.xf
            val dPy = posB.yf - posA.yf

            val velLenSq = dVx * dVx + dVy * dVy

            val nearestTime = when {
                velLenSq < 0.001f -> 0f
                else -> -(dPx * dVx + dPy * dVy) / velLenSq
            }

            // This time can be in the future or the past, so clamp it
            // to fall within this time step.
            val time = nearestTime.coerceIn(0f, dt)

            // Find how far apart the projectiles are at that instant.
            // For convenience, use the dV and dP variables which represent
            // the position and velocity of the B projectile, if Ais stationary
            // at the origin.
            val distSq = (dPx + dVx * time).sq + (dPy + dVy * time).sq

            return distSq < (radiusA + radiusB).sq
        }
    }
}

typealias ProjectileLoadCallback = (IProjectile) -> Unit

package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.environment.AsteroidProjectile
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.*
import kotlin.math.*

class DefenceDrone(type: DroneBlueprint) : AbstractExternalDrone(type, false) {
    override val flightController = OrbitFlightController(this)

    override val useCustomStunRotation: Boolean get() = true

    private lateinit var offImage: Image
    private lateinit var onImage: Image
    private lateinit var engine: Image

    private lateinit var gunOffImage: Image
    private lateinit var gunOnImage: Image
    private lateinit var gunChargedImage: Image

    private val typeCooldown = type.cooldown!! / 1000f

    // Note: missiles and asteroids count as the same thing
    private val shootsAtMissiles: Boolean = type.defenceTarget != DRONES_TARGET
    private val shootsAtLasers: Boolean = type.defenceTarget == LASERS_TARGET || type.defenceTarget == OMNI_TARGET
    private val shootsAtDrones: Boolean = type.defenceTarget == DRONES_TARGET || type.defenceTarget == OMNI_TARGET

    private lateinit var weapon: LaserBlueprint
    private var weaponSpeed: Int = 0

    private var aimAngle: Float = 0f
    private var cooldown: Float = 0f

    // Remember the last projectile we shot at, to avoid shooting
    // at the same one twice.
    private var lastShotAtTarget: AbstractProjectile? = null

    // This stores the drone we're currently pointing at, and
    // we'll only randomly select another one after we shoot.
    private var currentDroneTarget: AbstractExternalDrone? = null

    override fun onInit() {
        offImage = game.getImg("img/ship/drones/${type.droneImage}_base.png")
        onImage = game.getImg("img/ship/drones/${type.droneImage}_charged.png")
        engine = game.getImg("img/ship/drones/drone_engine.png")

        gunOffImage = game.getImg("img/ship/drones/${type.droneImage}_gun.png")
        gunOnImage = game.getImg("img/ship/drones/${type.droneImage}_gun_on.png")
        gunChargedImage = game.getImg("img/ship/drones/${type.droneImage}_gun_charged.png")

        // Just assume we're using a laser, it could get a bit silly otherwise.
        weapon = type.weaponBlueprint!! as LaserBlueprint

        weaponSpeed = speedFor(weapon)
    }

    override fun onRender(g: Graphics) {
        val baseImage = when {
            isRunning -> onImage
            else -> offImage
        }

        // Try to keep everything as an integer to make the sprites sharper.
        drawCentred(baseImage)

        // Draw the little thruster flame.
        if (isRunning) {
            val movementAngle = atan2(flightController.velocity.xf, flightController.velocity.yf)
            g.pushTransform()
            g.rotate(0f, 0f, Math.toDegrees(movementAngle.toDouble()).toFloat() + 90)
            drawCentred(engine)
            g.popTransform()
        }

        // Draw the gun.
        // It points up with no rotation, so we need to rotate it clockwise
        // to make 0 match up with sin/cos space.
        val gunImage = when {
            !isRunning -> gunOffImage
            else -> gunOnImage
        }

        g.pushTransform()
        g.rotate(0f, 0f, Math.toDegrees((aimAngle + stunRotationAnimation).toDouble()).toFloat())

        // Draw the base image
        drawCentred(gunImage)

        // And draw the charged image with an increasing opacity as we charge up
        if (isRunning) {
            val timeCharging = typeCooldown - cooldown
            val chargeProgress = max(0f, timeCharging / typeCooldown)
            val colour = Colour(1f, 1f, 1f, chargeProgress)
            drawCentred(gunChargedImage, colour)
        }

        g.popTransform()
    }

    override fun update(dt: Float) {
        super.update(dt)

        if (!isRunning) {
            cooldown = typeCooldown
            return
        }

        cooldown = max(0f, cooldown - dt)

        var bestTarget: InterceptResult? = if (shootsAtDrones) {
            pickBestDroneTarget()
        } else {
            pickBestProjectileTarget()
        }

        // make omni-targeting drones actually able to shoot non-drones
        // this doesn't work, please make it work (the intent is that if omni
        // targeting that it aims at both drones and projectiles, what it
        // chooses appears to be random like after shooting at a drone and
        // missing it might on the next shot fire at a projecile the
        // targeted drone fired)
        if (bestTarget == null && shootsAtMissiles) {
            bestTarget = pickBestProjectileTarget()
        }

        // Can we shoot at something?
        // note: if the drone uses a multi-shot gun (shots is > 1) then if no
        // target is found in vanilla it will continue to fire in the same
        // direction the gun is currently turned.
        // TODO wormhole doesn't do that, implement it.
        if (cooldown == 0f && bestTarget != null) {
            shootAt(bestTarget)
        }

        // Point the gun at whatever we're planning to shoot
        // when it gets in range (or have just shot at).
        if (bestTarget != null) {
            aimAngle = DroneFlightController.getAngleFrom(flightController.position, bestTarget.point)
        }
    }

    private fun pickBestDroneTarget(): InterceptResult? {
        // Switch drones if the one we were pointing at is gone
        // or has powered off.
        if (currentDroneTarget != null) {
            val current = currentDroneTarget!!
            if (!ownerShip.externalDrones.contains(current))
                currentDroneTarget = null
            if (!isDroneSuitableTarget(current))
                currentDroneTarget = null
        }

        if (currentDroneTarget == null) {
            // TODO priorities
            val candidates = ownerShip.externalDrones.filter(this::isDroneSuitableTarget)
            if (candidates.isEmpty())
                return null
            currentDroneTarget = candidates.random()
        }

        val fc = currentDroneTarget!!.flightController

        // Don't shoot at combat drones while they're pausing, since
        // they don't have nice linear movement.
        if (fc is CombatFlightController && fc.paused)
            return null

        // Don't shoot at combat drones while they're pausing,

        return calculateIntercept(
            null, fc.position,
            fc.velocity,
            weaponSpeed.f
        )
    }

    private fun isDroneSuitableTarget(drone: AbstractExternalDrone): Boolean {
        // Drones must have been on for a short period, to avoid shooting
        // at drones the frame they spawn and thus using bad speed values.
        return drone.onEnemy && drone.timeActive > 0.1f
    }

    private fun pickBestProjectileTarget(): InterceptResult? {
        var best: InterceptResult? = null
        var bestPriority: Int = -1

        projectileLoop@ for (projectile in ownerShip.projectiles) {
            if (projectile !is AbstractProjectile)
                continue

            // Skip targets we've already shot at
            if (projectile == lastShotAtTarget)
                continue

            // Ignore departing shots, or those shooting
            // at something other than the ship.
            if (projectile.targetShip != ownerShip)
                continue

            if (projectile is AbstractWeaponProjectile) {
                // Ignore shots that were fired by another drone, since
                // that'll distract this drone a lot, and it doesn't *seem*
                // to happen in vanilla.
                //it happens actually, more noticable on shieldless ships
                if (projectile.firedByDrone)
                    continue
            }

            // Skip projectiles we're not allowed to shoot at.
            when {
                projectile.isLaserForDD && shootsAtLasers -> Unit
                projectile.isMissileForDD && shootsAtMissiles -> Unit
                else -> continue@projectileLoop
            }

            // Prioritise targets based on their damage and whether they're
            // a missile or not. This isn't properly copied from FTL, but
            // should be about right.
            val priority = when {
                // Don't give asteroids an unreasonably high priority, as they're not
                // weapon projectiles as they don't have a blueprint.
                projectile is AsteroidProjectile -> 1

                // This is non-weapon stuff, like drones or hacking.
                projectile !is AbstractWeaponProjectile -> 5

                projectile.type.shieldPiercing >= 4 -> 10 + projectile.type.damage
                else -> projectile.type.damage
            }

            if (priority < bestPriority)
                continue

            // Check if we can intercept this target (IP=intercept point)
            val result = calculateIntercept(projectile, weaponSpeed.f) ?: continue

            // Check if the projectile will reach the target prior to the interception
            val distanceToTarget = projectile.targetPos.distTo(projectile.position)
            val timeToIntercept = distanceToTarget.f / projectile.speed

            if (timeToIntercept < result.time)
                continue

            best = result
            bestPriority = priority
        }

        return best
    }

    private fun calculateIntercept(projectile: AbstractProjectile, ourSpeed: Float): InterceptResult? {
        return calculateIntercept(
            projectile, projectile.position,
            projectile.velocity,
            ourSpeed
        )
    }

    private fun calculateIntercept(
        projectile: AbstractProjectile?,
        targetPos: IPoint,
        projVel: FPoint,
        ourSpeed: Float
    ): InterceptResult? {
        // Centre the projectile at the origin by translating the drone

        val dronePos = Point(flightController.position)
        dronePos -= targetPos

        val distanceToDrone = dronePos.distToSq(ConstPoint.ZERO).f

        val projectileSpeedSq = projVel.fLengthSq

        // This is largely based on the following article. Paste it's equations into Lyx to read them.
        // https://www.codeproject.com/Articles/990452/Interception-of-Two-Moving-Objects-in-D-Space

        val a = ourSpeed.pow(2) - projectileSpeedSq
        val b = 2 * projVel.dot(dronePos)
        val c = -distanceToDrone

        // If we're shooting at the exact same speed as the incoming projectile, we get
        // a degenerate case that would otherwise cause a divide-by-zero.
        if (a < 0.001f) {
            val interceptTime = distanceToDrone / b

            val interceptPos = Point(targetPos)
            interceptPos.x += (projVel.xf * interceptTime).roundToInt()
            interceptPos.y += (projVel.yf * interceptTime).roundToInt()
            return InterceptResult(projectile, interceptPos, interceptTime)
        }

        val discriminant = b.pow(2) - 4 * a * c

        // No time at which an interception would work?
        if (discriminant <= 0) {
            return null
        }

        val interceptTime1 = (-b + sqrt(discriminant)) / (2 * a)
        val interceptTime2 = (-b - sqrt(discriminant)) / (2 * a)

        val bestInterceptTime = when {
            // Both intercepts are in the past?
            interceptTime1 <= 0f && interceptTime2 <= 0f -> return null

            // If one time is in the past, use the other
            interceptTime1 <= 0f -> interceptTime2
            interceptTime2 <= 0f -> interceptTime1

            // If there are two valid times, pick the first one.
            else -> min(interceptTime1, interceptTime2)
        }

        // From the intercept time, we can trivially solve the intercept point.
        val interceptPos = Point(targetPos)
        interceptPos.x += (projVel.xf * bestInterceptTime).roundToInt()
        interceptPos.y += (projVel.yf * bestInterceptTime).roundToInt()

        return InterceptResult(projectile, interceptPos, bestInterceptTime)
    }

    private fun shootAt(interception: InterceptResult) {
        val point = interception.point

        // Require that all projectiles are within -150 to 450
        // in both the X and Y coordinates. This is what prevents
        // the defence drone from defending the right-hand side
        // of some larger ships.
        // (Note these should be exclusive ranges, but who cares).
        // See doc/defence-drones for the source of these numbers.
        // The reason we don't include this when searching for
        // targets is:
        // a) So that the gun barrel aiming code works for out-of-range
        //    targets, and:
        // b) So that if we prioritise one target over another, we
        //    wait until the more important one is in range.
        if (point.x !in -150..450)
            return
        if (point.y !in -150..450)
            return

        // If we were previously targeting a drone, pick another one.
        currentDroneTarget = null

        val shot = InterceptorLaser(ownerShip, weapon)
        targetShip.projectiles += shot

        shot.setInitialPath(flightController.position, point)

        weapon.launchSounds?.get()?.play()

        cooldown = typeCooldown
        lastShotAtTarget = interception.target
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        SaveUtil.addAttrFloat(elem, "aimAngle", aimAngle)
        SaveUtil.addAttrFloat(elem, "cooldown", cooldown)

        // TODO serialise (these aren't *that* important, but we should still do them):
        // lastShotAtTarget
        // currentDroneTarget
    }

    override fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        super.loadFromXML(elem, refs, containingShip)

        aimAngle = SaveUtil.getAttrFloat(elem, "aimAngle")
        cooldown = SaveUtil.getAttrFloat(elem, "cooldown")
    }

    private class InterceptorLaser(
        val ownerShip: Ship,
        val weapon: LaserBlueprint
    ) : AbstractProjectile(null) {

        private val sprite = ownerShip.sys.animations[weapon.projectile!!].spriteAt(ownerShip.sys, 0)

        private val hitAnimation = ownerShip.sys.animations[weapon.explosion]

        override val speed: Int = speedFor(weapon)

        override val antiDroneBP: AbstractWeaponBlueprint get() = weapon
        override val antiDroneExemption: Ship get() = ownerShip

        override val serialisationType: String get() = LASER_SERIALISATION_TYPE

        override fun renderPreTranslated(g: Graphics) {
            sprite.draw(-sprite.width / 2f, -sprite.height / 2f)
        }

        override fun reachedTarget() {
            // Do nothing and fly off into space.
        }

        // Since we don't override crossedShieldLine, we completely ignore shields.

        override fun hitOtherProjectile(currentSpace: Ship) {
            currentSpace.playCentredAnimation(hitAnimation, position)
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)

            SaveUtil.addAttrRef(elem, "ownerShip", refs, ownerShip)
            SaveUtil.addAttr(elem, "weapon", weapon.name)
        }
    }

    private class InterceptResult(val target: AbstractProjectile?, val point: IPoint, val time: Float)

    companion object {
        const val LASER_SERIALISATION_TYPE = "defenceDroneLaser"

        // The target type used by defence 2 drones, this also shoots
        // at asteroids/missiles.
        private const val LASERS_TARGET = "LASERS"

        // Used by anti-drones, this only fires at drones.
        private const val DRONES_TARGET = "DRONES"

        // Used by certain drones in Multiverse that can target solid projectiles, lasers and drones
        private const val OMNI_TARGET = "ALL"

        fun loadProjectileFromXML(game: InGameState, elem: Element, refs: RefLoader, callback: (IProjectile) -> Unit) {
            val weaponName = SaveUtil.getAttr(elem, "weapon")
            val weapon = game.blueprintManager[weaponName] as LaserBlueprint

            SaveUtil.getAttrRef(elem, "ownerShip", refs, Ship::class.java) { owner ->
                val laser = InterceptorLaser(owner!!, weapon)
                laser.loadPropertiesFromXML(elem, refs)
                callback(laser)
            }
        }

        private fun speedFor(type: LaserBlueprint): Int {
            // On that note, assume the speed defaults to 60 which is the case for lasers.
            // (x16 is to convert the speed into pixels-per-second)
            return (type.speed ?: 60) * 16
        }
    }
}

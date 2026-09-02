package xyz.znix.xftl.environment

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.TWO_PI
import xyz.znix.xftl.game.FTLSound
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.random
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rollChance
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponProjectile
import xyz.znix.xftl.weapons.MissileBlueprint
import xyz.znix.xftl.weapons.ProjectileLoadCallback
import kotlin.random.Random
import kotlin.random.nextInt

class AsteroidEnvironment(game: InGameState, beacon: Beacon) : AbstractEnvironment(game, beacon) {
    // See doc/asteroids for more information about the mechanics here

    override val type: Beacon.EnvironmentType get() = Beacon.EnvironmentType.ASTEROID

    private var asteroidAnimationTimer = 0f

    // The actual background
    private val realBackground = game.getImg("img/stars/bg_dullstars.png")

    private val layer1 = game.getImg("img/asteroids/asteroid_back1.png")
    private val layer2 = game.getImg("img/asteroids/asteroid_back2.png")
    private val layer3 = game.getImg("img/asteroids/asteroid_back3.png")

    // Pick the number of shields once, when the environment is loaded
    private var shieldCount: Int = game.player.shields?.energyLevels?.let { it / 2 } ?: 0

    private var currentPhase: Phase = Phase.FIRING_SLOW
    private var phaseTimeRemaining: Float = 0f
    private var firingTimer: Float = 0f

    // Toggles true/false as shots switch between the player and enemy.
    // If there isn't an enemy, those asteroids are discarded.
    private var fireAtEnemy: Boolean = true

    init {
        phaseTimeRemaining = getDuration(currentPhase)
    }

    override fun renderBackground(gc: GameContainer, g: Graphics) {
        val ps = pauseShade(game)

        // This draws the normal background with all its adjustments
        realBackground.draw(0f, 0f, ps)

        // Rough speeds measured from FTL
        // back img = ~13sec to traverse weapons bar (~400px)
        // middle img = ~10sec
        // foreground img = ~8sec

        // Rough speeds measured from FTL
        // back img = ~13sec to traverse weapons bar (~400px)
        // middle img = ~10sec
        // foreground img = ~8sec
        renderAsteroid(gc, layer1, 400f / 13, ps)
        renderAsteroid(gc, layer2, 400f / 10, ps)
        renderAsteroid(gc, layer3, 400f / 8, ps)

        if (game.debugFlags.showSunTimer.set) {
            game.getFont("HL2", 2f).drawString(
                550f, 20f,
                "$currentPhase p=$phaseTimeRemaining",
                Colour.white
            )
            game.getFont("HL2", 2f).drawString(
                850f, 20f,
                "f=$firingTimer",
                Colour.white
            )
        }
    }

    override fun update(dt: Float) {
        super.update(dt)
        asteroidAnimationTimer += dt

        phaseTimeRemaining -= dt
        if (phaseTimeRemaining <= 0f) {
            switchPhase()
        }

        if (currentPhase != Phase.IDLE) {
            firingTimer -= dt
            if (firingTimer <= 0f) {
                fireAsteroid()

                firingTimer = getFiringPeriod(currentPhase)
                fireAtEnemy = !fireAtEnemy
            }
        }
    }

    private fun switchPhase() {
        if (currentPhase == Phase.IDLE) {
            currentPhase = when (Random.rollChance(50)) {
                true -> Phase.FIRING_FAST
                false -> Phase.FIRING_SLOW
            }

            // Fire right away, regardless of how long there was left until
            // the last non-idle phase fired.
            firingTimer = 0f
        } else {
            currentPhase = Phase.IDLE
        }

        phaseTimeRemaining = getDuration(currentPhase)
    }

    private fun fireAsteroid() {
        // If the enemy ship is missing, discard its asteroids.
        val ship = (if (fireAtEnemy) game.enemy else game.player) ?: return

        val asteroid = AsteroidProjectile(ship.rooms.random())

        // Figure out where we'll be starting from
        val randPart = Random.nextInt(-400..800)
        val startPos = when (Random.nextInt(4)) {
            0 -> ConstPoint(-400, randPart)
            1 -> ConstPoint(800, randPart)
            2 -> ConstPoint(randPart, -400)
            else -> ConstPoint(randPart, 800)
        }

        asteroid.setInitialPath(startPos, asteroid.target.pixelCentre)

        ship.projectiles.add(asteroid)
    }

    private fun renderAsteroid(gc: GameContainer, img: Image, speed: Float, colour: Colour) {
        val offset = (asteroidAnimationTimer * speed).toInt() % img.width
        for (x in -offset until gc.width step img.width) {
            for (y in 0 until gc.height step img.height) {
                img.draw(x, y, colour)
            }
        }
    }

    private fun getDuration(phase: Phase): Float {
        return when (phase) {
            Phase.IDLE -> when (shieldCount) {
                0, 1, 2 -> (5f..10f).random(Random)
                else -> (12f..15f).random(Random)
            }

            Phase.FIRING_SLOW -> when (shieldCount) {
                0, 1 -> (8f..16f).random(Random)
                2 -> (10f..20f).random(Random)
                3 -> (16f..28f).random(Random)
                else -> (16f..30f).random(Random)
            }

            Phase.FIRING_FAST -> when (shieldCount) {
                0, 1 -> (5f..8f).random(Random)
                2 -> (5f..10f).random(Random)
                3 -> (8f..13f).random(Random)
                else -> (8f..11f).random(Random)
            }
        }
    }

    private fun getFiringPeriod(phase: Phase): Float {
        return when (phase) {
            Phase.IDLE -> error("The idle phase doesn't fire asteroids!")

            Phase.FIRING_SLOW -> when (shieldCount) {
                0, 1 -> (1.8f..2.2f).random(Random)
                2 -> (1.0f..1.8f).random(Random)
                3 -> (0.9f..1.4f).random(Random)
                else -> (0.72f..1.35f).random(Random)
            }

            Phase.FIRING_FAST -> when (shieldCount) {
                0, 1 -> (1.4f..1.6f).random(Random)
                2, 3 -> (0.9f..1.3f).random(Random)
                else -> (0.54f..0.95f).random(Random)
            }
        }
    }

    override fun saveToXML(elem: Element) {
        super.saveToXML(elem)

        SaveUtil.addAttrInt(elem, "shieldCount", shieldCount)
        SaveUtil.addAttr(elem, "currentPhase", currentPhase.name)
        SaveUtil.addAttrFloat(elem, "phaseTimer", phaseTimeRemaining)
        SaveUtil.addAttrFloat(elem, "firingTimer", firingTimer)
        SaveUtil.addAttrBool(elem, "fireAtEnemy", fireAtEnemy)
    }

    override fun loadFromXML(elem: Element) {
        super.loadFromXML(elem)

        shieldCount = SaveUtil.getAttrInt(elem, "shieldCount")
        currentPhase = Phase.valueOf(SaveUtil.getAttr(elem, "currentPhase"))
        phaseTimeRemaining = SaveUtil.getAttrFloat(elem, "phaseTimer")
        firingTimer = SaveUtil.getAttrFloat(elem, "firingTimer")
        fireAtEnemy = SaveUtil.getAttrBool(elem, "fireAtEnemy")
    }

    private enum class Phase {
        IDLE, FIRING_SLOW, FIRING_FAST
    }
}

class AsteroidProjectile(target: Room) : AbstractWeaponProjectile(TYPE, target) {
    override val defaultSpeed: Int get() = 35

    // Asteroids hit everyone's drones
    override val antiDroneExemption: Ship? get() = null

    override val isMissileForDD: Boolean get() = true

    // Asteroids can kill ships that have surrendered
    override val missDeadEnemies: Boolean get() = false

    // We need a custom serialisation type, since our fake blueprint isn't
    // in the blueprint manager and thus can't otherwise be deserialised.
    override val serialisationType: String get() = SERIALISATION_TYPE

    private var angle: Float = Random.nextFloat() * TWO_PI
    private var imageId = Random.nextInt(10)

    // I'm not certain it uses all the hitHull/hitShield sounds,
    // but this should be good enough.
    // Note that we can't put these in the blueprint, as they requires
    // post-loading which we can't do for our blueprint (since it must
    // remain independent of any one game instance, as it's a static
    // field).
    private val hitHullSound: FTLSound = listOf(
        ship.sys.sounds.getSample("hitHull1"),
        ship.sys.sounds.getSample("hitHull2"),
        ship.sys.sounds.getSample("hitHull3")
    ).random()
    private val hitShieldSound: FTLSound = listOf(
        ship.sys.sounds.getSample("hitShield1"),
        ship.sys.sounds.getSample("hitShield2"),
        ship.sys.sounds.getSample("hitShield3")
    ).random()

    init {
        drawUnderShip = false
    }

    override fun renderPreTranslated(g: Graphics) {
        g.pushTransform()

        // This applies our spinning rotation on top of the existing
        // rotation from our direction, but that's fine - it's all
        // randomised anyway.
        g.rotateRadians(0f, 0f, angle)

        val img = ship.sys.getImg("img/asteroids/asteroid$imageId.png")
        img.draw(-img.width / 2f, -img.height / 2f)

        g.popTransform()
    }

    override fun update(dt: Float, currentSpace: Ship) {
        super.update(dt, currentSpace)

        // Asteroids spin at 160°/sec in vanilla
        angle += (160f / 360f * TWO_PI) * dt
    }

    override fun hitHull() {
        super.hitHull()
        hitHullSound.play()
    }

    override fun hitShields() {
        super.hitShields()
        hitShieldSound.play()
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)
        SaveUtil.addRoomRef(elem, "target", refs, target)
        SaveUtil.addAttrInt(elem, "asteroidType", imageId)
        SaveUtil.addAttrFloat(elem, "asteroidAngle", angle)
    }

    override fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
        super.loadPropertiesFromXML(elem, refs)
        imageId = SaveUtil.getAttrInt(elem, "asteroidType")
        angle = SaveUtil.getAttrFloat(elem, "asteroidAngle")
    }

    companion object {
        const val SERIALISATION_TYPE = "asteroid"

        private val TYPE: AbstractWeaponBlueprint

        init {
            fun addElem(elem: Element, name: String, value: String) {
                val content = Element(name)
                content.text = value
                elem.addContent(content)
            }

            // This is a pretty horrible way of creating the type, but it's
            // cleaner than making some new way to create blueprints just
            // for this one projectile.
            // Note the speed is set via the defaultSpeed property.
            val elem = Element("weaponBlueprint")
            elem.setAttribute("name", "!!INTERNAL Asteroid")
            addElem(elem, "explosion", "explosion1")
            addElem(elem, "weaponArt", "<<ASTEROID - NONE>>")
            addElem(elem, "damage", "1")
            addElem(elem, "fireChance", "2")
            addElem(elem, "breachChance", "1")

            TYPE = MissileBlueprint(elem)
        }

        fun loadFromXML(elem: Element, refs: RefLoader, callback: ProjectileLoadCallback) {
            SaveUtil.getRoomRef(elem, "target", refs) { target ->
                val projectile = AsteroidProjectile(target)
                projectile.loadPropertiesFromXML(elem, refs)
                callback(projectile)
            }
        }
    }
}

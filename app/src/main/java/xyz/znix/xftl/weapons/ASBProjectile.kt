package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.FTLAnimation
import xyz.znix.xftl.Ship
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.random
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The Anti-Ship Battery shot (issue #77): vanilla's ASBs periodically fire
 * the internal PDS_SHOT weapon at a random room. It can be evaded, cannot
 * be shot down, pierces every regular shield, and flies straight through
 * Zoltan Shields - the ZS takes no damage while the ship takes hull and
 * system damage when we arrive.
 *
 * Most ASB shots are cosmetic ([isReal] = false): they fly exactly like
 * the real one but hit nothing - they fly through the aim point and off
 * screen, with no impact effect and no smoke.
 *
 * Look (pinned by the live-process scrape, see issue-77-110-asb-plan.md
 * "LIVE SCRAPE RESULT"): the projectile is vanilla's img/weapons/pds.png
 * bolt - the single-frame 43x25 "pds" anim that PDS_SHOT's <image> points
 * at - drawn rotated to its heading like any laser. The old "flying
 * explosion" rendering was our bug: <explosion>explosion_random</explosion>
 * is only the IMPACT effect, resolved by rolling a random member of the
 * group per shot. Byte-exact pds.png textures sit in the live process
 * during an ambient-only volley, so ambient shots use the bolt too.
 */
class ASBProjectile(target: Room, val isReal: Boolean) : AbstractWeaponProjectile(TYPE, target) {
    /**
     * PDS_SHOT's <speed> is 20, but the exe's PDSFire accelerates through
     * the flight (23 -> 44 engine units; see the plan's round-1 RE-PIN),
     * which a constant blueprint speed can't express. This is why the fake
     * blueprint has no <speed> - we compute it per frame instead. Past the
     * aim point (through-flight) we hold the top speed.
     */
    override val speed: Int
        get() {
            val raw = approachProgress
            val unit = if (raw < 0f) 1f else raw.coerceAtMost(1f)
            return ((SPEED_START + (SPEED_END - SPEED_START) * unit) * 16).toInt()
        }

    // Kept abstract-complete; superseded by the speed override above
    // (the fake blueprint has no <speed>, so the base would use this -
    // the old constant mid-point of the accel curve - if we didn't).
    override val defaultSpeed: Int get() = 35

    // Not a missile (and not a laser): defence drones ignore ASB shots -
    // vanilla's cannot be shot down.
    override val isMissileForDD: Boolean get() = false

    // We need a custom serialisation type, since our fake blueprint isn't
    // in the blueprint manager and thus can't otherwise be deserialised.
    override val serialisationType: String get() = SERIALISATION_TYPE

    // The in-flight art: the PDS bolt (img/weapons/pds.png, one frame).
    private val anim: FTLAnimation = target.ship.sys.animations["pds"]
        .startSingle(target.ship.sys)

    // Distance from the spawn point to the target, for the accel curve.
    private var startDist = 0f

    // Distance from the spawn point to the ship-space exit along our
    // heading, captured on the first update - the scale curve runs over
    // the FULL screen-crossing, not just the approach leg. (After a
    // mid-flight save/load this re-captures the remaining distance, so
    // the curve restarts - bolts are never actually resumed mid-flight.)
    private var totalTravelDist = 0f

    // The collision-grace period: dead projectiles pending removal sit at
    // our exact spawn position for one frame (issue Crystal Vengeance).
    private var framesSinceSpawn = 0

    override val collisionsEnabled: Boolean
        get() = framesSinceSpawn > 3

    /**
     * Fraction of the spawn -> aim-point leg completed. Raw: goes
     * negative once we've passed the aim point on a through-flight.
     */
    private val approachProgress: Float
        get() {
            if (startDist <= 0f)
                return 0f
            val dx = targetPos.x - pos.xf
            val dy = targetPos.y - pos.yf
            val remaining = sqrt(dx * dx + dy * dy)
            return 1f - remaining / startDist
        }

    /**
     * Fraction of the FULL screen-crossing completed (spawn -> off-screen
     * exit along our heading). Drives the size curve, so ambient bolts
     * keep their size after flying through instead of snapping back.
     */
    private val travelProgress: Float
        get() {
            if (totalTravelDist <= 0f)
                return 0f
            return (1f - exitDistance() / totalTravelDist).coerceIn(0f, 1f)
        }

    /**
     * Distance from our current position to the +/-800 ship-space exit
     * along our heading (the path is a straight line, so this shrinks
     * linearly as we fly).
     */
    private fun exitDistance(): Float {
        val dx = cos(rotation)
        val dy = sin(rotation)
        var t = Float.MAX_VALUE
        if (dx > 1e-6f) t = minOf(t, (800f - pos.xf) / dx)
        if (dx < -1e-6f) t = minOf(t, (-800f - pos.xf) / dx)
        if (dy > 1e-6f) t = minOf(t, (800f - pos.yf) / dy)
        if (dy < -1e-6f) t = minOf(t, (-800f - pos.yf) / dy)
        return t
    }

    override fun update(dt: Float, currentSpace: Ship) {
        framesSinceSpawn++

        if (startDist <= 0f) {
            val dx = targetPos.x - pos.xf
            val dy = targetPos.y - pos.yf
            startDist = sqrt(dx * dx + dy * dy)
            totalTravelDist = exitDistance()
        }

        anim.update(dt)

        super.update(dt, currentSpace)
    }

    override fun crossedShieldLine() {
        // Shield-piercing (sp 5 covers every regular shield), and unlike
        // sp-99 weapons it also flies straight through Zoltan Shields: the
        // ZS takes no damage, the ship takes hull and system damage when
        // we arrive. Evasion is rolled there.
    }

    override fun reachedTarget() {
        // Ambient shots don't hit anything: like a missed shot they fly
        // straight THROUGH the aim point and off-screen, with no impact
        // effect or smoke (vanilla behaviour - the bolt must not pop out
        // of existence at the room centre). hasReachedTarget is already
        // set, so the base movement keeps us on the same heading until
        // the out-of-bounds check removes us.
        if (!isReal) {
            return
        }
        super.reachedTarget()
    }

    /**
     * The impact explosion: PDS_SHOT says
     * <explosion>explosion_random</explosion>, which the exe resolves by
     * rolling a random member of the group (the three animations.xml anims
     * between the "called randomly" comments). Roll per shot, like vanilla.
     */
    override fun hitHull() {
        ship.damage(target, computeDamage())

        val impact = ship.sys.animations[EXPLOSION_ANIMS.random(Random)]
        ship.playCentredAnimation(impact, target.pixelCentre)

        // PDS_SHOT's hitShipSounds (hitHull1).
        type.hitShipSounds?.get()?.play()
    }

    override fun renderPreTranslated(g: Graphics) {
        // The base render has already translated us to the projectile's
        // position and rotated to the heading. The bolt grows over the
        // first ~2/3 of the full screen-crossing, then HOLDS max size
        // until it leaves the screen (user-pinned vanilla shape).
        val frame = anim.currentFrame
        g.pushTransform()
        g.rotate(0f, 0f, RENDER_ROTATION_OFFSET)
        val grow = (travelProgress / SCALE_MAX_PROGRESS).coerceIn(0f, 1f)
        val scale = RENDER_SCALE_START + (RENDER_SCALE_MAX - RENDER_SCALE_START) * grow
        g.scale(scale, scale)
        frame.draw(-frame.width / 2f, -frame.height / 2f)
        g.popTransform()
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)
        SaveUtil.addRoomRef(elem, "target", refs, target)
        SaveUtil.addAttrBool(elem, "isReal", isReal)
    }

    companion object {
        const val SERIALISATION_TYPE = "asb_shot"

        private const val SPEED_START = 23f
        private const val SPEED_END = 44f
        // By-eye tune (user, vs vanilla): the bolt grows over the first
        // 2/3 of the full screen-crossing, holds max size to the edge,
        // and everything is 1.5x the round-3 values (3.0 = 2.0 * 1.5,
        // 6.75 = 4.5 * 1.5).
        private const val RENDER_SCALE_START = 3.0f
        private const val RENDER_SCALE_MAX = 6.75f
        private const val SCALE_MAX_PROGRESS = 2f / 3f
        private const val RENDER_ROTATION_OFFSET = 0f

        // Vanilla's explosion_random group: exactly the three anims between
        // the "called randomly" comments in animations.xml.
        private val EXPLOSION_ANIMS = listOf("explosion_big2", "explosion_big3", "explosion_big4")

        // Fallback only: hitOtherProjectile plays the blueprint's explosion,
        // but ASB shots can't be shot down so this never happens in practice.
        private const val IMPACT_ANIM_FALLBACK = "explosion_missile1"

        private val TYPE: AbstractWeaponBlueprint

        init {
            fun addElem(elem: Element, name: String, value: String) {
                val content = Element(name)
                content.text = value
                elem.addContent(content)
            }

            // Same fake-blueprint approach the asteroid uses, with
            // PDS_SHOT's real numbers: 3 damage, guaranteed breach
            // (breachChance is out of 10), sp 5 pierces every regular
            // shield bubble (vanilla max is 4; the old sp 99 was our
            // stand-in).
            val elem = Element("weaponBlueprint")
            elem.setAttribute("name", "!!INTERNAL Anti-Ship Battery Shot")
            addElem(elem, "explosion", IMPACT_ANIM_FALLBACK)
            addElem(elem, "weaponArt", "<<ASB SHOT - NONE>>")
            addElem(elem, "damage", "3")
            addElem(elem, "breachChance", "10")
            addElem(elem, "sp", "5")
            // PDS_SHOT's hitShipSounds, played by our hitHull override.
            elem.addContent(Element("hitShipSounds").apply {
                addContent(Element("sound").apply { text = "hitHull1" })
            })

            TYPE = MissileBlueprint(elem)
        }

        fun loadFromXML(elem: Element, refs: RefLoader, callback: ProjectileLoadCallback) {
            SaveUtil.getRoomRef(elem, "target", refs) { target ->
                val projectile = ASBProjectile(target, SaveUtil.getAttrBool(elem, "isReal"))
                projectile.loadPropertiesFromXML(elem, refs)
                callback(projectile)
            }
        }
    }
}

package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil

/**
 * The counterattack shard fired by Crystal Vengeance (CRYSTAL_SHARDS).
 *
 * Vanilla behaviour (per the wiki): 1 damage, 10% breach chance, 20% stun
 * chance (AE - not applied yet, the engine has no crew stun), completely
 * ignores regular shields, is affected by evasion, and can be shot down by
 * defence drones. It's a neutral projectile, like asteroids - to the point
 * that friendly defence drones will shoot it down, which this reproduces.
 */
class VengeanceShardProjectile(target: Room) : AbstractWeaponProjectile(TYPE, target) {
    override val defaultSpeed: Int get() = 60

    // A neutral projectile: any defence drone, including a friendly one,
    // can shoot it down. Vanilla quirk, kept deliberately.
    override val antiDroneExemption: Ship? get() = null

    override val isMissileForDD: Boolean get() = true

    // We need a custom serialisation type, since our fake blueprint isn't
    // in the blueprint manager and thus can't otherwise be deserialised.
    override val serialisationType: String get() = SERIALISATION_TYPE

    private val spr = target.ship.sys.getImg("img/weapons/crystal_shot1.png")

    // The projectile that spawned us (dead, pending removal) sits at our
    // exact spawn position for one frame - without a grace period the
    // collision pass would always destroy us at spawn. Mid-flight, the
    // vanilla "projectiles can intercept the shard" quirk still applies.
    private var framesSinceSpawn = 0

    override val collisionsEnabled: Boolean
        get() = framesSinceSpawn > 3

    override fun update(dt: Float, currentSpace: Ship) {
        framesSinceSpawn++
        super.update(dt, currentSpace)
    }

    override fun renderPreTranslated(g: Graphics) {
        spr.draw(-spr.width / 2f, -spr.height / 2f)
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)
        SaveUtil.addRoomRef(elem, "target", refs, target)
    }

    companion object {
        const val SERIALISATION_TYPE = "vengeance_shard"

        private val TYPE: AbstractWeaponBlueprint

        init {
            fun addElem(elem: Element, name: String, value: String) {
                val content = Element(name)
                content.text = value
                elem.addContent(content)
            }

            // Same fake-blueprint approach the asteroid uses. The speed is
            // set via defaultSpeed; sp 99 makes the shard fly straight
            // through any number of regular shield bubbles (super-shields
            // still block it).
            val elem = Element("weaponBlueprint")
            elem.setAttribute("name", "!!INTERNAL Crystal Vengeance Shard")
            addElem(elem, "explosion", "explosion1")
            addElem(elem, "weaponArt", "<<CRYSTAL SHARD - NONE>>")
            addElem(elem, "damage", "1")
            addElem(elem, "breachChance", "10")
            addElem(elem, "stunChance", "20")
            addElem(elem, "sp", "99")

            TYPE = MissileBlueprint(elem)
        }

        fun loadFromXML(elem: Element, refs: RefLoader, callback: ProjectileLoadCallback) {
            SaveUtil.getRoomRef(elem, "target", refs) { target ->
                val projectile = VengeanceShardProjectile(target)
                projectile.loadPropertiesFromXML(elem, refs)
                callback(projectile)
            }
        }
    }
}

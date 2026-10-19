package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil

/**
 * A laser fired by the Rebel Flagship's stage-3 Power Surge (GitHub issue
 * #15).
 *
 * Vanilla's `ShipManager::PrepareSuperBarrage` (FTLGame.exe 1.6.14,
 * 0x004a6a90) fires seven `LASER_HEAVY_1` lasers at once. The blueprint is
 * copied normally - so the status chances are the Heavy Laser Mk I's 30%
 * fire / 30% breach / 20% stun (the engine only rolls breach if fire did
 * not proc, giving vanilla's effective 21% breach chance) - but the hull
 * damage is overwritten to 1 (the exe writes 1 to the projectile's damage
 * object after copying the blueprint).
 */
class PowerSurgeLaserProjectile(type: LaserBlueprint, target: Room) :
    AbstractWeaponProjectile(type, target) {
    override val defaultSpeed: Int get() = 60

    override val isLaserForDD: Boolean get() = true

    // The launcher isn't a normal weapon instance, so this needs its own
    // serialisation type (mid-flight barrage shots are otherwise restored
    // as ordinary 2-damage Heavy Lasers).
    override val serialisationType: String get() = SERIALISATION_TYPE

    private val spr = target.ship.sys.animations[type.projectile!!].spriteAt(ship.sys, 0)

    override fun renderPreTranslated(g: Graphics) {
        // Same as a regular laser: the hitbox is the sprite centre.
        spr.draw(-spr.width / 2f, -spr.height / 2f)
    }

    override fun computeDamage(): Damage {
        // Vanilla hard-codes the barrage lasers to one damage instead of the
        // Heavy Laser Mk I's two.
        val damage = super.computeDamage()
        damage.hullDamage = 1
        return damage
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)
        SaveUtil.addRoomRef(elem, "target", refs, target)
    }

    companion object {
        const val SERIALISATION_TYPE = "power_surge_laser"

        fun loadFromXML(
            game: InGameState,
            elem: Element, refs: RefLoader,
            callback: ProjectileLoadCallback
        ) {
            SaveUtil.getRoomRef(elem, "target", refs) { target ->
                val type = game.blueprintManager["LASER_HEAVY_1"] as LaserBlueprint
                val projectile = PowerSurgeLaserProjectile(type, target)
                projectile.loadPropertiesFromXML(elem, refs)
                callback(projectile)
            }
        }
    }
}

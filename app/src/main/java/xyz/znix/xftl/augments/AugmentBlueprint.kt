package xyz.znix.xftl.augments

import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.MedbayHealing

/**
 * The base augment class, which is suitable for augments which are
 * implemented as part of another system (for example, reconstructive
 * teleport is implemented by the teleporter).
 *
 * In general, it's probably a good idea to implement augments here
 * to show likely pain points for modding.
 */
open class AugmentBlueprint(elem: Element) : Blueprint(elem) {
    override val cost: Int = elem.getChildTextTrim("cost").toInt()
    val stackable: Boolean = elem.getChildTextTrim("stackable")!!.toBoolean()
    val value: Float = elem.getChildTextTrim("value")?.toFloat() ?: 1f

    open fun update(ship: Ship, dt: Float, totalValue: Float) {}

    open fun onJump(ship: Ship) {}

    /**
     * Called when a ship which comes with this augment is created.
     */
    open fun onShipSpawn(ship: Ship) {}

    companion object {
        const val AUTOMATED_RELOADERS: String = "AUTO_COOLDOWN"
        const val LONG_RANGE_SCANNERS: String = "ADV_SCANNERS"
        const val RECONSTRUCTIVE_TELEPORT: String = "TELEPORT_HEAL"
        const val OXYGEN_MASKS: String = "O2_MASKS"
        const val BACKUP_DNA: String = "BACKUP_DNA"
        const val BATTERY_CHARGER: String = "BATTERY_BOOSTER"
        const val SHIELD_CHARGE_BOOSTER: String = "SHIELD_RECHARGE"
        const val STEALTH_WEAPONS: String = "CLOAK_FIRE"
    }
}

class AugEngiMedbots(elem: Element) : AugmentBlueprint(elem) {
    override fun update(ship: Ship, dt: Float, totalValue: Float) {
        super.update(ship, dt, totalValue)

        val medbay = ship.medbay ?: return
        if (medbay.powerSelected == 0)
            return

        val healing = dt * 6.4f * totalValue

        for (crew in ship.friendlyCrew) {
            if (crew !is LivingCrew)
                continue

            // Medbots don't add extra healing in the medbay
            if (crew.room == medbay.room)
                continue

            crew.dealDamage(MedbayHealing(-healing))
        }
    }

    companion object {
        const val NAME = "NANO_MEDBAY"
    }
}

class AugPreigniter(elem: Element) : AugmentBlueprint(elem) {
    override fun onJump(ship: Ship) {
        super.onJump(ship)

        for (hp in ship.hardpoints) {
            val weapon = hp.weapon ?: continue

            // We don't charge up unpowered weapons
            if (!weapon.isPowered)
                continue

            weapon.timeCharged = weapon.type.chargeTime
        }
    }

    companion object {
        const val NAME: String = "WEAPON_PREIGNITE"
    }
}

class AugZoltanShield(elem: Element) : AugmentBlueprint(elem) {
    override fun onJump(ship: Ship) {
        super.onJump(ship)
        ship.superShield = 5
    }

    // This is required so that enemy ships start with a super shield.
    override fun onShipSpawn(ship: Ship) {
        super.onShipSpawn(ship)
        ship.superShield = 5
    }

    companion object {
        const val NAME: String = "ENERGY_SHIELD"
    }
}

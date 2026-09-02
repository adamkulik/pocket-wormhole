package xyz.znix.xftl.crew

import xyz.znix.xftl.weapons.Damage

/**
 * This defines a unit of damage given to a crew.
 *
 * This is written with an eye to modding, as one might expect mods
 * would want to adjust damage in various ways, which requires them
 * to know what's causing it.
 */
abstract class AbstractCrewDamage(var amount: Float) {
    /**
     * True if drones take half the damage from this source as regular crew do.
     */
    open val halvedForDrone: Boolean = false
}

/**
 * 'damage' caused by a medbay, normally negative to represent healing,
 * though this can be positive for a hacked medbay.
 */
class MedbayHealing(amount: Float) : AbstractCrewDamage(amount)

/**
 * 'damage' caused by a clonebay, always negative. Caused by the
 * heal-on-jump mechanic.
 */
class ClonebayHealing(amount: Float) : AbstractCrewDamage(amount)

class SuffocationDamage(amount: Float) : AbstractCrewDamage(amount)
class FireDamage(amount: Float) : AbstractCrewDamage(amount)
class CombatDamage(amount: Float, val attacker: AbstractCrew) : AbstractCrewDamage(amount)

class ShipDamage(amount: Float, val source: Damage) : AbstractCrewDamage(amount) {
    override val halvedForDrone: Boolean get() = true
}

class ZoltanDeathDamage(amount: Float, val crew: CrewZoltan) : AbstractCrewDamage(amount) {
    override val halvedForDrone: Boolean get() = true
}

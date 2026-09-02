package xyz.znix.xftl.weapons

/**
 * Represents some amount of damage which is applied to the ship.
 */
class Damage() {
    /**
     * Create a [Damage] instance from the properties of a weapon.
     */
    constructor(type: AbstractWeaponBlueprint) : this() {
        this.hullDamage = type.damage
        this.ionDamage = type.ionDamage
        this.pureSysDamage = type.sysDamage
        this.pureCrewDamage = type.personnelDamage * 15
        this.emptyRoomBonus = type.hullBust

        this.fireChance = type.fireChance * 10
        this.breachChance = type.breachChance * 10
        this.stunChance = type.stunChance * 10

        this.noSysDamage = type.noSysDamage
        this.noCrewDamage = type.noPersonnelDamage
    }

    /**
     * The base amount of damage this weapon does.
     */
    var hullDamage: Int = 0

    var ionDamage: Int = 0

    // The reason why pureSysDamage and pureCrewDamage are required is that
    // some stuff specifically uses them, so we need to track them to properly
    // match up with vanilla.

    /**
     * The amount of system damage this weapon does, excluding the
     * contribution from the hull damage.
     */
    var pureSysDamage: Int = 0

    /**
     * The amount of crew damage this weapon does, excluding the
     * contribution from the hull damage.
     *
     * This is in hp, not units of 15 hp that the weapon XML uses.
     */
    var pureCrewDamage: Int = 0

    // Note that negative hull damage doesn't mean negative sys/pers damage!
    val effectiveSysDamage: Int
        get() = when (noSysDamage) {
            true -> pureSysDamage
            false -> pureSysDamage + hullDamage.coerceAtLeast(0)
        }

    val effectiveCrewDamage: Int
        get() = when (noCrewDamage) {
            true -> pureCrewDamage
            false -> pureCrewDamage + hullDamage.coerceAtLeast(0) * 15
        }

    /**
     * The additional hull damage that's dealt against empty rooms.
     */
    var emptyRoomBonus: Int = 0

    // The fire/breach/stun chances, in percent
    var fireChance: Int = 0
    var breachChance: Int = 0
    var stunChance: Int = 0

    // When these are set, hullDamage isn't automatically added to
    // the system and crew damage.
    var noSysDamage: Boolean = false
    var noCrewDamage: Boolean = false

    // lockdown also belongs here once implemented

    /**
     * Apply the effect of a weapon that uses damage-chaining.
     */
    fun applyWeaponChaining(chainDamage: Int) {
        if (chainDamage <= 0)
            return

        when {
            hullDamage > 0 -> hullDamage += chainDamage
            ionDamage > 0 -> ionDamage += chainDamage
            pureCrewDamage > 0 -> pureCrewDamage += chainDamage
            pureSysDamage > 0 -> pureSysDamage += chainDamage
        }
    }

    /**
     * Returns a copy of this damage, with everything except the ion and stun
     * components removed.
     */
    fun copyIon(): Damage {
        val result = Damage()
        result.ionDamage = ionDamage
        result.stunChance = stunChance
        // TODO stun duration
        return result
    }

    /**
     * Returns a copy of this damage, with everything except the ion, stun
     * and system components removed.
     *
     * This uses [pureSysDamage], not [effectiveSysDamage]. Thus the system
     * damage caused by this function's result is not necessarily the same
     * as the damage caused by this object.
     *
     * This function is used when applying ion damage that hits the shields
     * of a ship.
     */
    fun copyIonAndSys(): Damage {
        val result = copyIon()
        result.pureSysDamage = pureSysDamage
        return result
    }

    /**
     * Returns an identical copy of this object.
     */
    fun copy(): Damage {
        val result = Damage()

        result.hullDamage = hullDamage
        result.ionDamage = ionDamage
        result.pureSysDamage = pureSysDamage
        result.pureCrewDamage = pureCrewDamage
        result.emptyRoomBonus = emptyRoomBonus

        result.fireChance = fireChance
        result.breachChance = breachChance
        result.stunChance = stunChance

        result.noSysDamage = noSysDamage
        result.noCrewDamage = noCrewDamage

        return result
    }

    companion object {
        @JvmStatic
        fun hullOnly(amount: Int): Damage {
            val damage = Damage()
            damage.hullDamage = amount
            damage.noSysDamage = true
            damage.noCrewDamage = true
            return damage
        }

        @JvmStatic
        fun hullAndSys(amount: Int): Damage {
            val damage = Damage()
            damage.hullDamage = amount
            damage.noCrewDamage = true
            return damage
        }

        @JvmStatic
        fun regular(amount: Int): Damage {
            val damage = Damage()
            damage.hullDamage = amount
            return damage
        }
    }
}

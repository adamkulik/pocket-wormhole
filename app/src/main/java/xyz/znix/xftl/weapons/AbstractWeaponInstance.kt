package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.GameText
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.drones.CombatDrone
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.DelayedTooltip
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.ITooltipProvider
import xyz.znix.xftl.replaceArg
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.Artillery
import xyz.znix.xftl.systems.Weapons
import kotlin.math.max

abstract class AbstractWeaponInstance(val type: AbstractWeaponBlueprint, val ship: Ship) {
    // The time spent charging thus far
    var timeCharged: Float = 0f

    // For charger weapons, this is how many shots are stored up, in
    // addition to the one represented by timeCharged.
    var extraCharges: Int = 0

    // For chain weapons, this is the number of volleys we've fired since
    // the weapon was powered off. This is limited to the maximum number
    // of shots that have an effect.
    var chainCount: Int = 0

    // The number of charges this weapon is ready to fire.
    val totalReadyCharges: Int get() = extraCharges + if (isCurrentCharged) 1 else 0

    // Is this weapon selected as powered
    // To set this, use forceSetPowered and read it's JavaDoc.
    var isPowered: Boolean = false
        private set

    abstract val isFiring: Boolean

    // Turn missile weapons off once their ship runs out of missiles
    val hasEnoughMissiles: Boolean get() = ship.missilesCount >= type.missilesUsed

    // How far out is this weapon, on a scale of 0-1. Used for making weapons slide in and out when
    // powered up and down.
    var slide: Float = 0f

    /**
     * True if the weapon is ready to fire. For charge weapons this is true
     * even when [isCurrentCharged] is false, as long as they've stored away an extra shot.
     */
    val isCharged: Boolean get() = isCurrentCharged || extraCharges > 0

    /**
     * True if the weapon's current charge has finished charging. This is different
     * to [isCharged] for charge weapons, as this is only true when the currently
     * charging charge is done.
     */
    val isCurrentCharged: Boolean get() = timeCharged >= chargeTime
    val chargeProgress: Float get() = timeCharged / chargeTime

    val maxTotalCharges get() = type.chargeLevels ?: 1
    val maxExtraCharges get() = maxTotalCharges - 1

    val animation = type.getLauncher(ship.sys)

    private val boostAnim = animation.boostAnim?.startSingle(ship.sys)

    /**
     * True if the player can fire this weapon at their own ship, eg for bombs.
     */
    open val canTargetOwnShip: Boolean get() = false

    val chargeTime: Float
        get() {
            if (type.boost?.type != AbstractWeaponBlueprint.BoostType.COOLDOWN)
                return type.chargeTime

            return type.chargeTime - chainCount * type.boost.perShot
        }

    /**
     * The tooltip displayed when the player hovers the button they click to aim the weapon.
     */
    val weaponBarTooltip: ITooltipProvider? by lazy { createWeaponBarTooltip() }


    open fun update(dt: Float, chargeTime: Float, canCharge: Boolean) {
        val speedMult = (1f + ship.getAugmentValue(AugmentBlueprint.AUTOMATED_RELOADERS))

        if (isPowered) {
            if (canCharge)
                timeCharged += chargeTime * speedMult
        } else {
            // Automated reloaders make weapons turn off faster, too.
            timeCharged -= dt * 10 * speedMult

            if (timeCharged <= 0) {
                extraCharges = 0
                chainCount = 0
            }
        }

        if (timeCharged > this.chargeTime)
            timeCharged = this.chargeTime
        if (timeCharged < 0f)
            timeCharged = 0f

        if (isCurrentCharged && extraCharges < maxExtraCharges) {
            timeCharged = 0f
            extraCharges++
        }
    }

    protected fun fire() {
        timeCharged = 0f
        extraCharges = 0

        // Count up
        if (type.boost != null) {
            chainCount = (chainCount + 1).coerceIn(0..type.boost.maxCount)
        }

        // Deduct a missile (or multiple), if this weapon uses them
        // This really shouldn't be going negative here, but guard
        // it just in case.
        if (!ship.sys.debugFlags.infiniteMissiles.set) {
            ship.missilesCount = max(0, ship.missilesCount - type.missilesUsed)
        }
    }

    open fun render(g: Graphics) {
        val launcher = animation.spriteAt(ship.sys, (animation.chargedFrame * chargeProgress).toInt())
        launcher.draw(0f, 0f)

        renderChainChargeLights()

        // Draw the charging glow, if present
        if (isCurrentCharged)
            return
        val glowPath = animation.chargeImage ?: return
        val glow = ship.sys.getImg(glowPath)

        val filter = Colour(1f, 1f, 1f, chargeProgress)
        glow.draw(0f, 0f, filter)
    }

    fun renderChainChargeLights() {
        // For charge weapons, draw on the indicator lights
        if (boostAnim != null && maxTotalCharges > 1 && totalReadyCharges > 0) {
            // 0 indicates one extra charge, so we have to -1.
            boostAnim.spriteAt(totalReadyCharges - 1).draw()
        }

        // For chain weapons, draw the progress
        if (boostAnim != null && type.boost != null && chainCount > 0) {
            // 0 indicates one shot already fired, so -1.
            boostAnim.spriteAt(chainCount - 1).draw()
        }
    }

    fun asWeaponInstance(): AbstractWeaponInstance = this

    /**
     * Power this weapon on or off.
     *
     * This should rarely be used - you should have a very
     * good reason not to use [Weapons.setWeaponPower], since
     * this doesn't check if the weapons system is ion-locked.
     */
    fun forceSetPowered(newPowerState: Boolean) {
        isPowered = newPowerState
    }

    protected open fun createWeaponBarTooltip(): ITooltipProvider {
        val tr = ship.sys.translator
        val sb = StringBuilder()

        // Thus function is very heavily based off vanilla's WeaponBlueprint::GetDescription

        // The "Type: Laser" line
        var typeKey = when (type) {
            is LaserBlueprint -> if (type.ionDamage > 0) "type_ion" else "type_laser"
            is MissileBlueprint -> if (type.missilesUsed > 0) "type_missile" else "type_crystal"
            is BeamBlueprint -> "type_beam"
            is BombBlueprint -> "type_bomb"
            is FlakBlueprint -> "type_burst"
            else -> "type_unknown" // Mods need to override this - TODO find a clean way to do this
        }
        if (type.chargeLevels != null) {
            typeKey += "_charge"
        }
        if (type.boost != null) {
            typeKey += "_chain"
        }
        val typeText: GameText = type.flavourType ?: GameText.localised(typeKey)
        val effectLines = ArrayList<GameText>()

        if (type.missilesUsed > 0) {
            effectLines.add(GameText.localised("type_missile_effect"))
        }
        if (type.ionDamage > 0) {
            effectLines.add(GameText.localised("type_ion_effect"))
        }
        if (type.chargeLevels != null) {
            effectLines.add(GameText.localised("type_charge_effect"))
        }
        if (type.boost != null) {
            effectLines.add(GameText.localised("type_chain_effect"))
        }

        val typeEffectsString = effectLines.joinToString { tr[it] + "\n" }
        sb.append(tr["type_desc"].replaceArg(tr[typeText]).replaceArg(typeEffectsString, 2) + "\n")

        if (type.boost?.type == AbstractWeaponBlueprint.BoostType.COOLDOWN) {
            sb.append(tr["boost_power_speed"] + "\n")
            // Max boost speed isn't shown in the tooltip
        }
        if (type.boost?.type == AbstractWeaponBlueprint.BoostType.DAMAGE) {
            // We have to include the initial damage in the max value shown.
            // How does vanilla FTL do this?
            // TODO deduplicate this calculation with the one in InfoPanel
            val baseDamage = max(type.damage, type.ionDamage)
            val maxDamage = type.boost.maxCount * type.boost.perShot + baseDamage
            sb.append(tr["boost_power_damage"] + "\n")
            sb.append(tr["damage_cap"].replaceArg(maxDamage) + "\n")
        }

        if (type is LaserBlueprint || type is FlakBlueprint || (type is MissileBlueprint && type.shots != 1)) {
            sb.append(tr["shots"].replaceArg(type.shots) + "\n")
        }

        if (type.chargeLevels != null) {
            sb.append(tr["charge"].replaceArg(type.chargeLevels) + "\n")
        }

        val damageKey = if (type is BeamBlueprint) "damage_room" else "damage_shot"
        sb.append(tr[damageKey].replaceArg(type.damage) + "\n")

        if (type.shieldPiercing != 0 && type.shieldPiercing != 5) {
            sb.append(tr["shield_piercing"].replaceArg(type.shieldPiercing) + "\n")
        }

        val effects = ArrayList<GameText>()
        if (type.breachChance != 0) {
            effects.add(GameText.localised("chance_of_breach"))
        }
        if (type.fireChance != 0) {
            effects.add(GameText.localised("chance_of_fire"))
        }
        if (type.stunChance != 0) {
            effects.add(GameText.localised("chance_of_stun"))
        }
        if (effects.isNotEmpty()) {
            // The separator XML tag includes a space (in the English locale),
            // but we're stripping it out while loading the translations.
            // It seems unlikely that it won't contain a space, so it's a bit
            // ugly but we can just put it back in here.
            val separator = tr["chance_of_separator"] + " "

            val specialEffectsString = effects.joinToString(separator) { tr[it] }
            sb.append(tr["chance_of"].replaceArg(specialEffectsString) + "\n")
        }

        if (type.ionDamage != 0) {
            sb.append(tr["ion_damage"].replaceArg(type.ionDamage) + "\n")
        }
        if (type.stun != 0) {
            sb.append(tr["stun_damage"].replaceArg(type.stun) + "\n")
        }
        if (type.personnelDamage != 0) {
            sb.append(tr["personnel_damage"].replaceArg(type.personnelDamage) + "\n")
        }
        if (type.sysDamage != 0) {
            sb.append(tr["system_damage"].replaceArg(type.sysDamage) + "\n")
        }
        if (type.hullBust != 0) {
            sb.append(tr["double_damage"] + "\n")
        }

        sb.append("\n")
        sb.append(tr["hotkey"] + "\n") // TODO insert the hotkey name
        sb.append(tr["rearrange"])

        return WeaponButtonTooltip(ship.sys, sb.toString())
    }

    open fun bindToWeaponsSystem(weapons: Weapons) {
    }

    open fun bindToArtillery(artillery: Artillery) {
    }

    open fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttr(elem, "type", type.name)
        SaveUtil.addAttrBool(elem, "powered", isPowered)
        SaveUtil.addAttrFloat(elem, "chargeTime", timeCharged)
        SaveUtil.addTagInt(elem, "extraCharges", extraCharges, 0)
        SaveUtil.addTagInt(elem, "chainCount", chainCount, 0)

        val expectedSlide = if (isPowered) 1f else 0f
        SaveUtil.addTagFloat(elem, "slideAnimation", slide, expectedSlide)
    }

    open fun loadFromXML(elem: Element, refs: RefLoader) {
        require(type.name == SaveUtil.getAttr(elem, "type"))

        isPowered = SaveUtil.getAttrBool(elem, "powered")
        timeCharged = SaveUtil.getAttrFloat(elem, "chargeTime")
        extraCharges = SaveUtil.getOptionalTagInt(elem, "extraCharges") ?: 0
        chainCount = SaveUtil.getOptionalTagInt(elem, "chainCount") ?: 0

        val expectedSlide = if (isPowered) 1f else 0f
        slide = SaveUtil.getOptionalTagFloat(elem, "slideAnimation") ?: expectedSlide
    }

    class WeaponButtonTooltip(game: InGameState, private val text: String) : DelayedTooltip(game) {
        override fun getText(): String = text
        override val customMaxWidth: Int get() = 300
    }
}

/**
 * Represents a weapon that can be fired at individual rooms. Includes basically everything but beams.
 */
interface IRoomTargetingWeapon {
    fun fire(targetSource: () -> Room)

    fun fireFromDrone(drone: CombatDrone, target: Room)

    fun asWeaponInstance(): AbstractWeaponInstance
}

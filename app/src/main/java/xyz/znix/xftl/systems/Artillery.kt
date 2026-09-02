package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.AbstractProjectileWeaponInstance
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.BeamBlueprint
import kotlin.math.max

class Artillery(blueprint: SystemBlueprint) : MainSystem(blueprint) {

    private lateinit var weapon: AbstractWeaponInstance

    // Charging appears to work in vanilla based on preserving your charge
    // progress fraction while changing power. This means that if you charge
    // the bar to half way on one power then finish it on four power, it's
    // going to take the average of 50 and 20 seconds.
    private var chargeProgress: Float = 0f
        set(value) {
            field = value.coerceIn(0f..1f)
        }

    override val sortingType: SortingType get() = SortingType.ARTILLERY

    override val title: GameText? get() = weapon.type.title
    override val description: GameText? get() = weapon.type.desc

    private val barImages: List<Image> by onInit { game ->
        (1..5).map { game.getImg("img/systemUI/button_artillery_$it.png") }
    }

    // The blueprint's cooldown is correct for a level-2 artillery system.
    private val cooldown: Float get() = weapon.chargeTime * (1.5f - powerSelected * 0.25f)

    private val hardpoint by lazy {
        // Find out what our index is, in all the artillery systems specified
        // in the ship's XML.
        // Note we can't use the ship's list of artillery systems, in case
        // (in a mod) there's a ship where some artillery systems have to be
        // purchased, in which case the index in the purchased systems might
        // not match that in the XML.
        val allSystemSlots = ship.rooms.flatMap { it.systemSlots }
        val artillerySlots = allSystemSlots.filter { it.system.type == INFO.name }.sortedBy { it.spec.systemIndex }
        val index = artillerySlots.indexOf(configuration)
        require(index != -1) { "Artillery system is not in ship systems list!" }

        // Hardpoints 4 and up (zero-indexed) are used for artillery.
        // If there's more than four regular weapons however, it starts
        // after the last weapon hardpoint.
        ship.hardpoints[max(4, ship.weaponSlots ?: 4) + index].spec
    }

    override fun initialise(ship: Ship) {
        super.initialise(ship)

        val weaponBlueprint = ship.sys.blueprintManager[configuration.spec.weapon!!] as AbstractWeaponBlueprint
        weapon = weaponBlueprint.buildInstance(ship)
        weapon.bindToArtillery(this)
    }

    override fun update(dt: Float) {
        super.update(dt)

        if (powerSelected <= 0) {
            chargeProgress -= dt / DISCHARGE_TIME

            // Make the beam disappear if we were shooting when we were turned off.
            weapon.forceSetPowered(false)
            weapon.update(dt, dt, false)

            return
        }

        var amount = dt / cooldown
        if (ship.sys.debugFlags.fastWeaponCharge.set)
            amount *= 5
        if (ship.opponentCloakActive)
            amount = 0f
        chargeProgress += amount * (1f + ship.getAugmentValue(AugmentBlueprint.AUTOMATED_RELOADERS))

        if (chargeProgress >= 1f) {
            val targetShip = ship.sys.getEnemyOf(ship)
            if (targetShip != null) {
                fireAt(targetShip)
                chargeProgress = 0f
            }
        }

        weapon.forceSetPowered(true)
        weapon.update(dt, dt, true)

        // Set the weapon's time charged to match our charge progress. This is
        // so the flagship's weapons show their usual charging animation.
        weapon.timeCharged = weapon.chargeTime * chargeProgress
    }

    override fun drawBackground(g: Graphics) {
        super.drawBackground(g)

        // Draw the weapon launcher animation
        g.pushTransform()

        val anim = weapon.animation

        Weapons.translateHardpoint(g, hardpoint)
        g.translate(-anim.mountPoint.x.f, -anim.mountPoint.y.f)

        weapon.render(g)

        Weapons.drawEnemyChargeBar(ship, weapon, hackedBy?.isPoweredUp == true, isHackActive)

        g.popTransform()
    }

    override fun drawIconAndPower(
        game: InGameState,
        g: Graphics,
        isPlayer: Boolean,
        drawPower: Boolean,
        hoverGlow: Boolean,
        x: Int,
        y: Int
    ) {
        super.drawIconAndPower(game, g, isPlayer, drawPower, hoverGlow, x, y)

        if (!isPlayer)
            return

        val boxX = x + 22 - 6
        val boxY = y - 53 - 6

        val imageId = max(0, powerSelected - 1)

        // Draw the image of the box the charge bar sits in
        barImages[imageId].draw(boxX, boxY)

        val barX = boxX + 9
        val barBaseY = boxY + 59

        val maxHeight = 50 - imageId * 10 // one pixel per second of charging
        val barHeight = (chargeProgress * maxHeight).toInt()

        g.colour = Colour.white
        g.fillRect(barX.f, barBaseY.f - barHeight, 5f, barHeight.f)
    }

    override fun onJump() {
        super.onJump()

        chargeProgress = 0f
    }

    private fun fireAt(targetShip: Ship) {
        if (ship.sys.debugFlags.noEnemyFire.set && !ship.isPlayerShip)
            return

        when (val weapon = this.weapon) {
            is BeamBlueprint.BeamInstance -> {
                val startRoom = targetShip.rooms.random()
                val aim = weapon.buildLongestAim(startRoom)

                weapon.fire(aim)
            }

            // This handles flak, missiles, and lasers.
            is AbstractProjectileWeaponInstance -> {
                // The firing position won't not be right for the player ship (because
                // of the weapon rotation) if there's a non-zero weapon position,
                // but it'll be fine for vanilla.
                val anim = weapon.animation
                val firePos = hardpoint.position - anim.mountPoint + anim.firePoint

                weapon.fireFromArtillery(targetShip.rooms, firePos)
            }

            else -> error("Artillery system doesn't support weapon ${weapon.type.name} instance $weapon")
        }
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrFloat(elem, "charge", chargeProgress)

        val weaponElem = Element("weapon")
        weapon.saveToXML(weaponElem, refs)
        elem.addContent(weaponElem)
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        chargeProgress = SaveUtil.getAttrFloat(elem, "charge")

        val weaponElem = elem.getChild("weapon")
        weapon.loadFromXML(weaponElem, refs)
    }

    companion object {
        private const val DISCHARGE_TIME = 10f

        val INFO: SystemInfo = ArtilleryInfo
    }
}

private object ArtilleryInfo : SystemInfo("artillery") {
    override val canBeManned: Boolean get() = false

    override fun create(blueprint: SystemBlueprint) = Artillery(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return when (level) {
            0 -> translator["artillery_1"]
            1 -> translator["artillery_2"]
            2 -> translator["artillery_3"]
            3 -> translator["artillery_4"]
            4 -> translator["artillery_5"]
            else -> "INVALID LEVEL ${level + 1}"
        }
    }
}

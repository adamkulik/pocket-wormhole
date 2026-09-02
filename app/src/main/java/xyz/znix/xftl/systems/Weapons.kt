package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.utils.WeaponPowerManager
import xyz.znix.xftl.weapons.AbstractWeaponInstance
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class Weapons(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.WEAPONS

    val selectedTargets = TargetList()

    // Ship isn't set when constructed, and this reads the number of
    // hardpoints when it's constructed, so lazy-init it.
    val powerManager by lazy { WeaponPowerManager(this, WeaponPowerAccess()) }

    override fun update(dt: Float) {
        super.update(dt)

        var chargeMult = when (ship.sys.debugFlags.fastWeaponCharge.set) {
            true -> 10f
            false -> 1f
        }

        // When hacked, the weapons charge backwards at the same
        // speed as they charge normally.
        if (isHackActive) {
            chargeMult *= -1
        } else {
            // Don't let manning speed up discharging the weapons.

            val manningChargeSub = getSkillLevel(Skill.WEAPONS)?.let { SKILL_BONUSES[it.ordinal] / 100f } ?: 0f

            // This gets applied weirdly - 1-manningChargeSub is multiplied to the
            // weapon charge time, which alternatively means dividing
            // the charge multiplier by it.
            chargeMult /= 1f - manningChargeSub
        }

        val chargeTime = dt * chargeMult

        for (hp in ship.hardpoints) {
            val weapon = hp.weapon ?: continue
            weapon.bindToWeaponsSystem(this)
            weapon.update(dt, chargeTime, !ship.opponentCloakActive)

            if (!weapon.hasEnoughMissiles)
                setWeaponPower(weapon, false)

            // Update the weapon slide
            val slideSpeed = dt * 2
            if (weapon.isPowered) {
                weapon.slide = (weapon.slide + slideSpeed).coerceAtMost(1f)
            } else {
                weapon.slide = (weapon.slide - slideSpeed).coerceAtLeast(0f)
            }
        }

        selectedTargets.update()
    }

    override fun drawBackground(g: Graphics) {
        for (shipHardpoint in ship.hardpoints) {
            val weapon = shipHardpoint.weapon ?: continue
            val hp = shipHardpoint.spec

            g.pushTransform()

            run {
                val anim = weapon.animation

                // Apply the slide
                if (hp.slide != null) {
                    val dist = (12 * (weapon.slide - 1)).roundToInt()
                    g.translate(hp.slide.x * dist.f, hp.slide.y * dist.f)
                }

                // We start in ship space - x and y are relative to the hull

                translateHardpoint(g, hp)

                // We are now in image space - any given XY value here will line up to the pixel
                // with the same XY on the launcher image.

                g.translate(-anim.mountPoint.x.f, -anim.mountPoint.y.f)

                weapon.render(g)

                drawEnemyChargeBar(ship, weapon, hackedBy?.isPoweredUp == true, isHackActive)
            }

            g.popTransform()
        }
    }

    fun findHardpoint(weapon: AbstractWeaponInstance): Ship.Hardpoint {
        for (hp in ship.hardpoints) {
            if (hp.weapon == weapon)
                return hp
        }

        throw IllegalArgumentException("No matching hardpoint for weapon $weapon")
    }

    fun getWeaponForcedPower(slot: Int): Int {
        return powerManager.getForcedPower(slot)
    }

    fun getProjectileSpawnPos(weapon: AbstractWeaponInstance, firePoint: IPoint): IPoint {
        val hp = findHardpoint(weapon).spec
        val anim = weapon.animation

        // Fly off-screen, so it jumps over to the target ship.

        // Find the exact position to start at.
        // Take the difference between the fire and mounting positions
        // on the animation. The latter, when converted into ship-space,
        // is equal to hp.position.
        val startPos = Point(0, 0)
        startPos += firePoint
        startPos -= anim.mountPoint

        // Convert from hardpoint-space to ship-space.
        // This does everything that translateHardpoint does, but backwards
        // since matrix transforms (which the Slick operations in
        // translateHardpoint are) apply the last operation first.
        if (hp.mirror) {
            startPos.x *= -1
        }
        if (hp.rotate) {
            // To rotate counter-clockwise, set:
            // Y to X (so a right-hand line points up)
            // X to -Y (so an upwards line points left)
            val tmp = startPos.x
            startPos.x = -startPos.y
            startPos.y = tmp
        }
        startPos += hp.position

        return startPos
    }

    override fun powerStateChanged() {
        super.powerStateChanged()

        // Don't adjust the power if the weapons haven't yet been loaded.
        if (ship.sys.isCurrentlyLoadingSave)
            return

        powerManager.powerStateChanged()
    }

    override fun increasePower() {
        powerManager.increasePower()
    }

    override fun decreasePower() {
        powerManager.decreasePower()
    }

    override fun drawManningIcon(g: Graphics, x: Int, y: Int) {
        drawManningSkillIcon(x, y, getSkillLevel(Skill.WEAPONS))
    }

    /**
     * Turns a weapon on or off.
     *
     * Returns true if successful.
     */
    fun setWeaponPower(weapon: AbstractWeaponInstance, newPower: Boolean): Boolean {
        val idx = ship.hardpoints.indexOfFirst { it.weapon == weapon }
        return powerManager.setItemPower(idx, newPower)
    }

    private fun onWeaponFired() {
        addSkillPoint(Skill.WEAPONS)
    }

    // The weapons are all serialised individually by the ship, we only
    // have to serialise the selected targets.
    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        for (target in selectedTargets) {
            val targetElem = Element("target")
            target.saveToXML(targetElem, refs)
            elem.addContent(targetElem)
        }
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        val getWeapon: (Int) -> AbstractWeaponInstance = { ship.hardpoints[it].weapon!! }

        for (targetElem in elem.getChildren("target")) {
            SelectedTarget.loadFromXML(targetElem, refs, getWeapon) { target ->
                when (target) {
                    is SelectedTarget.RoomAim -> selectedTargets.targetRoom(target.weaponNumber, target.room)
                    is SelectedTarget.BeamAim -> selectedTargets.targetBeam(target.weaponNumber, target)
                }
            }
        }
    }

    inner class TargetList {
        /**
         * For a weapon that's aimed at a specific room (notably not beams),
         * target it onto a given room.
         */
        fun targetRoom(weaponId: Int, room: Room) {
            val weapon = ship.hardpoints[weaponId].weapon!!
            check(weapon is IRoomTargetingWeapon)

            targets[weaponId] = SelectedTarget.RoomAim(room, weapon, weaponId)
        }

        fun targetBeam(weaponId: Int, beam: SelectedTarget.BeamAim) {
            val weapon = ship.hardpoints[weaponId].weapon!!
            require(weapon == beam.weapon)

            targets[weaponId] = beam
        }

        fun unTarget(weaponId: Int) {
            targets.remove(weaponId)
        }

        operator fun iterator(): MutableIterator<SelectedTarget> {
            return targets.values.iterator()
        }

        fun getTarget(weaponId: Int): SelectedTarget? {
            return targets[weaponId]
        }

        fun update() {
            // Un-target all unpowered weapons
            selectedTargets.targets.values.removeIf { !it.weapon.isPowered }
        }

        fun fireChargedWeapons() {
            var fired: MutableList<SelectedTarget>? = null

            for (tgt in this) {
                // There's some weapons (eg the charge laser) that will be ready
                // before they've finished firing when the fast weapon charge
                // debug flag is set. Thus block them here to avoid crashing.
                val weapon = tgt.weapon.asWeaponInstance()
                if (!weapon.isCharged || weapon.isFiring)
                    continue

                if (fired == null)
                    fired = ArrayList()

                fired.add(tgt)

                when (tgt) {
                    is SelectedTarget.BeamAim -> tgt.beamWeapon.fire(tgt)
                    is SelectedTarget.RoomAim -> tgt.roomTargetingWeapon.fire { tgt.room }
                }

                this@Weapons.onWeaponFired()
            }

            fired?.forEach { tgt ->
                unTarget(tgt.weaponNumber)
            }
        }

        private val targets = HashMap<Int, SelectedTarget>()

        // If the user is currently aiming a beam, this marks it so the ship being targeted
        // can draw it. It's a bit ugly, but keeps all the target-drawing stuff in one place.
        var beamAiming: SelectedTarget.BeamAim? = null
    }

    private inner class WeaponPowerAccess : WeaponPowerManager.ItemAccess {
        override val count: Int get() = ship.hardpoints.size

        override fun hasItem(slot: Int): Boolean {
            return ship.hardpoints[slot].weapon != null
        }

        override fun getItemPowerDraw(slot: Int): Int {
            return ship.hardpoints[slot].weapon?.type?.power ?: 0
        }

        override fun isItemPowered(slot: Int): Boolean {
            return ship.hardpoints[slot].weapon?.isPowered ?: false
        }

        override fun setItemPowered(slot: Int, powered: Boolean) {
            val weapon = ship.hardpoints[slot].weapon ?: return

            // If a weapon runs out of missiles, it turns off even if Zoltan-powered.
            if (weapon.hasEnoughMissiles) {
                weapon.forceSetPowered(powered)
            } else {
                weapon.forceSetPowered(false)
            }
        }

        override fun setSystemPower(level: Int): Boolean {
            return this@Weapons.setSystemPower(level)
        }
    }

    companion object {
        val INFO: SystemInfo = WeaponsInfo
        val SKILL_BONUSES = listOf(10, 15, 20)

        fun translateHardpoint(g: Graphics, hp: ShipBlueprint.ParsedHardpoint) {
            g.translate(hp.position.x.f, hp.position.y.f)

            // We are now in hardpoint-xy space - x and y are relative to the hardpoint's xy,
            // but rotation is independant

            if (hp.rotate)
                g.rotate(0f, 0f, 90f)

            // We are now properly in hardpoint space, y+ is always forward and y- is always backwards

            if (hp.mirror) {
                g.scale(-1f, 1f)
            }
        }

        fun drawEnemyChargeBar(
            ship: Ship,
            weapon: AbstractWeaponInstance,
            passiveHacking: Boolean,
            activeHacking: Boolean
        ) {
            // The bars are only drawn on enemy weapons
            if (ship.isPlayerShip)
                return

            // If the weapon is off, it's charge bar is instantly hidden.
            if (!weapon.isPowered)
                return

            // Make sure the player has high enough level sensors to see the bar
            val game = ship.sys
            if (
                game.player.sensors?.showsEnemyWeaponCharge != true
                && !passiveHacking
                && !game.debugFlags.showEverything.set
            )
                return

            val enemyChargeBox = game.getImg("img/combatUI/box_hostiles_weapon_charge.png")
            val enemyChargeBar = game.getImg("img/combatUI/box_hostiles_weapon_chargebar.png")

            // Draw the charge bar on enemy ships when the player has
            // improved sensors.
            // These are level with the top of the weapon, and 10px out.
            val barX = weapon.animation.sheet.frameWidth + 10
            val barColour = when (activeHacking) {
                true -> Constants.SYSTEM_HACKED
                false -> Constants.SYS_ENERGY_REPAIR
            }
            enemyChargeBox.draw(barX, 0, barColour)

            val height = (enemyChargeBar.height * weapon.chargeProgress).roundToInt()
            val barY = 2 + enemyChargeBar.height - height
            enemyChargeBar.draw(barX + 2f, barY.f, enemyChargeBar.width.f, height.f, barColour)
        }
    }
}

private object WeaponsInfo : SystemInfo("weapons") {
    override val canBeManned: Boolean get() = true

    override fun create(blueprint: SystemBlueprint) = Weapons(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return translator["system_power"]
    }
}

sealed class SelectedTarget(val weapon: AbstractWeaponInstance, val weaponNumber: Int) {
    abstract val targetShip: Ship

    class RoomAim(val room: Room, weapon: IRoomTargetingWeapon, weaponNumber: Int) :
        SelectedTarget(weapon.asWeaponInstance(), weaponNumber) {

        val roomTargetingWeapon get() = weapon as IRoomTargetingWeapon
        override val targetShip: Ship get() = room.ship
    }

    class BeamAim(
        weapon: BeamBlueprint.BeamInstance,
        weaponId: Int,
        override val targetShip: Ship,
        val startShipPoint: IPoint
    ) : SelectedTarget(weapon, weaponId) {
        val hitRooms = ArrayList<Room>()
        var angle: Float = 0f

        /**
         * True if the beam is valid. This is only ever false for the beam
         * the player is currently aiming.
         */
        var visible: Boolean = false

        /**
         * The length of this beam swipe.
         *
         * This is lowered by AI-controlled attacks, which end before
         * the beam goes off the side of the screen.
         */
        var length = (weapon.type as BeamBlueprint).length

        val beamWeapon get() = weapon as BeamBlueprint.BeamInstance

        /**
         * Update the rooms the beam will hit, based on [angle].
         */
        fun updateHitRooms() {
            hitRooms.clear()
            visible = true

            // Loop over the pixels, marking whenever we cross one of the lines of the
            // grid all the enemy rooms are placed on.
            val lastPoint = Point(-100, -100)
            var lastRoom: Room? = null
            val tmpPoint = Point(ConstPoint.ZERO)
            for (i in 0 until length) {
                tmpPoint.x = startShipPoint.x + (i * cos(angle)).roundToInt()
                tmpPoint.y = startShipPoint.y + (i * sin(angle)).roundToInt()

                // This (among other things) divides the position by the size
                // of a room, so whenever the result changes we might
                // be in a new room.
                targetShip.screenPosToShipPos(tmpPoint)

                if (tmpPoint == lastPoint)
                    continue
                lastPoint.set(tmpPoint)

                val roomPoint = targetShip.shipToRoomPos(tmpPoint) ?: continue

                // We might still be inside the same room, however.
                if (roomPoint.room == lastRoom)
                    continue
                lastRoom = roomPoint.room

                hitRooms.add(roomPoint.room)
            }
        }
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrInt(elem, "hardpoint", weaponNumber)
        SaveUtil.addAttr(elem, "targetShip", refs[targetShip])

        when (this) {
            is RoomAim -> {
                SaveUtil.addAttr(elem, "type", "room")
                SaveUtil.addAttrInt(elem, "roomId", room.id)
            }

            is BeamAim -> {
                require(visible)
                SaveUtil.addAttr(elem, "type", "beam")
                SaveUtil.addPoint(elem, "startPos", startShipPoint)
                SaveUtil.addAttrFloat(elem, "angle", angle)
                SaveUtil.addAttrInt(elem, "length", length)
            }
        }
    }


    companion object {
        fun loadFromXML(
            elem: Element, refs: RefLoader,
            getWeapon: (Int) -> AbstractWeaponInstance,
            onLoaded: (SelectedTarget) -> Unit
        ) {
            val hardpointIndex = SaveUtil.getAttrInt(elem, "hardpoint")
            val targetShipRef = SaveUtil.getAttr(elem, "targetShip")

            val type = SaveUtil.getAttr(elem, "type")
            when (type) {
                "room" -> {
                    val roomId = SaveUtil.getAttrInt(elem, "roomId")

                    refs.asyncResolve(Ship::class.java, targetShipRef) { targetShip ->
                        val weapon = getWeapon(hardpointIndex)
                        require(weapon is IRoomTargetingWeapon)

                        val room = targetShip!!.rooms[roomId]
                        val target = RoomAim(room, weapon, hardpointIndex)
                        onLoaded(target)
                    }
                }

                "beam" -> {
                    val startPos = SaveUtil.getPoint(elem, "startPos")
                    val angle = SaveUtil.getAttrFloat(elem, "angle")
                    val length = SaveUtil.getAttrInt(elem, "length")

                    refs.asyncResolve(Ship::class.java, targetShipRef) { targetShip ->
                        val weapon = getWeapon(hardpointIndex)
                        require(weapon is BeamBlueprint.BeamInstance)

                        val target = BeamAim(weapon, hardpointIndex, targetShip!!, startPos)
                        target.angle = angle
                        target.length = length
                        target.updateHitRooms()
                        onLoaded(target)
                    }
                }

                else -> {
                    error("Unknown weapon target type '$type'")
                }
            }
        }
    }
}

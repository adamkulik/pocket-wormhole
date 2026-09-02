package xyz.znix.xftl

import org.jdom2.Element
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.crew.SkillLevel
import xyz.znix.xftl.game.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.ITooltipProvider
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.weapons.Damage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KProperty

abstract class AbstractSystem(val blueprint: SystemBlueprint) {
    val codename: String get() = blueprint.type

    // The title and description of the system shown in the game window.
    // The Artillery system uses this to copy it's weapon's title/desc.
    open val title: GameText? get() = blueprint.title
    open val description: GameText? get() = blueprint.desc

    lateinit var configuration: SystemInstallConfiguration
    var room: Room? = null

    protected val ship: Ship get() = room!!.ship

    /**
     * The number of purchased power bars.
     */
    var energyLevels: Int = 1

    var damagedEnergyLevels: Int = 0
    val damaged: Boolean get() = damagedEnergyLevels > 0
    val broken: Boolean get() = damagedEnergyLevels >= energyLevels

    private val onInitValues = ArrayList<OnInitWrapper<*>>()

    val info: SystemInfo = blueprint.info ?: error("System $codename doesn't have system info set!")

    /**
     * The power limit applied by a <status/> effect at the current beacon,
     * or null if no limit is imposed.
     */
    var scriptedPowerLimit: Int? = null
        set(value) {
            field = value?.coerceIn(0..energyLevels)
            powerLimitChanged()
        }

    /**
     * The time remaining until the ion effect wears off.
     */
    var ionTimer: Float = 0f
        set(value) {
            // The UI can't display more than nine levels since there isn't
            // an image for that long.
            field = min(value, 9 * TIME_PER_ION)
        }

    /**
     * If non-null, this is the ion-induced limit for how much
     * power this system can use.
     *
     * This is based on the power selected when the ion first hit.
     */
    var ionPowerLimit: Int? = null

    open val effectiveIonPowerLimit: Int? get() = ionPowerLimit

    // Note that system cooldowns set the ion timer without adding
    // any ion damage, to lock in the power. Hence we need to check
    // the timer instead of the damage.
    val isIonised: Boolean get() = ionTimer > 0

    /**
     * If non-null, this is the hacking system that's attacking this system.
     */
    var hackedBy: Hacking? = null

    /**
     * True if this system is being hacked by a hacking system currently
     * running its hacking pulse.
     */
    val isHackActive: Boolean get() = hackedBy?.active == true

    /**
     * The number of intact energy bars in the system. Ion damage is subtracted from this.
     */
    open val undamagedEnergy: Int
        get() {
            var available = energyLevels

            // Subtract of normal damage *before* applying the power limit,
            // since the unavailable levels act as buffer points.
            available -= damagedEnergyLevels

            scriptedPowerLimit?.let { limit ->
                available = min(limit, available)
            }

            // If ion damage is applied, we can't increase the amount
            // of power beyond the limit it set.
            effectiveIonPowerLimit?.let { available = min(available, it) }

            return max(available, 0)
        }

    var repairProgress: Float = 0f
        private set(value) {
            field = if (damaged) value else 0f
        }

    var damageProgress: Float = 0f
        private set(value) {
            field = if (broken) 0f else value
        }

    /**
     * Get the crewmember that's currently manning this system.
     */
    val manningCrew: LivingCrew?
        get() {
            return room!!.crew.firstOrNull { it.currentAction == AbstractCrew.Action.MANNING } as? LivingCrew
        }

    /**
     * True if there should be a white locking box around the system's power,
     * which is shown in hacking/cloaking/mind control when those systems
     * are active.
     */
    open val hasWhiteLockingBox: Boolean get() = false

    open fun update(dt: Float) {
        if (!damaged) {
            repairProgress = 0f
        } else if (ship.isAutoScout && room!!.breaches.all { it == null }) {
            // 8% per second is the human speed, it's 1/3 of that for the auto repair
            repair(0.08f / 3 * dt)
        } else if (room?.crew?.none { it.mode == AbstractCrew.SlotType.CREW } == true) {
            // No-one is in the room
            repairProgress = 0f
        }

        // Damage is shared between fires and boarders, we can undo it
        // if there's neither of them here.
        val hasIntruders = room?.crew?.any { it.mode == AbstractCrew.SlotType.INTRUDER } == true
        val hasFire = room?.fires?.any { it != null } == true
        if (broken || !(hasIntruders || hasFire))
            damageProgress = 0f

        // Check both the damage and timer to avoid somehow getting stuck where
        // one of them is zero and the other isn't.
        if (ionPowerLimit != null || ionTimer > 0f) {
            if (ship.sys.debugFlags.noIon.set)
                ionTimer = 0f

            ionTimer -= dt
            if (ionTimer <= 0) {
                ionPowerLimit = null
                ionTimer = 0f
                powerLimitChanged()
            }
        }

        // Check if the hacking probe is still in place.
        // This is checked here rather than the hacking system
        // un-setting hackedBy to make the game more robust
        // if one of the ships jumps away or something similar.
        hackedBy?.let {
            if (!it.checkStillAttacking(this))
                hackedBy = null
        }
    }

    open fun drawBackground(g: Graphics) {
    }

    open fun drawForeground(g: Graphics) {
    }

    open fun initialise(ship: Ship) {
        for (item in onInitValues) {
            item.doInit(ship.sys)
        }
    }

    open fun drawRoom(g: Graphics) {
        // Draw the system icon
        val room = room!!
        val img = room.ship.sys.getImg(blueprint.roomIconPath)

        val colour = when {
            damagedEnergyLevels == energyLevels -> Constants.SYSTEM_BROKEN
            damagedEnergyLevels > 0 -> Constants.SYSTEM_DAMAGED
            else -> Constants.SYSTEM_NORMAL
        }

        img.draw(
            (room.offsetX + (room.pixelWidth / 2f - img.width / 2f).toInt()).f,
            (room.offsetY + (room.pixelHeight / 2f - img.height / 2f).toInt()).f,
            colour
        )
    }

    open fun dealDamage(damage: Int, ionDamage: Int) {
        // Add the specified amount of damage, but avoid having more damage than we have power (which
        // would come to a negative amount of available power)
        damagedEnergyLevels = (damagedEnergyLevels + damage).coerceAtMost(energyLevels)

        // Apply ion damage. If we're not already ion-locked, use
        // the current power (don't mind the ugly hack to get it).
        // Otherwise, reduce relative to the current ion limit which
        // is included in undamagedEnergy.
        // The reason we don't always use powerSelected is because
        // of things like weapons and shields, where ions would otherwise
        // always take down multiple power worth of stuff.
        var basePower = undamagedEnergy
        if (ionPowerLimit == null && this is MainSystem) {
            basePower = min(basePower, this.powerSelected)
        }
        ionPowerLimit = basePower - ionDamage
        ionTimer += 5f * ionDamage

        powerLimitChanged()
    }

    open fun onJump() {
    }

    /**
     * Something - anything - happened to the system's power level.
     * Systems should generally override this rather than dealDamage,
     * to include stuff like ionisation or a Zoltan leaving the room.
     */
    open fun powerStateChanged() {
    }

    /**
     * Called when this system's maximum power limit changed, such
     * as from taking damage.
     */
    open fun powerLimitChanged() {
        powerStateChanged()
    }

    /**
     * Contributes some progress towards repairing this system.
     *
     * Returns true if the system was repaired by a level, and
     * some repair experience should be awarded.
     */
    fun repair(progress: Float): Boolean {
        var modifiedProgress = progress

        // If a system is hacked, repairs run at half-speed
        if (hackedBy?.isPoweredUp == true) {
            modifiedProgress /= 2f
        }

        repairProgress += modifiedProgress

        if (repairProgress < 1f)
            return false

        repairProgress = 0f

        damagedEnergyLevels--
        powerLimitChanged()

        return true
    }

    /**
     * Attack this room with sabotage or fire damage.
     *
     * Returns true if this bit of damage broke the system, and experience should be awarded.
     */
    fun attack(damage: Float): Boolean {
        if (broken) {
            damageProgress = 0f
            return false
        }

        damageProgress += damage

        if (damageProgress < 1f)
            return false

        damageProgress = 0f

        // This bar of the system is broken.
        // Don't use ship.damage, as that'd spawn a damage popup number which
        // we don't want unless the system is fully broken.
        if (!ship.sys.debugFlags.noDmg.set) {
            dealDamage(1, 0)
        }

        if (!broken) {
            return true
        }

        // When the system breaks, it does a hull point of damage.
        ship.damage(room!!, Damage.hullOnly(1))

        // Play the explosion animation
        val animation = ship.sys.animations["explosion1"]
        ship.playCentredAnimation(animation, room!!.pixelCentre)

        return true
    }

    open val iconColourName: String
        get() = when {
            damagedEnergyLevels == energyLevels -> "red"
            damagedEnergyLevels > 0 -> "orange"
            this is MainSystem && powerSelected == 0 -> "grey"
            else -> "green"
        }

    /**
     * Draw the system icon and it's power bars.
     *
     * [isPlayer] is true if the system is being rendered as part of the
     * player's ship. This should be used to hide stuff like the clonebay
     * queue on an enemy ship.
     *
     * [drawPower] is false for enemy ships without top-level sensors,
     * otherwise it's true.
     *
     * [hoverGlow] is set when the player is hovering over one of their
     * main systems, and the system icon should be highlighted with a glow.
     *
     * [x] and [y] specify the top-left corner of the box that contains
     * the system icon, NOT including the 19-pixel glow around the icon.
     */
    open fun drawIconAndPower(
        game: InGameState,
        g: Graphics,
        isPlayer: Boolean,
        drawPower: Boolean,
        hoverGlow: Boolean,
        x: Int, y: Int
    ) {
        // Account for the 19px of padding
        val iconX = x - 19
        val iconY = y - 19

        if (!drawPower) {
            // TODO handle when the system is ionised
            game.getImg("img/icons/s_${codename}_${iconColourName}1.png").draw(iconX, iconY)
            return
        }

        if (!isIonised) {
            // TODO flash blue when hacking/mind control/cloaking/backup battery is active
            val icon = game.getImg("img/icons/s_${codename}_${iconColourName}1.png")

            // Vanilla draws the icon three times when it's hovered, so the small amount of glow
            // around the image becomes a lot stronger.
            if (hoverGlow) {
                icon.draw(iconX, iconY)
                icon.draw(iconX, iconY)
                icon.draw(iconX, iconY)
            } else {
                icon.draw(iconX, iconY)
            }
        } else {
            // Levels are rounded up, so <5s shows 1, <10s shows 2, etc.
            val levels = min(9, ceil(ionTimer / TIME_PER_ION).toInt())

            // Use the appropriate image to match whether this is a subsystem or not
            val imgType = if (this is MainSystem) "ring" else "octa"

            game.getImg("img/icons/locking/s_${imgType}_${levels}_base.png").draw(iconX, iconY)

            // Draw the ring around the outside.
            val timerImg = game.getImg("img/icons/locking/s_${imgType}_timer.png")

            // Figure out the angle around this image we need to cut it at
            val numSegments = 12 // The ring is split up into 12 segments
            val progress = (ionTimer / TIME_PER_ION).rem(1f) // How full the ring is, 0-1
            var segments = ceil(numSegments * progress).toInt() // There's always at least 1 segment visible

            // If the timer is an exact multiple of five seconds,
            // progress will be zero and thus no segments will be
            // displayed. Fix that, as it should be showing all the
            // segments.
            if (segments == 0)
                segments = numSegments

            // Find the angle of the end point, where 0 is up and +ve is CW
            val angle = TWO_PI * segments / numSegments

            // Convert that into 0 is right and +ve is CCW
            val upAngle = TWO_PI / 4
            val endAngle = TWO_PI + upAngle // End pointing straight up
            val startAngle = endAngle - angle

            g.drawImagePieSlice(
                timerImg,
                iconX.f, iconY.f,
                startAngle, endAngle,
                Colour.white
            )
        }

        val barX = x + 5

        // Draw the power bars, so that systems can override this relatively easily.
        val bottomBarBaseY = y - 11 + 6
        val topBarY = drawPowerBars(g, barX, bottomBarBaseY, hoverGlow)

        // If a status icon (ion or hacking) is drawn, this is the Y of it's base.
        var statusIconY = topBarY - 3

        fun drawIonOrHackBar() {
            val barsHeight = bottomBarBaseY - topBarY

            // Two-pixel-wide rectangle around the energy bars
            g.drawRect(barX - 3f, topBarY - 3f, 3f + 16f + 2f, 3f + barsHeight + 2f)
            g.drawRect(barX - 2f, topBarY - 2f, 2f + 16f + 1f, 2f + barsHeight + 1f)

            statusIconY -= 3
        }

        var showManning = false

        if (isHackActive) {
            // When this system is actively being hacked, draw a purple
            // outline around it.
            g.colour = Constants.SYSTEM_HACKED
            drawIonOrHackBar()
        } else if (hasWhiteLockingBox) {
            g.colour = Colour.white
            drawIonOrHackBar()

            // Draw the padlock icon at the top
            val lockImg = game.getImg("img/icons/locking/s_lock_white.png")
            lockImg.draw(barX + 2f - 6f, statusIconY - 21f)

            // If we've got ion damage and a hacking probe connected,
            // the latter is shifted upwards.
            statusIconY -= 21
        } else if (isIonised) {
            // If the system is ionised, draw the 'locked' bar around it
            g.colour = Constants.SYSTEM_IONISED
            drawIonOrHackBar()

            // Draw the padlock icon at the top
            val lockImg = game.getImg("img/icons/locking/s_lock.png")
            lockImg.draw(barX + 2f - 6f, statusIconY - 21f)

            // If we've got ion damage and a hacking probe connected,
            // the latter is shifted upwards.
            statusIconY -= 21
        } else {
            showManning = true
        }

        // If this room has a hacking probe attached and turned on - whether
        // or not it's in a hacking pulse - then draw the hacking laptop icon.
        if (hackedBy?.isPoweredUp == true) {
            // Draw the hacking icon at the top
            val lockImg = game.getImg("img/icons/s_hacked.png")
            lockImg.draw(barX + 1f - 9f, statusIconY - 24f)
            showManning = false
        }

        // If the system is being burnt or sabotaged, draw the fist above it
        val hasIntruders = room!!.crew.any { it.mode == AbstractCrew.SlotType.INTRUDER }
        val hasFires = room!!.fires.any { it != null }

        if (damageProgress != 0f && hasIntruders) {
            val sabotageIcon = game.getImg("img/icons/s_sabatoge.png") // (sic)
            sabotageIcon.draw(barX - 9, statusIconY - 26)
            statusIconY -= 24
            showManning = false
        }

        if (hasFires) {
            val fireIcon = game.getImg("img/icons/s_fire2.png")
            fireIcon.draw(barX - 9, statusIconY - 24)
            showManning = false
        }

        // Only draw the manning icon if there isn't another status icon
        if (showManning) {
            drawManningIcon(g, barX, statusIconY)
        }
    }

    open fun createTooltip(): ITooltipProvider {
        return SystemButtonTooltip(ship.sys, this)
    }

    protected open fun drawManningIcon(g: Graphics, x: Int, y: Int) {
    }

    protected fun drawManningSkillIcon(x: Int, y: Int, level: SkillLevel?) {
        val iconPath = when (level) {
            SkillLevel.BASE -> "img/systemUI/manning_white.png"
            SkillLevel.PARTIAL -> "img/systemUI/manning_green.png"
            SkillLevel.MAX -> "img/systemUI/manning_yellow.png"
            null -> "img/systemUI/manning_outline.png"
        }

        val img = ship.sys.getImg(iconPath)
        img.draw(x + 1, y + 3 - img.height)
    }

    /**
     * Can this system be manned by the given crewmember. This checks whether
     * the system is mannable at all, not just whether the given crewmember
     * can man it.
     *
     * If true, then [configuration].computerPoint must be non-null.
     */
    open fun isMannableBy(crew: AbstractCrew): Boolean {
        // Note we have to check canBeManned, in case the ship has a manually
        // configured computer location for an un-mannable system.
        // (canBeManned enables automatic computer placement)
        return configuration.computerPoint != null
                && hackedBy?.isPoweredUp != true
                && undamagedEnergy > 0
                && info.canBeManned
                && !isIonised
                && scriptedPowerLimit == null
    }

    /**
     * Draw the power bars themselves, returning the Y of the top of the top bar.
     *
     * @param yOfBottom The Y of the bottom of the lowest bar in the stack.
     */
    protected abstract fun drawPowerBars(g: Graphics, x: Int, yOfBottom: Int, hoverGlow: Boolean): Int

    /**
     * Create any system-specific buttons that go next to the power button in the main UI.
     *
     * For example, this is for stuff like the cloak and teleport buttons on those systems.
     */
    open fun makeExtraButtons(powerPos: IPoint): List<Button> {
        return emptyList()
    }

    fun addSkillPoint(skill: Skill) {
        manningCrew?.addSkillPoint(skill)
    }

    /**
     * Get the level this system is manned to, or null if there's no-one manning it.
     *
     * This accounts for manning auto-scouts.
     */
    fun getSkillLevel(skill: Skill): SkillLevel? {
        // It seems there's a fake crewmember in every room?
        // This only applies to fully-repaired systems, I think.
        // https://www.reddit.com/r/ftlgame/comments/2e30zc/question_re_autoscouts/
        if (ship.isAutoScout && !damaged)
            return SkillLevel.BASE

        return manningCrew?.getSkillLevel(skill)
    }

    /**
     * This is called whenever a hotkey is pressed. It's how systems should
     * implement all key-based input.
     */
    open fun hotkeyPressed(key: Hotkey) {
    }

    /**
     * This is used to create a delegated property that's initialised
     * when the system is initialised. This can be used to load stuff
     * from SlickGame, which is passed as the argument to the lambda.
     *
     * This is used very similarly to [lazy]:
     *
     * val myImage by onInit { it.getImg("...") }
     *
     * This should be preferred to loading images and sounds when
     * required, as this means missing resources will fail when
     * the ship is loaded rather than when the system is used.
     * Obviously this makes finding these issues a lot quicker.
     */
    protected fun <T> onInit(initializer: (InGameState) -> T): OnInitWrapper<T> {
        val wrapper = OnInitWrapper(initializer)
        onInitValues += wrapper
        return wrapper
    }

    open fun saveToXML(elem: Element, refs: ObjectRefs) {
        elem.setAttribute("name", blueprint.name)

        // Add the level as an attribute, since most of the other tags
        // are optional we might be able to collapse the element (when
        // it takes the form of <thing/> rather than <thing></thing>).
        elem.setAttribute("level", energyLevels.toString())

        SaveUtil.addAttrInt(elem, "damage", damagedEnergyLevels)
        SaveUtil.addTagInt(elem, "scriptedLimit", scriptedPowerLimit, null)
        SaveUtil.addTagFloat(elem, "ionTimer", ionTimer, 0f)
        SaveUtil.addTagInt(elem, "ionPowerLimit", ionPowerLimit, null)
        SaveUtil.addTagFloat(elem, "repairProgress", repairProgress, 0f)
        SaveUtil.addTagFloat(elem, "damageProgress", damageProgress, 0f)

        // Don't save hackedBy, it'll be set by the enemy hacking system when it loads.

        saveSystem(elem, refs)
    }

    open fun loadFromXML(elem: Element, refs: RefLoader) {
        require(elem.getAttributeValue("name") == blueprint.name)

        energyLevels = elem.getAttributeValue("level")!!.toInt()

        damagedEnergyLevels = SaveUtil.getAttrInt(elem, "damage")
        scriptedPowerLimit = SaveUtil.getOptionalTagInt(elem, "scriptedLimit")
        ionTimer = SaveUtil.getOptionalTagFloat(elem, "ionTimer") ?: 0f
        ionPowerLimit = SaveUtil.getOptionalTagInt(elem, "ionPowerLimit")
        repairProgress = SaveUtil.getOptionalTagFloat(elem, "repairProgress") ?: 0f
        damageProgress = SaveUtil.getOptionalTagFloat(elem, "damageProgress") ?: 0f

        loadSystem(elem, refs)

        powerLimitChanged()
    }

    /**
     * This is a version of [saveToXML] that saves the system-unique data.
     *
     * This is kept separate from [saveToXML] so it can be marked abstract
     * to force systems to implement it.
     */
    protected abstract fun saveSystem(elem: Element, refs: ObjectRefs)

    // Same thing as saveSystem
    protected abstract fun loadSystem(elem: Element, refs: RefLoader)

    protected class OnInitWrapper<T>(private val fn: (InGameState) -> T) {
        private var storedValue: T? = null

        operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
            return storedValue ?: error("On-init property used before initialisation!")
        }

        fun doInit(game: InGameState) {
            storedValue = fn(game)
        }
    }

    companion object {
        /**
         * The time in seconds a system is locked per ion damage.
         */
        const val TIME_PER_ION = 5f
    }
}

/**
 * Information about how a system is installed into a room - this is both
 * the system and the location of its computer, along with any XML data
 * that's specified in the ship blueprint.
 *
 * This is based on [ShipBlueprint.ParsedSystem], but with information like
 * the computer position calculated based on the loaded ship.
 */
class SystemInstallConfiguration(
    val spec: ISystemConfiguration,
    game: InGameState,
    val room: Room
) {
    val system: SystemBlueprint = game.blueprintManager[spec.systemName] as SystemBlueprint

    val computerPoint: ConstPoint?
    val computerDirection: Direction?
    val obstructionPoint: ConstPoint?

    val isInstalled: Boolean get() = room.system?.blueprint == system

    // Parse out the computer point and direction
    init {
        val defaultCompDir: Direction?
        var defaultCompSlot: Int?

        // Load defaults
        when (system.info) {
            Weapons.INFO -> {
                defaultCompDir = Direction.UP
                defaultCompSlot = 1
            }

            Engines.INFO -> {
                defaultCompDir = Direction.DOWN
                defaultCompSlot = 2
            }

            Shields.INFO -> {
                defaultCompDir = Direction.LEFT
                defaultCompSlot = 0
            }

            Piloting.INFO -> {
                defaultCompDir = Direction.RIGHT
                defaultCompSlot = 0
            }

            // For clonebay/medbay, this sets the position of the obstruction
            Clonebay.INFO -> {
                defaultCompSlot = 1
                defaultCompDir = null
            }

            Medbay.INFO -> {
                defaultCompSlot = 1
                defaultCompDir = null
            }

            else -> {
                defaultCompDir = null
                defaultCompSlot = null
            }
        }

        // If the default computer slot doesn't fit the room, move it to slot zero.
        if (defaultCompSlot != null && defaultCompSlot >= room.cellCount) {
            defaultCompSlot = 0
        }

        var compDir = spec.slotDirection ?: defaultCompDir
        var compPoint = (spec.slotNumber ?: defaultCompSlot)?.let {
            // -2 appears to be used for the medbay to indicate no obstruction in 2-cell medbays
            if (it == -2)
                return@let null
            room.slotToPoint(it).const
        }

        // Pick a room with the invalid computer position if it's a mannable system
        // and the computer is not set.
        if (compPoint == null && system.info?.canBeManned == true) {
            compPoint = ConstPoint(999, 999)
        }

        // If the computer position is invalid (outside the room), just find a point
        // that makes sense (doesn't overlap a door).
        if (compPoint != null && !room.containsRelative(compPoint)) {
            // Take a range of the valid X values
            val validPlaces = (0 until room.width).asSequence().flatMap { x ->
                // Flatmap each of them to the valid positions in that column
                (0 until room.height).asSequence().map { y -> ConstPoint(x, y) }
            }.flatMap {
                // Expand each position into two valid edges
                // Note that in a 1x2/2x1 room this doesn't cover all edges - close enough though
                val horizontal = if (it.x == 0) Direction.LEFT else Direction.RIGHT
                val vertical = if (it.y == 0) Direction.UP else Direction.DOWN
                sequenceOf(Pair(it, horizontal), Pair(it, vertical))
            }.filter { pos ->
                // Filter out anything that intersects with a door
                room.doors.none { it.roomPos(room) posEq pos.first && it.dirFor(room) == pos.second }
            }.sortedBy { pos ->
                // Prefer things that aren't on the same tile as a door
                if (room.doors.none { it.roomPos(room) posEq pos.first }) 0 else 1
            }

            val place = validPlaces.first()
            compPoint = place.first
            compDir = place.second
        }

        // The medbay/clonebay uses the computer to represent a cell that is obstructed.
        if (system.info?.isComputerObstruction == true && compPoint != null) {
            obstructionPoint = compPoint
            compPoint = null
        } else {
            obstructionPoint = null
        }

        // Except for the medbay and clonebay - which are handled above - all systems
        // that specify a computer point must also specify a direction.
        if (compPoint != null) {
            requireNotNull(compDir) { "Cannot set computer point but not computer direction for system ${system.type}" }
        }

        // If the computer point isn't set, it doesn't make sense for the direction to be.
        // Some mods can set this, eg fed tanker C from Dyno's Hangar.
        if (compPoint == null && compDir != null) {
            println("[WARN] Cannot set the computer direction but not point for system ${system.type}")
            compDir = null
        }

        computerPoint = compPoint
        computerDirection = compDir
    }
}

/**
 * Describes this type of system, in a way that can be attached to the blueprint.
 *
 * This contains information that's coupled to the blueprint, but we don't want
 * to actually put into the [SystemBlueprint] class since it varies between systems.
 */
abstract class SystemInfo(
    val name: String
) {
    abstract val canBeManned: Boolean
    open val isComputerObstruction: Boolean get() = false
    open val isSubSystem: Boolean get() = false

    abstract fun create(blueprint: SystemBlueprint): AbstractSystem

    abstract fun getLevelName(level: Int, translator: Translator): String
}

/**
 * Describes how a system can be placed in a room.
 *
 * This either comes from the ship blueprint (via [ShipBlueprint.ParsedSystem])
 * or from the ship editor ([xyz.znix.xftl.hangar.FinalisedEditableSystem]).
 */
interface ISystemConfiguration {
    val systemName: String

    val slotNumber: Int?
    val slotDirection: Direction?

    val startingPower: Int

    /**
     * The index of this configuration within the ship's list of systems.
     *
     * This is for figuring out the order the artillery systems are specified
     * in, as that determines what hardpoint they fire from.
     */
    val systemIndex: Int

    val availableByDefault: Boolean

    /**
     * Used for calculations by the ship generator.
     * The flagship notably doesn't have it's maximum power set, so
     * use the maximum specified in the system blueprint in that case.
     */
    val aiMaxPower: Int?

    /**
     * The room interior image
     */
    val interiorImage: String?

    /**
     *  For artillery weapons, this is the weapon they're using internally.
     */
    val weapon: String?
}

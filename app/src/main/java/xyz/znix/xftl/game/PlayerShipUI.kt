package xyz.znix.xftl.game

import org.jdom2.Element
import org.newdawn.slick.geom.Rectangle
import xyz.znix.xftl.*
import xyz.znix.xftl.Constants.*
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.crew.SkillLevel
import xyz.znix.xftl.drones.AbstractDrone
import xyz.znix.xftl.environment.PulsarEnvironment
import xyz.znix.xftl.environment.SunEnvironment
import xyz.znix.xftl.game.InGameState.RoomClickListener
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.*
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.sector.Event
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon
import java.util.*
import kotlin.math.*

class PlayerShipUI(val ship: Ship, private val game: InGameState) {
    private val font = game.getFont("HL2", 2f)
    private val weaponNameText = game.getFont("JustinFont8")
    private val crewNameText = game.getFont(CREW_NAME_FONT)
    private val weaponNumberFont = game.getFont("c&c")
    private val numberFont = game.getFont("num_font")
    private val oxygenEvadeFont = game.getFont("JustinFont10")
    private val tooltipFont = game.getFont("JustinFont11Bold")

    private val numberDeltaFont = game.getFont("HL2", 3f)
    private val numberDeltaFontBg = game.getFont("HL1", 3f)

    private val powerUpSound = game.sounds.getSample("powerUpSystem")
    private val powerUpFailSound = game.sounds.getSample("powerUpFail")
    private val powerDownSound = game.sounds.getSample("powerDownSystem")

    private val cursorNormal = game.getCursor("img/mouse/pointerInvalid.png")
    private val cursorHover = game.getCursor("img/mouse/pointerValid.png")
    private val cursorTeleRetrieveInvalid = game.getCursor("img/mouse/mouse_teleport_in2.png")
    private val cursorTeleRetrieveValid = game.getCursor("img/mouse/mouse_teleport_in1.png")
    private val cursorTeleSendInvalid = game.getCursor("img/mouse/mouse_teleport_out2.png")
    private val cursorTeleSendValid = game.getCursor("img/mouse/mouse_teleport_out1.png")
    private val cursorMindInvalid = game.getCursor("img/mouse/mouse_mind_valid.png")
    private val cursorMindValid = game.getCursor("img/mouse/mouse_mind.png") // Valid doesn't have the _valid path
    private val cursorHacking = game.getCursor("img/mouse/mouse_hacking.png")

    private var selectWeaponClickEvent: RoomClickListener? = null
    private var targetingSelectedWeapon: Int? = null

    private val selectedCrew: MutableList<AbstractCrew> = ArrayList()
    private val hoveredCrew: MutableList<AbstractCrew> = ArrayList()
    private var skillsHoveredCrew: AbstractCrew? = null

    private val buttons = ArrayList<Button>()

    /**
     * The currently shown window. When a window is shown, the main UI is darkened, buttons are locked
     * and all input is sent to the window.
     */
    private var currentWindow: Window? = null

    /**
     * The currently-open pause or options menu.
     *
     * This is handled separately from all other windows, since it can
     * be opened on top of them.
     */
    private var pauseWindow: Window? = null

    private val hullWarningLines = listOf(
        ConstPoint(361, 51),
        ConstPoint(393, 82),
        ConstPoint(482, 82)
    )
    private val hullWarning25 = WarningFlasher(game, ConstPoint(437, 100), "warning_hull_25", true, hullWarningLines)
    private val hullWarning50 = WarningFlasher(game, ConstPoint(437, 100), "warning_hull_50", true, hullWarningLines)
    private val hullWarning75 = WarningFlasher(game, ConstPoint(437, 100), "warning_hull_75", true, hullWarningLines)

    private val oxygenWarningLines = listOf(
        ConstPoint(99, 137),
        ConstPoint(116, 170),
        ConstPoint(216, 170)
    )
    private val oxygenWarning = WarningFlasher(
        game, ConstPoint(167, 188),
        "warning_oxygen", true, oxygenWarningLines, alwaysOn = true
    )

    private val shieldsWarning = WarningFlasher(
        game, ConstPoint(204, 132), "warning_shields", true,
        listOf(
            ConstPoint(129, 93),
            ConstPoint(151, 114),
            ConstPoint(250, 114)
        )
    )

    private val sunWarning = WarningFlasher(
        game, ConstPoint(825, 64), "warning_solar_flare", false, alwaysOn = true
    )
    private val pulsarWarning = WarningFlasher(
        game, ConstPoint(825, 64), "warning_ion_pulse", false, alwaysOn = true
    )

    private val hullTooltip = FixedStandardTooltip(game, "tooltip_hull")
    private val scrapTooltip = FixedStandardTooltip(game, "tooltip_scrapCount")
    private val fuelTooltip = FixedStandardTooltip(game, "tooltip_fuelCount")
    private val missileTooltip = FixedStandardTooltip(game, "tooltip_missileCount")
    private val droneTooltip = FixedStandardTooltip(game, "tooltip_droneCount")
    private val shieldTooltip = FixedStandardTooltip(game, "tooltip_shieldDisplay")
    private val evasionTooltip = FixedStandardTooltip(game, "tooltip_evadeDisplay")
    private val oxygenTooltip = FixedStandardTooltip(game, "tooltip_oxygenDisplay")

    private var lastHealth: Int = -1

    val isWindowOpen: Boolean get() = currentWindow != null || pauseWindow != null

    private val lastResources = ResourceSet()
    private val resourceDeltaAnimations = ArrayList<ResourceDeltaText>()

    private var crewSelectionRectangle: Pair<ConstPoint, Point>? = null
    private val isCrewSelectionPoint: Boolean
        get() = crewSelectionRectangle?.let { it.first.distToSq(it.second) < SELECTION_BOX_SIZE } ?: false

    // Non-null when the player is aiming a beam weapon
    private var beamTargeting: SelectedTarget.BeamAim? = null
        set(value) {
            field = value

            // Show the aiming-in-progress beam on the enemy ship
            ship.weapons!!.selectedTargets.beamAiming = value
        }
    private val beamTargetingStartPos = Point(0, 0)

    // Set by render
    private var width: Int = 500
    private var height: Int = 500

    // The last position of the cursor. Try to avoid using this if possible, there's
    // usually a better way to do stuff with a button.
    private val mousePos = Point(0, 0)

    // The time remaining on the insufficient scrap flash animation
    private var insufficientScrapTimer: Float = 0f

    // If true, buttons that are tightly intertwined with the rest of the UI
    // (for example, the weapon and drone buttons) will be re-populated.
    private var updatingButtons = false

    // The longest time it takes to charge a weapon - this is used for drawing
    // the weapon charge bars.
    private var maxWeaponChargeTime: Float = 1f

    // The position of the weapons box
    val weaponBoxY get() = height - 113

    // Whether or not we've previously seen a store UI at this sector.
    // This is used to auto-open the store when the dialogue is finished.
    private var storeAlreadyOpened = false

    private val crewX: Int = 10
    private var crewBaseY: Int = 0 // Set while rendering
    private var lastCrewCount: Int = 0

    // These time the message that shows up for a second when you save/restore
    // your crew positions.
    private var saveCrewPosMessageTimer: Float = 0f
    private var saveCrewPosMessageText: String = ""

    /**
     * If the user is selecting a room to teleport to/from, this is non-null.
     * True if sending crew to an enemy ship, false if receiving.
     */
    val teleportMode: Boolean? get() = (game.clickEvent as? TeleportRoomListener)?.send

    val isSelectingHackingTarget: Boolean get() = game.clickEvent is HackingRoomListener
    val isSelectingMindControlTarget: Boolean get() = game.clickEvent is MindControlRoomListener

    init {
        updateButtons()
    }

    fun updateButtons() {
        buttons.clear()
        updatingButtons = true

        var nextPos = ConstPoint(531, 29)

        val jump = Buttons.JumpButton(nextPos, ship, game) {
            openJumpMap()
        }
        nextPos += ConstPoint(101, 0)

        val ship = Buttons.ShipButton(nextPos, game) {
            showShipWindow(ShipWindow.Tab.UPGRADES)
        }
        nextPos += ConstPoint(ship.size.x + 17, 0)

        if (game.currentBeacon?.hasStore == true) {
            val store = Buttons.StoreButton(nextPos, game) {
                showStoreWindow()
            }
            nextPos += ConstPoint(store.size.x + 17, 0)
            buttons += store
        }

        val settings = SimpleButton(
            game, nextPos, ConstPoint(41, 41), ConstPoint(7, 7),
            game.getImg("img/statusUI/top_optionswrench_on.png"),
            game.getImg("img/statusUI/top_optionswrench_select2.png"),
            FixedDelayedTooltip(game, "open_options")
        ) {
            showPauseWindow()
        }

        buttons += jump
        buttons += ship
        buttons += settings
    }

    fun mouseClick(button: Int, x: Int, y: Int, playerShipPosition: IPoint) {
        pauseWindow?.let { win ->
            win.mouseClick(button, x, y)
            return
        }

        currentWindow?.let { win ->
            win.mouseClick(button, x, y)
            return
        }

        // When we're in beam targeting mode, block other mouse input.
        if (beamTargeting != null) {
            if (button == Input.MOUSE_LEFT_BUTTON) {
                targetBeamWeapon()
            } else if (button == Input.MOUSE_RIGHT_BUTTON) {
                beamTargeting = null
            }
            return
        }

        for (btn in buttons) {
            val hit = btn.mouseDown(button, x, y)
            if (hit) return
        }

        // Check if we're clicking on a door
        if (button == Input.MOUSE_LEFT_BUTTON) {
            for (door in ship.doors) {
                val hit = door.click(x - playerShipPosition.x, y - playerShipPosition.y)
                if (hit)
                    return
            }
        }

        // Select players
        if (button == Input.MOUSE_LEFT_BUTTON) {
            crewSelectionRectangle = Pair(ConstPoint(x, y), Point(x, y))
        }

        // Move players if they're right-clicking somewhere
        val shipMousePos = Point(x, y)
        shipMousePos -= playerShipPosition

        val roomPoint = Point(shipMousePos)
        roomPoint.divideFloor(ROOM_SIZE)
        roomPoint -= ship.offset
        for (room in ship.rooms) {
            if (!room.containsAbsolute(roomPoint))
                continue

            if (button == Input.MOUSE_RIGHT_BUTTON) {
                for (crew in selectedCrew) {
                    // Skip mind-controlled crew that remained selected.
                    if (!crew.playerControllable)
                        continue

                    // Exclude crew that are boarding an enemy ship, as
                    // this could cause a crash.
                    if (crew.room.ship == ship)
                        crew.setTargetRoom(room)
                }

                return
            }
        }
    }

    fun mouseUp(button: Int, x: Int, y: Int, playerShipPosition: IPoint) {
        pauseWindow?.let { win ->
            win.mouseReleased(button, x, y)
            return
        }

        currentWindow?.let { win ->
            win.mouseReleased(button, x, y)
            return
        }

        crewSelectionRectangle?.let {
            if (button != Input.MOUSE_LEFT_BUTTON) return@let

            updateHoveredCrew(x, y, playerShipPosition)

            selectedCrew.clear()
            selectedCrew.addAll(hoveredCrew)

            crewSelectionRectangle = null
        }
    }

    fun mouseScroll(change: Int) {
        pauseWindow?.let { win ->
            win.mouseScroll(change)
            return
        }

        currentWindow?.let { win ->
            win.mouseScroll(change)
            return
        }
    }

    fun crewScreenPos(crew: AbstractCrew, playerShipPosition: IPoint): IPoint {
        if (crew.room.ship == ship) {
            return ConstPoint(crew.screenX, crew.screenY) + playerShipPosition
        }

        // The crewmember must be on the enemy ship, add that position on
        return ConstPoint(crew.screenX, crew.screenY) + game.enemyPosition
    }

    fun isCrewHovered(crew: AbstractCrew, mouseX: Int, mouseY: Int, crewPos: IPoint): Boolean {
        // Check if the crewmember's box is being hovered
        val index = game.playerCrew.indexOf(crew)
        if (index != -1) {
            val boxY = crewBaseY + index * CREW_BOX_SPACING
            if (mouseX in crewX..crewX + CREW_BOX_WIDTH && mouseY in boxY..boxY + CREW_BOX_HEIGHT) {
                skillsHoveredCrew = crew
                return true
            }
        }

        // Check if the physical crewmember is being hovered
        return mouseX in crewPos.x..crewPos.x + crew.icon.width &&
                mouseY in crewPos.y..crewPos.y + crew.icon.height
    }

    // Called by SlickGame, used for controlling boarders.
    fun enemyRoomRightClicked(room: Room, enemyShip: Ship) {
        for (crew in selectedCrew) {
            // Skip mind-controlled crew that remained selected.
            if (!crew.playerControllable)
                continue

            if (crew.room.ship == enemyShip)
                crew.setTargetRoom(room)
        }
    }

    fun weaponHotkeyPressed(id: Int, shiftPressed: Boolean) {
        if (pauseWindow != null) return

        // Temporary hack to make the option hotkeys work
        (currentWindow as? DialogueWindow)?.let {
            it.selectOption(id)
            return
        }

        if (currentWindow != null) return

        // Note that modded ships might not have a weapons system
        val weapons = ship.weapons ?: return

        // Block interactions with hacked weapons
        if (weapons.isHackActive)
            return

        // If the user was previously targeting a beam, cancel that.
        beamTargeting = null

        val weapon = ship.hardpoints.getOrNull(id)?.weapon ?: return

        if (shiftPressed) {
            // Holding shift turns a weapon off
            if (weapon.isPowered && weapons.setWeaponPower(weapon, false)) {
                powerDownSound.play()
            }
            return
        }

        if (!weapon.isPowered) {
            if (weapons.setWeaponPower(weapon, true)) {
                powerUpSound.play()
            } else {
                powerUpFailSound.play()
            }
            return
        }

        weapons.selectedTargets.unTarget(id)

        targetingSelectedWeapon = id
        selectWeaponClickEvent = RoomClickListener { room, gc ->
            // Prevent the player from shooting at the enemy ship once
            // their crew is dead.
            if (room.ship != ship && room.ship != game.getEnemyOf(ship)) {
                return@RoomClickListener
            }

            if (room.ship == ship && !weapon.canTargetOwnShip) {
                return@RoomClickListener
            }

            if (weapon is IRoomTargetingWeapon) {
                weapons.selectedTargets.targetRoom(id, room)
            } else if (weapon is BeamBlueprint.BeamInstance) {
                val mousePos = ConstPoint(gc.input.mouseX, gc.input.mouseY)
                val shipPos = mousePos - game.enemyPosition
                beamTargeting = SelectedTarget.BeamAim(weapon, id, game.enemy, shipPos)
                beamTargetingStartPos.set(mousePos)
            }
        }
        game.clickEvent = selectWeaponClickEvent
    }

    fun systemPowerHotkeyPressed(type: Class<*>, powerUp: Boolean) {
        for (sys in ship.mainSystems) {
            if (sys.javaClass != type) continue

            changeSystemPower(sys, powerUp)
        }
    }

    private fun changeSystemPower(sys: MainSystem, increasePower: Boolean) {
        val prevPower = sys.powerSelected

        if (increasePower) {
            sys.increasePower()
        } else {
            sys.decreasePower()
        }

        if (sys.powerSelected > prevPower) {
            powerUpSound.play()
        } else if (sys.powerSelected < prevPower) {
            powerDownSound.play()
        } else if (increasePower) {
            powerUpFailSound.play()
        }
    }

    private fun updateBeamTargeting(mouseX: Int, mouseY: Int) {
        val beam = beamTargeting ?: return

        val point = Point(beamTargetingStartPos)

        val delta = ConstPoint(mouseX, mouseY) - point

        // If the mouse is too close to where the beam starts, no beam is drawn.
        beam.visible = delta.distToSq(ConstPoint.ZERO) > MIN_BEAM_LENGTH * MIN_BEAM_LENGTH
        if (!beam.visible)
            return

        beam.angle = atan2(delta.y.toDouble(), delta.x.toDouble()).toFloat()

        beam.updateHitRooms()
    }

    private fun targetBeamWeapon() {
        val beam = beamTargeting ?: error("Called targetBeamWeapon without beam")
        beamTargeting = null

        val weaponIndex = ship.hardpoints.indexOfFirst { it.weapon == beam.weapon }
        ship.weapons!!.selectedTargets.targetBeam(weaponIndex, beam)
    }

    // Dispatch actions from the UI - this is only called when the game is not paused, so dispatch
    // everything from here so a player can cancel their actions if they remain paused.
    fun update() {
        ship.weapons?.selectedTargets?.fireChargedWeapons()
    }

    fun updateAlways(dt: Float) {
        // Always called, whether or not the player is paused.
        // This is therefore only to be used for UI stuff.
        insufficientScrapTimer -= dt
        if (insufficientScrapTimer < 0)
            insufficientScrapTimer = 0f

        // The resource number animations run while paused
        for (anim in resourceDeltaAnimations) {
            anim.update(dt)
        }
        resourceDeltaAnimations.removeIf { it.finished }
    }

    fun render(gc: GameContainer, g: Graphics) {
        width = gc.width
        height = gc.height

        // Update the buttons if crew have been added or removed, as this
        // moves around the save/load position buttons.
        // We have to do this here since we can't safely call updateButtons
        // mid-way through drawing.
        if (lastCrewCount != game.playerCrew.size) {
            updateButtons()
            lastCrewCount = game.playerCrew.size
        }

        drawTopBar(g)
        drawSystems(g)
        drawSubSystems(gc)

        // Draw the buttons last, so they don't disappear for a frame
        // when they're updated.
        for (button in buttons) {
            button.draw(g)
        }

        // Draw the floating numbers that show a change in a resource
        for (anim in resourceDeltaAnimations) {
            anim.draw(g)
        }

        updatingButtons = false

        // If the player's ship is full of crew, open the window to dismiss one.
        // Don't override another window to do this, however.
        if (currentWindow == null && game.playerHasTooManyCrew()) {
            showShipWindow(ShipWindow.Tab.CREW, true)
        }
    }

    private fun drawSystems(g: Graphics) {
        // Draw the power tree's energy bars
        val (fullPowerHeight, availablePowerHeight) = drawReactorPower(g)

        val powerTreeX = 86 - 53
        val powerTreeY = height - 21 - 302
        val powerTreeMaskY = powerTreeY + 27
        val powerIsAvailable = ship.powerAvailable > 0
        val powerMaskImg = game.getImg("img/wire_left_mask.png")
        val powerWireImg = game.getImg("img/wireUI/wire_full.png")

        Utils.drawStenciled(Utils.StencilMode.BLOCKING, {
            powerMaskImg.draw(powerTreeX.f + 4, powerTreeMaskY.f - fullPowerHeight)
        }) {
            powerWireImg.draw(powerTreeX.f, powerTreeY.f, 0.5f)
        }

        // Have to check since this draws the first system's power wire too,
        // and it's not masked off.
        if (powerIsAvailable) {
            Utils.drawStenciled(Utils.StencilMode.BLOCKING, {
                powerMaskImg.draw(powerTreeX.f + 4, powerTreeMaskY.f - availablePowerHeight)
            }) {
                // The power wire is taller than the mask (and way taller
                // than it needs to be), so the top shows if almost all
                // the ship's power is used.
                val maxHeight = powerMaskImg.height
                val exclusion = powerWireImg.height - maxHeight
                powerWireImg.draw(
                    powerTreeX.f, powerTreeY.f + exclusion,
                    powerTreeX.f + powerWireImg.width, powerTreeY.f + powerWireImg.height,
                    0f, exclusion.f,
                    powerWireImg.width.f, powerWireImg.height.f
                )
            }
        }

        // Draw the systems and the wires under them
        var weaponPowerX: Int? = null
        var dronePowerX: Int? = null
        var powerX = 58
        val powerY = height - 69
        for ((i, system) in ship.mainSystems.withIndex()) {
            // Draw the power bar to the next item
            val wireX = powerX + 31
            val wireY = powerY +
                    45 + // Top of the power button image to the bottom
                    12 + // Distance between the power button and the main horizontal wire
                    -26 // Go back up to the top of the wire image

            // Save the position of the weapon and drone power icons, since
            // they're used to position the weapon and drone selection boxes.
            if (system is Weapons)
                weaponPowerX = wireX
            if (system is Drones)
                dronePowerX = wireX

            if (updatingButtons) {
                val powerPos = ConstPoint(powerX + 19, powerY + 19)
                buttons += SystemPowerButton(powerPos, system)

                buttons += system.makeExtraButtons(powerPos)
            }

            powerX += when {
                system is Weapons -> 48 + ship.weaponSlots!! * 97
                system is Drones -> 48 + ship.droneSlots!! * 97
                system.insertButtonSpace -> 54
                else -> 36
            }

            // Don't draw the bar under the last item - since each item draws the
            // wire to the next item, this would result in a wire sticking out.
            if (i == ship.mainSystems.size - 1)
                continue

            val lastSystem = i == ship.mainSystems.size - 2

            val wireWidth = when {
                // Remember that we'll only run this if this isn't the last system,
                // so we won't be drawing a wire under weapons if drones isn't installed.
                system is Weapons -> {
                    // Draw the wire that goes under the weapons to the drone system
                    when (ship.weaponSlots) {
                        4 -> "456"
                        else -> "456_${ship.weaponSlots!!}weapon"
                    }
                }

                system.insertButtonSpace -> "54"
                else -> "36"
            }
            val image = when (lastSystem) {
                true -> game.getImg("img/wireUI/wire_${wireWidth}_cap.png")
                false -> game.getImg("img/wireUI/wire_$wireWidth.png")
            }

            // The wire does indeed get drawn twice if power is available, the colours match vanilla.
            image.draw(wireX.f, wireY.f, 0.5f)
            if (powerIsAvailable) {
                image.draw(wireX.f, wireY.f)
            }
        }

        // If a weapon is being targeted, check that our click event is still the game's active click
        // event. This ensures that if the click event is cancelled or overridden, targeting mode
        // is disabled.
        if (targetingSelectedWeapon != null && selectWeaponClickEvent != game.clickEvent) {
            targetingSelectedWeapon = null
            selectWeaponClickEvent = null
        }

        // Find the longest charge time of all equipped weapons
        maxWeaponChargeTime = ship.hardpoints.stream()
            .map { it.weapon }
            .filter { Objects.nonNull(it) }
            .map { it!!.chargeTime }
            .reduce { a, b -> Math.max(a, b) }
            .orElse(1f)

        // Draw the weapons box
        // Note that some modded ships don't have a weapons system
        val weapons = ship.weapons
        if (weapons != null) {
            val weaponBoxX = weaponPowerX!! + 1
            drawWeaponBox(weaponBoxX, "weapons_label", ship.weaponSlots!!)

            for (i in 0 until ship.weaponSlots!!) {
                if (!updatingButtons)
                    continue

                // The origin position of the weapon button
                val wx = weaponBoxX + WEAPON_BOX_GLOW + 12 + i * 97
                val wy = weaponBoxY + 12 + 4

                val weapon = ship.hardpoints[i].weapon

                buttons += object : WeaponDroneButton(ConstPoint(wx, wy), i, ConstPoint(87, 39)) {
                    override val empty: Boolean get() = weapon == null
                    override val name: String get() = weapon!!.type.translateShort(game)
                    override val requiredPower: Int get() = weapon!!.type.power
                    override val chargeTime: Float get() = weapon!!.chargeTime
                    override val chargeProgress: Float get() = weapon!!.chargeProgress
                    override val zoltanPower: Int get() = ship.weapons!!.getWeaponForcedPower(i)
                    override val isPowered: Boolean get() = weapon!!.isPowered
                    override val isCharged: Boolean get() = weapon!!.isCharged
                    override val isTargeted: Boolean get() = ship.weapons!!.selectedTargets.getTarget(i) != null
                    override val isSelectingTarget: Boolean get() = targetingSelectedWeapon == i
                    override val hasChargeBar: Boolean get() = true
                    override val isBeingHacked: Boolean get() = ship.weapons!!.isHackActive
                    override val droneCooldownProgress: Float? get() = null
                    override val tooltip: ITooltipProvider? get() = weapon?.weaponBarTooltip

                    override fun click(button: Int) {
                        when (button) {
                            Input.MOUSE_LEFT_BUTTON -> weaponHotkeyPressed(i, false)
                            Input.MOUSE_RIGHT_BUTTON -> weaponHotkeyPressed(i, true)
                        }
                    }

                    override fun draw(g: Graphics) {
                        super.draw(g)

                        if (weapon != null && weapon.type.chargeLevels != null) {
                            drawChargedShots()
                        }

                        if (weapon != null && weapon.type.boost != null) {
                            drawChainIcon()
                        }
                    }

                    private fun drawChargedShots() {
                        requireNotNull(weapon)

                        val filter = mainColour
                        val max = weapon.maxTotalCharges
                        val ready = weapon.totalReadyCharges
                        val width = 11
                        val baseX = pos.x + (size.x - max * 11) / 2
                        val y = pos.y + 25

                        for (charge in 0 until max) {
                            val suffix = when {
                                charge < ready -> "use"
                                else -> "on"
                            }

                            val img = game.getImg("img/combatUI/icon_chain_$suffix.png")
                            val x = baseX + charge * width
                            img.draw(x, y, filter)
                        }
                    }

                    private fun drawChainIcon() {
                        requireNotNull(weapon)
                        val boost = weapon.type.boost
                        requireNotNull(boost)

                        val clockOn = when {
                            weapon.chainCount == 0 -> false
                            !isPowered -> false
                            else -> true
                        }

                        val mode = when (clockOn) {
                            true -> "on"
                            false -> "off"
                        }
                        val iconName = when (boost.type) {
                            AbstractWeaponBlueprint.BoostType.COOLDOWN -> "img/combatUI/icon_clock_$mode.png"
                            AbstractWeaponBlueprint.BoostType.DAMAGE -> "img/combatUI/icon_arrow_$mode.png"
                        }
                        val icon = game.getImg(iconName)

                        val filter = when (clockOn) {
                            true -> mainColour
                            false -> Colour.white // Don't filter the already-greyed image
                        }
                        icon.draw(pos.x + 32, pos.y + 20, filter)

                        if (clockOn) {
                            val text = "+" + weapon.chainCount
                            weaponNameText.drawString(pos.x + 50f, pos.y + 31f, text, filter)
                        }
                    }
                }
            }
        }

        // Draw the drone area, if it's installed
        val drones = ship.drones
        if (drones != null) {
            val droneBoxX = dronePowerX!! + 1
            drawWeaponBox(droneBoxX, "drones_label", ship.droneSlots!!)

            // Draw the in-operation drone boxes - this seems very similar to weapons
            for ((i, info) in drones.drones.withIndex()) {
                val drone = info?.type
                if (!updatingButtons)
                    break

                // The origin position of the drone button
                val pos = ConstPoint(
                    droneBoxX + WEAPON_BOX_GLOW + 4 + i * 97,
                    weaponBoxY + 12 + 4
                )

                buttons += object : WeaponDroneButton(pos, i, ConstPoint(95, 39)) {
                    override val empty: Boolean get() = drone == null
                    override val name: String get() = drone!!.translateShort(game)
                    override val requiredPower: Int get() = drone!!.power
                    override val chargeTime: Float get() = error("Can't get charge time for drone")
                    override val chargeProgress: Float get() = error("Can't get charge progress for drone")
                    override val zoltanPower: Int get() = drones.getDroneForcedPower(i)
                    override val isPowered: Boolean get() = info!!.instance?.isPowered == true
                    override val isCharged: Boolean get() = isPowered // Always use the charged colour
                    override val isTargeted: Boolean get() = false
                    override val isSelectingTarget: Boolean get() = false
                    override val hasChargeBar: Boolean get() = false
                    override val isBeingHacked: Boolean get() = drones.isHackActive && info?.instance != null
                    override val droneCooldownProgress: Float?
                        get() = info?.cooldown?.let { it / AbstractDrone.DRONE_DESTROYED_COOLDOWN }
                    override val tooltip: ITooltipProvider? get() = null // TODO

                    override fun click(button: Int) {
                        val wasPowered = info?.instance?.isPowered ?: false

                        if (button == Input.MOUSE_LEFT_BUTTON) {
                            drones.setDronePower(i, true)
                        }
                        if (button == Input.MOUSE_RIGHT_BUTTON) {
                            drones.setDronePower(i, false)
                        }

                        val nowPowered = info?.instance?.isPowered ?: false
                        when {
                            wasPowered && !nowPowered -> powerDownSound.play()
                            !wasPowered && nowPowered -> powerUpSound.play()
                            !wasPowered && button == Input.MOUSE_LEFT_BUTTON -> powerUpFailSound.play()
                        }
                    }

                    override fun draw(g: Graphics) {
                        super.draw(g)

                        if (info?.instance != null) {
                            game.getImg("img/ui_icons/icon_drones.png").draw(pos.x + 43 - 9, pos.y + 18 - 8, mainColour)
                        }
                    }
                }
            }
        }

        // Draw the crew selection rectangle, if appropriate
        val csr = crewSelectionRectangle
        if (csr != null && !isCrewSelectionPoint) {
            g.colour = Colour.white
            val size = csr.second - csr.first
            g.drawRect(csr.first.x.f, csr.first.y.f, size.x.f, size.y.f)
            g.drawRect(csr.first.x + 1f, csr.first.y + 1f, size.x - 2f, size.y - 2f)
        }
    }

    private fun drawSubSystems(gc: GameContainer) {
        // The box position, not including the glow.
        val boxX = gc.width - 252
        val boxY = gc.height - 47

        game.getImg("img/box_subsystems4.png").draw(boxX - 6, boxY - 6)

        // Draw the subsystems label
        val label = game.translator["subsystems_label"]
        font.drawString(boxX + 7f, boxY + 23f, label, UI_TEXT_COLOUR_1)

        // Add all the system buttons
        if (!updatingButtons) {
            return
        }

        fun addSubSys(x: Int, sys: SubSystem?) {
            if (sys == null)
                return

            val powerPos = ConstPoint(boxX + x, boxY - 21)
            buttons += SystemPowerButton(powerPos, sys)
            buttons += sys.makeExtraButtons(powerPos)
        }

        addSubSys(6, ship.piloting)
        addSubSys(42, ship.sensors)
        addSubSys(78, ship.doorsSystem)
        addSubSys(129, ship.backupBattery)
    }

    /**
     * Draw the stack of reactor power icons.
     *
     * Returns the height of the stack of power bars, and the height of the top
     * of the highest non-empty power bar.
     */
    private fun drawReactorPower(g: Graphics): Pair<Int, Int> {
        // Top-left corner of the bottom power bar
        val x = 12
        val baseY = height - 34

        val barHeight = 7
        val spacing = barHeight + 2

        val availablePower = ship.powerAvailable
        var totalPower = ship.purchasedReactorPower
        ship.backupBattery?.let { totalPower += it.contributedPower }
        var nextPowerIdx = 0
        for (type in EnergySource.GLOBAL_TYPES) {
            val available = ship.powerAvailableTypes[type] ?: 0

            for (i in 0 until available) {
                val y = baseY - spacing * nextPowerIdx
                type.drawReactorPowerBar(g, x, y, 28, barHeight)

                nextPowerIdx++
            }
        }

        while (nextPowerIdx < totalPower) {
            val y = baseY - spacing * nextPowerIdx

            // If this power is blocked (eg by an ion storm), show it as blue
            // with a line through it.
            if (nextPowerIdx >= totalPower - ship.blockedReactorPower) {
                g.colour = SYS_ENERGY_ION_STORM
                g.drawRect(x, y, 28 - 1, 7 - 1)
                g.drawLine(x, y + 7, x + 28, y)
            } else {
                g.colour = SYS_ENERGY_DEPOWERED
                g.drawRect(x, y, 28 - 1, 7 - 1)
            }

            nextPowerIdx++
        }

        val topOfStack = (totalPower - 1) * spacing
        val topOfPowered = (availablePower - 1) * spacing
        return Pair(topOfStack, topOfPowered)
    }

    fun renderMenus(container: GameContainer, g: Graphics) {
        currentWindow?.let { renderSingleMenu(container, g, it) }
        pauseWindow?.let { renderSingleMenu(container, g, it) }
    }

    private fun renderSingleMenu(container: GameContainer, g: Graphics, window: Window) {
        if (!window.appliesSelfTint) {
            drawWindowBackgroundTint(g)
        }

        // Don't let tooltips show up through the tint
        g.tooltip = null

        // Centre the window.
        window.position = window.windowCentreOffset + ConstPoint(
            (container.width - window.size.x) / 2,
            (container.height - window.size.y) / 2
        )

        window.draw(g)
    }

    fun drawWindowBackgroundTint(g: Graphics) {
        // Add the tint over all the regular game stuff to make the window clearer.
        g.colour = Colour(0f, 0f, 0f, 0.65f)
        g.fillRect(0, 0, width, height)
    }

    private fun drawTopBar(g: Graphics) {
        val isHullBoxRed = hullWarning25.isFlashingHigh || hullWarning50.isFlashingHigh || hullWarning75.isFlashingHigh
        val hullBoxImage = when {
            isHullBoxRed -> game.getImg("img/statusUI/top_hull_red.png")
            else -> game.getImg("img/statusUI/top_hull.png")
        }
        hullBoxImage.draw(0f, 0f)

        val hullLabelImg = when {
            isHullBoxRed -> game.getImg("img/statusUI/top_hull_red_label.png")
            else -> game.getImg("img/statusUI/top_hull_label.png")
        }
        val hullText = game.translator["status_hull"]
        UIUtils.drawTab(font, hullText, hullLabelImg, 0f, 0f, 10f, 30f)
        font.drawString(9f, 25f, hullText, UI_TEXT_COLOUR_1)

        if (mousePos.x in 10..370 && mousePos.y in 20..45) {
            g.tooltip = hullTooltip
        }

        // Draw the hull bar

        // TODO support ships with more or less health

        val hpW = 12f * ship.health
        val healthColour = when {
            ship.health < 10 -> SHIP_HEALTH_LOW
            ship.health < 20 -> SHIP_HEALTH_MED
            else -> SHIP_HEALTH_HIGH
        }
        val mask = game.getImg("img/statusUI/top_hull_bar_mask.png")
        val hpH = mask.height.f
        mask.draw(11f, 0f, 11f + hpW, hpH, 0f, 0f, hpW, hpH, 1f, healthColour)

        val lastFraction = lastHealth.f / ship.maxHealth
        val newFraction = ship.health.f / ship.maxHealth
        lastHealth = ship.health

        if (newFraction < 0.25f) {
            // Constantly trigger the hull critical warning
            hullWarning25.startFor(5f)
        } else if (lastFraction > 0.50f && newFraction < 0.50f) {
            hullWarning50.startFor(9f)
        } else if (lastFraction > 0.75f && newFraction < 0.75f) {
            hullWarning75.startFor(9f)
        }

        // Stop animations when we're healed, or our health drops
        // enough to trigger the next animation.
        if (newFraction > 0.25f) {
            hullWarning25.stop()
        }
        if (newFraction !in 0.25f..0.5f) {
            hullWarning50.stop()
        }
        if (newFraction !in 0.5f..0.75f) {
            hullWarning75.stop()
        }

        hullWarning25.draw(g)
        hullWarning50.draw(g)
        hullWarning75.draw(g)

        // Draw the scrap indicator
        val isScrapRed = insufficientScrapTimer != 0f &&
                (insufficientScrapTimer / INSUFFICIENT_SCRAP_FLASH_TIME).toInt() % 2 == 0
        val scrapPath = if (isScrapRed) "img/statusUI/top_scrap_red.png" else "img/statusUI/top_scrap.png"
        game.getImg(scrapPath).draw(374f + 8 - 5, 0f)
        numberFont.drawStringCentred(416f, 35f, 87f, ship.scrap.toString(), UI_SCRAP_TEXT_COLOUR)

        val deltaScrap = ship.scrap - lastResources.scrap
        if (deltaScrap != 0) {
            lastResources.scrap = ship.scrap
            val rdt = ResourceDeltaText(460f, 30f, deltaScrap)
            rdt.x += VisualRandom.nextInt(-10, 10)
            resourceDeltaAnimations.add(rdt)
        }

        if (mousePos.x in 383..505 && mousePos.y in 11..46) {
            g.tooltip = scrapTooltip
        }

        // Going down the side, for the shields/oxygen
        val shieldY = 43
        val shields = ship.shields

        // Draw the shields indicator
        val broken = shields != null
                && shields.energyLevels >= 2 // Don't warn for Zoltan B, which starts on 1
                && shields.energyLevels - shields.damagedEnergyLevels < 2
        val shieldBox = when {
            broken -> game.getImg("img/statusUI/top_shields4_red.png")
            shields == null || shields.selectedShieldBars == 0 -> game.getImg("img/statusUI/top_shields4_off.png")
            else -> game.getImg("img/statusUI/top_shields4_on.png")
        }
        shieldBox.draw(0, shieldY)
        val hacked = shields?.isHackActive == true
        if (hacked) {
            game.getImg("img/statusUI/top_shields4_purple.png").draw(31 - 8, shieldY)
        }

        if (ship.superShield != 0) {
            // Draw the super-shield bar where the recharge
            // bar would normally go.
            g.colour = SYS_ENERGY_ACTIVE
            var x = 33
            for (i in 0 until ship.superShield) {
                // The middle bar is one pixel shorter to make everything
                // else line up properly.
                val width = if (i == 2) 16 else 17

                g.fillRect(x.f, shieldY + 34f + 2f, width.f, 6f)
                x += width + 2
            }
        } else if (shields != null && shields.rechargeTimer != 0f) {
            // Draw the recharge bar
            val progress = (shields.rechargeTimer / shields.rechargeDelay).coerceIn(0f..1f)
            g.colour = when (hacked) {
                true -> SHIELD_BAR_HACKED
                false -> SHIELD_BAR_NORMAL
            }
            g.fillRect(
                33f, shieldY + 34f + 2f,
                92f * progress, 6f
            )
        }

        if (shields != null) {
            // Draw the shield bubble indicators
            val shieldOnImg = game.getImg("img/statusUI/top_shieldsquare1_on.png")
            val shieldOffImg = game.getImg("img/statusUI/top_shieldsquare1_off.png")
            val shieldHackedOnImg = game.getImg("img/statusUI/top_shieldsquare1_hacked_charged.png")
            val shieldHackedOffImg = game.getImg("img/statusUI/top_shieldsquare1_hacked.png")

            for (i in 0 until shields.selectedShieldBars) {
                val charged = shields.activeShields > i

                val img = when {
                    hacked && charged -> shieldHackedOnImg
                    hacked -> shieldHackedOffImg

                    charged -> shieldOnImg
                    else -> shieldOffImg
                }
                img.draw(30 + 23 * i, shieldY + 4)
            }

            when {
                broken -> shieldsWarning.startFor(1f)
                else -> shieldsWarning.stop()
            }
            shieldsWarning.draw(g)
        }

        if (mousePos.x in 12..128 && mousePos.y in 50..77) {
            g.tooltip = shieldTooltip
        }

        fun drawSmallCounter(
            name: String,
            x: Int,
            numAreaOffset: Int,
            count: Int, delta: Int,
            redThreshold: Int,
            tooltip: ITooltipProvider
        ) {
            val suffix = when {
                count <= redThreshold -> "_red"
                else -> ""
            }
            game.getImg("img/statusUI/top_${name}_on$suffix.png").draw(x, shieldY)

            val areaWidth = 35
            val areaStart = x.f + 33 + numAreaOffset

            val textWidth = numberFont.getWidth(count.toString())
            val textInternalX = ceil((areaWidth - textWidth) / 2f).toInt()

            numberFont.drawString(areaStart + textInternalX, shieldY + 27f, count.toString(), Colour.white)

            if (delta != 0) {
                val rdt = ResourceDeltaText(x + 40f, 65f, delta)
                resourceDeltaAnimations.add(rdt)
            }

            if (mousePos.x in x + 7..x + 7 + 59 && mousePos.y in shieldY + 7..shieldY + 7 + 26) {
                g.tooltip = tooltip
            }
        }

        val shieldsEndX = 122

        // Draw the environmental hazard imminent warnings (eg 'ion pulse imminent')
        when (val env = game.currentBeacon.getEnvironment(game)) {
            is SunEnvironment -> {
                if (env.showWarning)
                    sunWarning.draw(g)
            }

            is PulsarEnvironment -> {
                if (env.showWarning)
                    pulsarWarning.draw(g)
            }
        }

        val deltaFuel = ship.fuelCount - lastResources.fuel
        val deltaMissiles = ship.missilesCount - lastResources.missiles
        val deltaDrones = ship.dronesCount - lastResources.droneParts
        lastResources.fuel = ship.fuelCount
        lastResources.missiles = ship.missilesCount
        lastResources.droneParts = ship.dronesCount

        // Fuel shows as red when there's three or less left.
        drawSmallCounter("fuel", shieldsEndX, 0, ship.fuelCount, deltaFuel, 3, fuelTooltip)

        // Missiles only show as red when there's none left.
        drawSmallCounter("missiles", shieldsEndX + 66, 1, ship.missilesCount, deltaMissiles, 0, missileTooltip)

        // Same for drones.
        drawSmallCounter("drones", shieldsEndX + 66 + 70, -2, ship.dronesCount, deltaDrones, 0, droneTooltip)

        // Oxygen and evasion indicator

        // Note we hide the oxygen critical warning if the player doesn't
        // have an oxygen system. This is Hyperspace behaviour, but it doesn't
        // affect vanilla, and it's hard to imagine a mod where this is a problem.
        val avgOxygen = round(ship.averageOxygen * 100).toInt()
        val oxygenRed: Boolean
        if (avgOxygen < 25 && ship.oxygen != null) {
            oxygenWarning.draw(g)
            oxygenRed = oxygenWarning.isFlashingHigh
        } else {
            oxygenRed = false
        }

        val evasion = ship.evasion
        val evasionRed = evasion == 0

        val evasionOxyPath = when {
            evasionRed && oxygenRed -> "img/statusUI/top_evade_oxygen_both_red.png"
            evasionRed -> "img/statusUI/top_evade_oxygen_up_red.png"
            oxygenRed -> "img/statusUI/top_evade_oxygen_down_red.png"
            else -> "img/statusUI/top_evade_oxygen.png"
        }
        val oxyY = 96
        game.getImg(evasionOxyPath).draw(1f, oxyY - 7f)

        val evadeTextRight = 92f

        // Evade
        oxygenEvadeFont.drawStringLeftAligned(evadeTextRight, oxyY + 15f, "$evasion%", Colour.white)

        // Oxygen
        oxygenEvadeFont.drawStringLeftAligned(evadeTextRight, oxyY + 37f, "$avgOxygen%", Colour.white)

        if (mousePos.x in 8..98 && mousePos.y in oxyY..oxyY + 20) {
            g.tooltip = evasionTooltip
        }
        if (mousePos.x in 8..98 && mousePos.y in oxyY + 22..oxyY + 42) {
            g.tooltip = oxygenTooltip
        }

        // Draw all the crew boxes
        crewBaseY = oxyY + 59
        for ((index, crew) in ship.sys.playerCrew.withIndex()) {
            val crewY = crewBaseY + index * CREW_BOX_SPACING

            drawCrewBox(g, crew, crewX, crewY)
        }

        // Add the save/restore crew position buttons
        val lastCrewBoxY = crewBaseY + (ship.sys.playerCrew.size - 1).coerceAtLeast(0) * CREW_BOX_SPACING
        val crewSaveLoadY = lastCrewBoxY + 32
        if (updatingButtons) {
            val saveImg = ButtonImageSet.select2(game, "img/statusUI/button_station_assign")
            val loadImg = ButtonImageSet.select2(game, "img/statusUI/button_station_return")
            val base = game.getImg("img/statusUI/button_station_base.png")

            buttons += object : Button(game, ConstPoint(19 + 5, crewSaveLoadY + 5), saveImg.normal.imageSize) {
                val tooltip = HotkeyDelayedTooltip(game, "save_stations_tooltip")

                override fun draw(g: Graphics) {
                    // 7px glow, and the button is 5px inside the wrapper
                    base.draw(pos.x - 5 - 7, crewSaveLoadY - 7)

                    saveImg.pick(this).draw(pos)

                    if (hovered) {
                        g.tooltip = tooltip
                    }
                }

                override fun click(button: Int) {
                    if (button != Input.MOUSE_LEFT_BUTTON)
                        return

                    saveCrewPositions()
                }
            }

            buttons += object : Button(game, ConstPoint(58 + 5, crewSaveLoadY + 5), loadImg.normal.imageSize) {
                val tooltip = HotkeyDelayedTooltip(game, "ret_stations_tooltip")

                override fun draw(g: Graphics) {
                    // 7px glow, and the button is 5px inside the wrapper
                    base.draw(pos.x - 5 - 7, crewSaveLoadY - 7)

                    loadImg.pick(this).draw(pos)

                    if (hovered) {
                        g.tooltip = tooltip
                    }
                }

                override fun click(button: Int) {
                    if (button != Input.MOUSE_LEFT_BUTTON)
                        return

                    loadCrewPositions()
                }
            }
        }

        // The save/load stations messages, which fade out after a second
        if (saveCrewPosMessageTimer > 0f) {
            saveCrewPosMessageTimer -= game.renderingDeltaTime

            val message = game.translator[saveCrewPosMessageText]
            oxygenEvadeFont.drawString(11f, crewSaveLoadY + 55f, message, Colour.white)
        }
    }

    private fun drawCrewBox(g: Graphics, crew: LivingCrew, x: Int, y: Int) {
        val clonebay = ship.clonebay

        val isMindControlled = crew.mindControlledBy != null
        val isStunned = false // TODO implement when stunning crew is added
        val isFlashingHealth = false // TODO
        val isCloning = clonebay?.queue?.contains(crew) ?: false
        val cloneDyingProgress =
            if (clonebay?.queue?.lastOrNull() == crew)
                clonebay.dyingProgress
            else
                0f

        val colour = when {
            isMindControlled -> CREW_BOX_MIND_CONTROLLED
            crew in selectedCrew -> CREW_BOX_SELECT
            isStunned -> CREW_BOX_STUNNED
            crew in hoveredCrew -> CREW_BOX_HOVER
            isFlashingHealth -> CREW_BOX_LOW_HEALTH
            isCloning -> CREW_BOX_CLONING
            else -> CREW_BOX_NORMAL
        }

        val drawSkills = crew == skillsHoveredCrew

        if (!drawSkills) {
            // Draw the semi-transparent background
            g.colour = Colour(colour.r, colour.g, colour.b, CREW_BOX_BG_ALPHA)
            g.fillRect(x.f, y.f, CREW_BOX_WIDTH.f, CREW_BOX_HEIGHT.f)

            // Draw the solid outline, via two unfilled rectangles.
            g.colour = colour
            g.drawRect(x.f, y.f, CREW_BOX_WIDTH - 1f, CREW_BOX_HEIGHT - 1f)
            g.drawRect(x + 1f, y + 1f, CREW_BOX_WIDTH - 3f, CREW_BOX_HEIGHT - 3f)

            // TODO show the animation when the crewmember gets a skill point.
        } else {
            // Draw the semi-transparent background
            g.colour = Colour(colour.r, colour.g, colour.b, 0.25f)
            g.fillRect(x.f, y.f, 89f, CREW_BOX_HEIGHT.f)
            g.fillRect(x + 89f, y.f, 80f, 146f)

            // Draw the outline, which we do with line drawing by rectangles.
            g.colour = colour
            g.fillRect(x.f, y.f, 2f, CREW_BOX_HEIGHT.f)
            g.fillRect(x.f, y.f, 169f, 2f)
            g.fillRect(x.f, y + 25f, 91f, 2f)
            g.fillRect(x + 89f, y + 27f, 2f, 119f)
            g.fillRect(x + 167f, y.f, 2f, 146f)
            g.fillRect(x + 91f, y + 144f, 76f, 2f)

            drawSkillBar(g, x + 93, y, crew, Skill.PILOTING)
            drawSkillBar(g, x + 93, y, crew, Skill.ENGINES)
            drawSkillBar(g, x + 93, y, crew, Skill.SHIELDS)
            drawSkillBar(g, x + 93, y, crew, Skill.WEAPONS)
            drawSkillBar(g, x + 93, y, crew, Skill.REPAIRS)
            drawSkillBar(g, x + 93, y, crew, Skill.COMBAT)
        }

        // Draw the health bar
        val maxHpWidth = 49
        val hpFraction = crew.health / crew.maxHealth
        var hpWidth = (maxHpWidth * hpFraction).toInt()

        // Don't go below one pixel of health unless they're dead.
        // This is particularly notable when the crew is cloning.
        if (hpFraction > 0 && hpWidth == 0) {
            hpWidth = 1
        }

        if (crew.health == crew.maxHealth) {
            g.colour = Colour.green
        } else {
            g.colour = Colour(
                1f,
                (2f * hpFraction).coerceIn(0f..1f),
                0f
            )
        }
        g.fillRect(x + 33f, y + 19f, hpWidth.f, 4f)

        // Draw the crew portrait, mostly so you can see what race they are.
        crew.drawPortrait(x - 1, y - 3, true)

        // Draw the crew name
        crewNameText.drawString(x + 33f, y + 14f, crew.info.shortName, CREW_BOX_NAME_COLOUR)

        // If the crew is dying in a clonebay, draw the red overlay
        if (cloneDyingProgress > 0f) {
            g.colour = CREW_BOX_CLONE_DYING_OVERLAY
            g.fillRect(x.f, y.f, CREW_BOX_WIDTH.f, CREW_BOX_HEIGHT * cloneDyingProgress)
        }

        // Add the tooltip
        if (drawSkills) {
            g.tooltip = object : ITooltipProvider {
                override fun drawTooltip(
                    g: Graphics,
                    mouseX: Int, mouseY: Int,
                    firstFrame: Boolean,
                    screenWidth: Int, screenHeight: Int
                ) {
                    val lines = listOf(
                        game.translator["health_tooltip"]
                            .replaceArg(crew.health.roundToInt())
                            .replaceArg(crew.maxHealth.roundToInt(), 2),
                        game.translator["hotkey"] // TODO
                    )

                    val width = TooltipConstants.widthOf(lines, tooltipFont)
                    val height = TooltipConstants.heightOf(lines)

                    TooltipConstants.drawTooltip(g, tooltipFont, x + 181, y, width, height, lines)
                }
            }
        }
    }

    private fun drawSkillBar(
        g: Graphics,
        x: Int, baseY: Int,
        crew: LivingCrew, skill: Skill
    ) {
        val y = baseY + skill.ordinal * 24 // Not 26, which is the icon height
        val skillLevel = crew.getSkillLevel(skill)

        // Draw the icon
        val icon = game.getImg(skill.iconPath)
        val iconColour = when (skillLevel) {
            SkillLevel.MAX -> SYS_ENERGY_REPAIR
            SkillLevel.PARTIAL -> SYS_ENERGY_ACTIVE
            else -> UI_BACKGROUND_GLOW_COLOUR
        }
        icon.draw(x, y, iconColour)

        // Draw the progress bar
        crew.info.drawSkillProgressBar(g, x + 30, y + 9, 40, 8, skill)
    }

    // Draw the box containing the weapon or drone selection buttons
    private fun drawWeaponBox(x: Int, label: String, size: Int) {
        game.getImg("img/box_weapons_bottom$size.png").draw(x.f, weaponBoxY.f)

        val textX = x + 18
        val textY = weaponBoxY + 61

        // Draw the 'weapons' or 'drones' label
        val name = game.translator[label]
        val textWidth = font.getWidth(name)
        val img = game.getImg("img/box_weapons_bottom_label.png")

        // Stretch part of the label to cover the width of the text
        img.draw(
            textX.f, textY.f, textX + textWidth - 13f, textY + img.height.f,
            0f, 0f, 3f, img.height.f
        )

        // Draw the 'ramp' at the end of the label.
        img.draw((textX + textWidth - 13).f, textY.f)

        font.drawString(textX + 1f, textY + 15f, name, UI_TEXT_COLOUR_1)
    }

    fun updateUI(x: Int, y: Int, playerShipPosition: IPoint) {
        mousePos.set(x, y)

        pauseWindow?.let { win ->
            win.updateUI(x, y)
            return
        }

        currentWindow?.let { win ->
            win.updateUI(x, y)
            return
        }

        for (button in buttons) {
            button.update(x, y)
        }

        if (beamTargeting != null) {
            updateBeamTargeting(x, y)
        }

        crewSelectionRectangle?.second?.set(x, y)

        updateHoveredCrew(x, y, playerShipPosition)

        // Update the door hover markers
        for (door in ship.doors) {
            door.updateMouseHover(x - playerShipPosition.x, y - playerShipPosition.y)
        }
    }

    fun showEventDialogue(event: Event, seed: Int) {
        openEventDialogue(event, seed)
    }

    private fun openEventDialogue(event: Event?, seed: Int) {
        var hasImmediatelyClosed = false

        currentWindow = DialogueWindow(game, ship, event, seed) {
            hasImmediatelyClosed = true
            eventDialogueClosed()
        }

        // It's possible for DialogueWindow to call close in its constructor,
        // in which case we have to close it not to avoid it being rendered
        // while in an invalid state.
        // (This is because the constructor would run before currentWindow
        //  is set).
        // This can be tested with the TRADER_LIST event, or any other
        // event list whose purpose is to pick one of several events and
        // close without any text set.
        if (hasImmediatelyClosed) {
            currentWindow = null
        }
    }

    fun showSyntheticDialogue(syntheticEvent: DialogueWindow.SyntheticEvent) {
        var dialogue = currentWindow as? DialogueWindow

        // Open a new, event-less window.
        // This would crash on the next render if we didn't add
        // the synthetic event.
        if (dialogue == null) {
            openEventDialogue(null, 0)
            dialogue = currentWindow as DialogueWindow
        }

        dialogue.addSyntheticEvent(syntheticEvent)
    }

    private fun eventDialogueClosed() {
        currentWindow = null

        // If a store was made available by the dialogue, open it
        if (game.currentBeacon.hasStore && !storeAlreadyOpened) {
            updateButtons() // Make the store button show up
            showStoreWindow()
        }
    }

    private fun showShipWindow(initialTab: ShipWindow.Tab, force: Boolean = false) {
        if (!force && game.isInDanger)
            return

        currentWindow = ShipWindow(game, ship, initialTab) {
            currentWindow = null
        }
    }

    private fun showStoreWindow() {
        if (game.isInDanger)
            return

        val store = game.currentBeacon.getStore(game) ?: return

        storeAlreadyOpened = true

        currentWindow = StoreWindow(game, ship, store) {
            currentWindow = null
        }
    }

    fun showPauseWindow() {
        pauseWindow = PauseWindow(game) { pauseWindow = null }
    }

    fun showOptionsWindow() {
        // This uses the pause window slot, so it doesn't dismiss
        // a regular window, such as the dialogue or game-over windows.
        pauseWindow = OptionsWindow(game) { pauseWindow = null }
    }

    fun showKeybindWindow() {
        // Switch back to the options window when it's closed, that's how
        // vanilla behaves.
        pauseWindow = HotkeysWindow(game) { showOptionsWindow() }
    }

    // Hack used to draw the selected crew from the ship, rather than some cleaner solution
    fun isCrewHighlighted(crew: AbstractCrew): Boolean {
        return selectedCrew.contains(crew) || hoveredCrew.contains(crew)
    }

    fun escapePressed() {
        pauseWindow?.let { win ->
            win.escapePressed()
            return
        }

        currentWindow?.let { win ->
            win.escapePressed()
            return
        }

        showPauseWindow()
    }

    fun playInsufficientScrapAnimation() {
        // Three flashes: on-off-on-off-on
        insufficientScrapTimer = INSUFFICIENT_SCRAP_FLASH_TIME * 5

        // I think this is the right sound?
        powerUpFailSound.play()
    }

    fun shipModified() {
        updateButtons()
        currentWindow?.shipModified()
    }

    /**
     * Called by [Teleporter] when one of its buttons are clicked.
     *
     * When the 'send to enemy ship' button is clicked, [send] is true.
     * Otherwise it's false.
     */
    fun teleportSelected(send: Boolean) {
        game.clickEvent = TeleportRoomListener(send)
    }

    /**
     * Called by [Hacking] when we're supposed to select the room to hack.
     */
    fun hackSelected() {
        game.clickEvent = HackingRoomListener()
    }

    /**
     * Called by [MindControl] when we're supposed to select the room to
     * apply mind control to.
     */
    fun mindControlSelected() {
        game.clickEvent = MindControlRoomListener()
    }

    fun openJumpMap() {
        if (!ship.isFtlReady || !ship.canChargeFTL)
            return

        currentWindow = JumpWindow(game, ::openSectorMap) {
            if (it != null) {
                // Reset stuff after jumping
                storeAlreadyOpened = false
            }

            currentWindow = null
            updateButtons() // A store may now be available
        }
    }

    fun openSectorMap() {
        currentWindow = SectorMapWindow(game) { sectorInfo ->
            currentWindow = null

            // Null just means the window was closed
            if (sectorInfo == null)
                return@SectorMapWindow

            val sector = game.gameMap.generateSector(sectorInfo, game)
            game.currentBeacon = sector.startBeacon

            // In case we were at a store
            // TODO move this into an on-jump handler function
            updateButtons()
        }
    }

    fun openAllDoors() {
        val internalDoors = ship.doors.filter { !it.isAirlock }
        val allInternalDoorsOpen = internalDoors.all { it.open }

        // Pressing the open key with all the internal doors open
        // opens all the airlocks, otherwise only the internal
        // doors are opened.
        for (door in ship.doors) {
            if (!allInternalDoorsOpen && door.isAirlock)
                continue

            door.open = true
        }
    }

    fun closeAllDoors() {
        for (door in ship.doors) {
            door.open = false
        }
    }

    /**
     * Called whenever a hotkey is pressed.
     *
     * This is the proper way to handle hotkey input, as it supports control rebinding.
     */
    fun hotkeyPressed(key: Hotkey, input: Input) {
        // If a window is open, it absorbs all the input.
        // This is particularly important for keybinds like the jump/ship
        // ones as they open a window - the user could otherwise use them
        // to replace the currently-open dialogue window, to avoid the
        // result of an undesirable event.
        pauseWindow?.let {
            it.hotkeyPressed(key)
            return
        }
        currentWindow?.let {
            it.hotkeyPressed(key)
            return
        }

        for (system in ship.mainSystems) {
            if (key.id == "sys_power_${system.codename}") {
                val powerUp: Boolean = !input.isKeyDown(Input.KEY_LSHIFT)
                changeSystemPower(system, powerUp)
            }
            if (key.id == "sys_power_${system.codename}_off") {
                changeSystemPower(system, false)
            }
        }

        for (system in ship.systems) {
            system.hotkeyPressed(key)
        }

        // The select-one-crewmember hotkeys
        for ((index, id) in VanillaHotkeys.SELECT_CREW.withIndex()) {
            if (key.id != id)
                continue

            val crew = game.playerCrew.getOrNull(index) ?: continue
            if (!crew.playerControllable)
                continue

            selectedCrew.clear()
            selectedCrew += crew
        }

        when (key.id) {
            VanillaHotkeys.FTL_JUMP -> openJumpMap()
            VanillaHotkeys.SHIP_UPGRADES -> showShipWindow(ShipWindow.Tab.UPGRADES)
            VanillaHotkeys.SHIP_CREW -> showShipWindow(ShipWindow.Tab.CREW)
            VanillaHotkeys.SHIP_INVENTORY -> showShipWindow(ShipWindow.Tab.EQUIPMENT)
            VanillaHotkeys.OPEN_STORE -> showStoreWindow()
            VanillaHotkeys.OPEN_OPTIONS -> showOptionsWindow()

            VanillaHotkeys.SYS_ACTION_DOOR_OPEN -> openAllDoors()
            VanillaHotkeys.SYS_ACTION_DOOR_CLOSE -> closeAllDoors()

            VanillaHotkeys.SELECT_CREW_ALL -> {
                // Should this include mind-controlled enemies?
                selectedCrew.clear()
                selectedCrew += game.playerCrew.filter { it.playerControllable }
            }

            // TODO crystal lockdown hotkey, when that's implemented

            VanillaHotkeys.SAVE_CREW_POS -> saveCrewPositions()
            VanillaHotkeys.LOAD_CREW_POS -> loadCrewPositions()

            // These hotkey applies to both medbays and clonebays
            VanillaHotkeys.SYS_POWER_MEDICAL -> {
                val powerUp: Boolean = !input.isKeyDown(Input.KEY_LSHIFT)
                ship.medbay?.let { changeSystemPower(it, powerUp) }
                ship.clonebay?.let { changeSystemPower(it, powerUp) }
            }

            VanillaHotkeys.SYS_POWER_MEDICAL_OFF -> {
                ship.medbay?.let { changeSystemPower(it, false) }
                ship.clonebay?.let { changeSystemPower(it, false) }
            }
        }
    }

    /**
     * FOR DEBUG USE ONLY!
     *
     * See [InGameState.debugContinuousSaveRestore] for more information.
     */
    fun debugContinuousSaveRestore(prev: PlayerShipUI) {
        crewSelectionRectangle = prev.crewSelectionRectangle

        // Copy over the selected crew, based on the index into the ship
        // crew list. This is hacky but it's fine for a debug flag.
        for (prevCrew in prev.selectedCrew) {
            val index = prev.ship.crew.indexOf(prevCrew)

            // Don't bother with crew on the enemy ship.
            if (index == -1)
                continue

            selectedCrew += ship.crew[index]
        }

        // Reloading should keep the store open, if it's run manually.
        val oldStore = prev.currentWindow as? StoreWindow
        if (oldStore != null) {
            showStoreWindow()
            val store = currentWindow as StoreWindow
            store.sellTab = oldStore.sellTab
            store.secondBuyTab = oldStore.secondBuyTab
        }
    }

    private fun updateHoveredCrew(mouseX: Int, mouseY: Int, playerShipPosition: IPoint) {
        hoveredCrew.clear()
        skillsHoveredCrew = null

        // Allow for smart-casting
        val csr = crewSelectionRectangle

        // If a rectangle is visible on screen (ie, the player isn't clicking a point) then build a rectangle
        val rect = if (csr != null && !isCrewSelectionPoint) {
            val pos = csr.first.min(csr.second)
            val size = (csr.second - csr.first).abs()
            Rectangle(pos.x.f, pos.y.f, size.x.f, size.y.f)
        } else null

        // Any intruders on the enemy ship should also be controllable,
        // including mind controlled enemies (once that's implemented).
        // Mind-controlled crew are then filtered out in the for loop.
        val controllableCrew = ArrayList(ship.friendlyCrew)
        game.enemy?.let { controllableCrew.addAll(it.intruders) }

        for (crew in controllableCrew) {
            // Skip drones, mind controlled crew and the like which the
            // player isn't allowed to control.
            if (!crew.playerControllable)
                continue

            val crewPos = crewScreenPos(crew, playerShipPosition)

            @Suppress("IfThenToElvis")
            val hovered = if (rect != null) {
                // If we're in rectangle mode, check that it intersects the centre of the player's body
                // TODO also check if the player is selecting the crew name box
                rect.contains(crewPos.x + ROOM_SIZE / 2f, crewPos.y + ROOM_SIZE / 2f)
            } else {
                // Otherwise check the point overlaps the player
                isCrewHovered(crew, mouseX, mouseY, crewPos)
            }

            if (hovered) {
                hoveredCrew += crew
            }
        }
    }

    fun saveCrewPositions() {
        ship.saveCrewPositions()

        // Show the popup text
        saveCrewPosMessageTimer = 1f
        saveCrewPosMessageText = "stations_saved"
    }

    fun loadCrewPositions() {
        ship.loadCrewPositions()

        // Show the popup text
        saveCrewPosMessageTimer = 1f
        saveCrewPosMessageText = "return_stations"
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        val window = currentWindow

        // If the player is currently playing through an event, save that.
        if (window is DialogueWindow) {
            val dialogueElem = Element("dialogue")
            window.saveToXML(dialogueElem, refs)
            elem.addContent(dialogueElem)
        }
    }

    fun loadFromXML(elem: Element, refs: RefLoader) {
        val dialogueElem = elem.getChild("dialogue")
        if (dialogueElem != null) {
            currentWindow = DialogueWindow(game, ship, dialogueElem, refs, this::eventDialogueClosed)
        }
    }

    fun showGameOverScreen(outcome: GameOverWindow.Outcome) {
        currentWindow = GameOverWindow(game, outcome)
    }

    fun getCurrentCursor(): Cursor {
        // Use the hacking/teleport/mind control cursor
        (game.clickEvent as? RoomClickCursor)?.getCursor()?.let { return it }

        targetingSelectedWeapon?.let { id ->
            val valid = game.hoveredRoom != null
            val autofire = false // TODO

            // Combine the cursor and crosshairs icon
            val baseName = when {
                valid -> "img/mouse/mouse_crosshairs.png"
                autofire -> "img/mouse/mouse_crosshairs_valid2.png"
                else -> "img/mouse/mouse_crosshairs_valid.png"
            }
            val overlayName = when {
                autofire -> "img/mouse/mouse_crosshairs3_${id + 1}.png"
                else -> "img/mouse/mouse_crosshairs2_${id + 1}.png"
            }
            return game.getCursor("$baseName,$overlayName")
        }

        // TODO the door open/close animation cursor

        return when {
            buttons.any { it.hovered } -> cursorHover
            hoveredCrew.isNotEmpty() -> cursorHover
            else -> cursorNormal
        }
    }

    fun onTextInput(key: Int, c: Char): Boolean {
        pauseWindow?.let { return it.onTextInput(key, c) }
        currentWindow?.let { return it.onTextInput(key, c) }

        return false
    }

    private abstract inner class WeaponDroneButton(pos: IPoint, slotNumber: Int, size: ConstPoint) :
        Button(game, pos, size) {

        abstract val empty: Boolean
        abstract val name: String
        abstract val chargeTime: Float
        abstract val chargeProgress: Float
        abstract val requiredPower: Int
        abstract val zoltanPower: Int
        abstract val isPowered: Boolean
        abstract val isCharged: Boolean
        abstract val isTargeted: Boolean
        abstract val isSelectingTarget: Boolean
        abstract val hasChargeBar: Boolean
        abstract val isBeingHacked: Boolean
        abstract val droneCooldownProgress: Float?
        abstract val tooltip: ITooltipProvider?

        override val disabled: Boolean get() = empty

        protected val mainColour: Colour
            get() = when {
                empty -> WEAPONS_ITEM_DESELECTED
                isBeingHacked -> SYSTEM_HACKED
                droneCooldownProgress != null -> WEAPONS_ITEM_DRONE_COOLDOWN
                !isPowered -> WEAPONS_ITEM_DESELECTED
                isSelectingTarget -> WEAPONS_ITEM_TARGETING
                isCharged -> WEAPONS_ITEM_CHARGED
                else -> WEAPONS_ITEM_SELECTED
            }

        private var hackingSparks: FTLAnimation? = null
        private var hackingOffsetX: Int = 0
        private var hackingMaskY: Int = 0
        private var hackingMirror: Boolean = false
        private var hackingLastNS: Long = 0

        // Move these out to save a few allocations
        // (it's probably well into paranoid territory, but why not)
        private val nameLines by lazy { weaponNameText.wrapString(name, 58) }
        private val weaponNumberString = (slotNumber + 1).toString()

        override fun draw(g: Graphics) {
            if (hovered) {
                g.tooltip = tooltip

                // Draw the glow behind everything else
                game.getImg("img/bar_weapon_minibox_glow.png").draw(pos.x - 18, pos.y - 10)
            }

            // Draw the drone cooldown progress, if applicable
            val cooldownProgress = droneCooldownProgress
            if (cooldownProgress != null) {
                val height = (size.y * cooldownProgress).roundToInt()
                g.colour = DRONE_COOLDOWN_BACKGROUND
                g.fillRect(pos.x, pos.y + size.y - height, size.x, height)
            }

            // Draw the outline box
            g.colour = mainColour
            g.drawRect(pos.x.f, pos.y.f, (size.x - 1).f, (size.y - 1).f)
            g.drawRect((pos.x + 1).f, (pos.y + 1).f, (size.x - 3).f, (size.y - 3).f)

            if (empty)
                return

            if (hasChargeBar) {
                val maxBarSize = 35 - 2
                val barSize = (maxBarSize * chargeTime / maxWeaponChargeTime).toInt()

                // The Y position of the inside of the charge bar, relative to the main weapons box
                val top = maxBarSize - barSize

                // The top point of the triangle
                val triangleTop = top - 7

                for (j in 8 downTo 1) {
                    val triangleY = j + triangleTop
                    if (triangleY < 0)
                        continue
                    val y = pos.y + triangleY
                    g.drawLine((pos.x - j).f, y.f, pos.x.f, y.f)
                }

                if (isTargeted)
                    g.colour = WEAPONS_ITEM_TARGETING

                val chargePx = (barSize * chargeProgress).toInt()
                g.fillRect((pos.x - 5).f, (pos.y + 36 - chargePx).f, 4f, chargePx.f)

                g.colour = mainColour

                g.lineWidth = 2f
                g.drawLine(pos.x - 7.5f, pos.y.f + top.f + 1.5f, pos.x - 7.5f, pos.y + 39 - 1.5f)
                g.drawLine(pos.x - 7.5f, pos.y + 39 - 1.5f, pos.x - 0.5f, pos.y + 39 - 1.5f)
                g.lineWidth = 1f
            }

            // Draw the weapon number box
            val numBoxX = pos.x + size.x - 12
            val numBoxY = pos.y + size.y - 15
            g.lineWidth = 2f
            g.drawLine(numBoxX + 0.5f, numBoxY + 0.5f, numBoxX + 0.5f, numBoxY + 12f + 0.5f)
            g.drawLine(numBoxX + 0.5f, numBoxY + 0.5f, numBoxX + 10f + 0.5f, numBoxY + 0.5f)
            g.lineWidth = 1f

            // Draw the weapon/drone number itself
            val weaponNumberWidth = weaponNumberFont.getWidth(weaponNumberString)
            weaponNumberFont.drawString(
                (numBoxX + 2 + 1 + (8 - weaponNumberWidth) / 2).f,
                pos.y + size.y - 4f,
                weaponNumberString,
                mainColour
            )

            // Draw the item name, which is split across
            // multiple lines to fit in the box.
            var lineY = pos.y + 15
            for (line in nameLines) {
                weaponNameText.drawString(pos.x + 26f, lineY.f, line, mainColour)
                lineY += 15
            }

            for (bar in 0 until requiredPower) {
                val y = pos.y + size.y - 11 - bar * 8

                if (zoltanPower > bar) {
                    g.colour = WEAPONS_ITEM_ENERGY_ZOLTAN
                } else if (droneCooldownProgress != null) {
                    g.colour = WEAPONS_ITEM_DRONE_COOLDOWN
                    g.drawRect((pos.x + 4).f, y.f, (16 - 1).f, (7 - 1).f)
                    continue
                } else if (!isPowered) {
                    g.colour = WEAPONS_ITEM_ENERGY_UNPOWERED
                    g.drawRect((pos.x + 4).f, y.f, (16 - 1).f, (7 - 1).f)
                    continue
                } else if (isBeingHacked) {
                    g.colour = SYSTEM_HACKED
                } else if (isSelectingTarget) {
                    g.colour = WEAPONS_ITEM_TARGETING
                } else if (isCharged) {
                    g.colour = WEAPONS_ITEM_ENERGY_CHARGED
                } else {
                    g.colour = WEAPONS_ITEM_ENERGY_POWERED
                }
                g.fillRect((pos.x + 4).f, y.f, 16f, 7f)
            }

            drawHackingSparks()
        }

        private fun drawHackingSparks() {
            if (!isBeingHacked) {
                hackingSparks = null
                return
            }

            val currentNS = System.nanoTime()
            val dt = (currentNS - hackingLastNS) / 1_000_000_000f
            hackingLastNS = currentNS

            if (hackingSparks == null || hackingSparks?.isStopped == true) {
                hackingSparks = ship.sys.animations["stun_spark_big"].startSingle(game)

                hackingOffsetX = VisualRandom.nextInt(25)
                hackingMaskY = VisualRandom.nextInt(31)
                hackingMirror = VisualRandom.nextBoolean()
            }

            // Update the animation regardless of whether we're paused.
            hackingSparks!!.update(dt)

            val frame = hackingSparks!!.currentFrame
            val cutoutWidth = 69
            val cutoutHeight = 39
            frame.draw(
                // Screen points
                pos.x.f + hackingOffsetX, pos.y.f,
                pos.x.f + hackingOffsetX + cutoutWidth, pos.y.f + cutoutHeight,

                // Image points
                if (hackingMirror) cutoutWidth.f else 0f, hackingMaskY.f,
                if (hackingMirror) 0f else cutoutWidth.f, hackingMaskY.f + cutoutHeight
            )
        }
    }

    /**
     * This is the button that adjusts a system's power.
     */
    private inner class SystemPowerButton(pos: IPoint, val system: AbstractSystem) :
        Button(game, pos, ConstPoint(26, 26)) {

        // Subsystems can't be clicked, so don't play their hover noise.
        // This will also keep hovered set to false, since we don't want
        // to activate any hovering-related stuff for something you can't click.
        override val disabled: Boolean get() = system !is MainSystem

        private val tooltip = system.createTooltip()

        override fun draw(g: Graphics) {
            system.drawIconAndPower(game, g, true, true, hovered, pos.x, pos.y)

            if (rawHovered) {
                g.tooltip = tooltip
            }
        }

        override fun click(button: Int) {
            // You can't adjust the power of subsystems
            if (system !is MainSystem)
                return

            if (button == Input.MOUSE_LEFT_BUTTON) {
                changeSystemPower(system, true)
            } else if (button == Input.MOUSE_RIGHT_BUTTON) {
                changeSystemPower(system, false)
            }
        }
    }

    /**
     * A version of [RoomClickListener] that also sets the mouse cursor.
     */
    private interface RoomClickCursor : RoomClickListener {
        fun getCursor(): Cursor?
    }

    private inner class TeleportRoomListener(val send: Boolean) : RoomClickCursor {
        override fun roomClicked(room: Room, gc: GameContainer) {
            // Can't teleport to/from our own ship
            if (room.ship == ship)
                return

            ship.teleporter!!.selectTeleportAction(send, room)
        }

        override fun getCursor(): Cursor {
            val valid = game.hoveredRoom != null

            return when {
                send && valid -> cursorTeleSendValid
                send -> cursorTeleSendInvalid
                valid -> cursorTeleRetrieveValid
                else -> cursorTeleRetrieveInvalid
            }
        }

        override fun canTargetRoom(room: Room): Boolean {
            // We can only teleport to/from the enemy ship
            return room.ship != ship
        }
    }

    private inner class HackingRoomListener : RoomClickCursor {
        override fun roomClicked(room: Room, gc: GameContainer) {
            // Can't hack our own ship
            if (room.ship == ship)
                return

            ship.hacking!!.selectTarget(room)
        }

        override fun getCursor(): Cursor {
            return cursorHacking
        }
    }

    private inner class MindControlRoomListener : RoomClickCursor {
        override fun roomClicked(room: Room, gc: GameContainer) {
            ship.mindControl!!.selectRoom(room)
        }

        override fun getCursor(): Cursor {
            return when {
                game.hoveredRoom?.ship == game.enemy -> cursorMindValid
                else -> cursorMindInvalid
            }
        }
    }

    private inner class ResourceDeltaText(var x: Float, var y: Float, amount: Int) {
        private var age: Float = 0f

        private val text: String = when {
            amount > 0 -> "+$amount"
            else -> amount.toString()
        }

        val finished: Boolean get() = age >= 1f

        init {
            x -= numberDeltaFont.getWidth(text) / 2f
            y += numberDeltaFont.baselineToTop * numberDeltaFont.scale
        }

        fun draw(g: Graphics) {
            numberDeltaFont.drawStringPaired(numberDeltaFontBg, x, y, text, Colour.black, Colour.white)
        }

        fun update(dt: Float) {
            y += 15f * dt
            age += dt
        }
    }

    companion object {
        /**
         * The diagonal distance (squared) that the crew selection box has to be before it appears. If the
         * player drags out an area smaller than this, it'll be treated as a click and select whoever is
         * standing underneath.
         */
        val SELECTION_BOX_SIZE = 6f.pow(2f).toInt()

        /**
         * This is the font used for truncating crew names, so expose it here.
         */
        const val CREW_NAME_FONT = "JustinFont8"
        const val MAX_NAME_WIDTH = 50

        private const val INSUFFICIENT_SCRAP_FLASH_TIME = 0.35f
        private const val WEAPON_BOX_GLOW = 12

        private const val MIN_BEAM_LENGTH = 10f

        private const val CREW_BOX_WIDTH = 86
        private const val CREW_BOX_HEIGHT = 27
        private const val CREW_BOX_SPACING = 30
    }
}

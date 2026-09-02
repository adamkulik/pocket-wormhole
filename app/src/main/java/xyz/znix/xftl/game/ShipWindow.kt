package xyz.znix.xftl.game

import xyz.znix.xftl.*
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.systems.SubSystem
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.ui.Label
import xyz.znix.xftl.ui.WidgetContainer
import kotlin.math.max

private typealias UndoFn = () -> Unit

class ShipWindow(val game: InGameState, val ship: Ship, initialTab: Tab, private val close: () -> Unit) :
    Window() {
    override val size = ConstPoint(587, 464)

    // Make the system power stuff visible
    override val windowCentreOffset = ConstPoint(-10, 0)

    override val appliesSelfTint: Boolean get() = crewToDismiss != null

    private val acceptButtonImage = game.getImg("img/upgradeUI/buttons_accept_base.png")
    private val undoButtonImage = game.getImg("img/upgradeUI/buttons_undo_base.png")

    private val shipNameFont = game.getFont("c&c", 3f)
    private val numberFont = game.getFont("num_font")
    private val reactorFont = game.getFont("HL1", 2f)
    private val crewNameFont = game.getFont("JustinFont8")
    private val crewMessageFont = game.getFont("JustinFont10")
    private val dismissFont = game.getFont("HL1")
    private val maxFont = game.getFont("HL1", 2f)

    private val upgradeSystemSound = game.sounds.getSample("upgradeSystem")
    private val downgradeSystemSound = game.sounds.getSample("downgradeSystem")

    private var tab: Tab = initialTab

    private val equipmentPanel = ShipEquipmentPanel(game, ship)

    private val infoPanel = InfoPanel(game)

    // These store functions that each undo the last
    // upgrade of a system or the reactor.
    private val systemUpgradeUndos = HashMap<AbstractSystem, ArrayList<UndoFn>>()
    private val reactorUpgradeUndo = ArrayList<UndoFn>()

    private val crewDismissWidget: WidgetContainer = game.uiLoader.load("confirm").mainWidget
    private val crewDismissWidgetPos = Point(0, 0)
    private var crewToDismiss: LivingCrew? = null
    private var crewDismissWidgetButtons: List<Button> = emptyList()
    private val dismissHighlightButtons = ArrayList<Button>()

    // If non-null, this is the crewmember whose name we're changing
    private var renamingCrew: LivingCrew? = null
    private var cursorFlashTimer = 0f

    // If true, the drawing code should re-create any buttons it added
    private var updatingButtons = false

    private val closeButton = Buttons.BasicButton(
        // As an interesting note, in the base game it's one pixel too far right so
        // where it meets up with the side of the ship panel has a small ledge.
        game, position + ConstPoint(428, 471),
        ConstPoint(132, 32), game.translator["button_accept"],
        4, game.getFont("HL2", 3f), 25,
        this::escapePressed
    )

    init {
        // TODO too many items

        updateButtons()

        (crewDismissWidget.byId["message"] as Label).text = game.translator["confirm_dismiss"]

        // Re-perform the layout to fit the message
        crewDismissWidget.root.updateSizes()
        crewDismissWidget.root.expandToParent(ConstPoint.ZERO)
        crewDismissWidget.root.updateLayout()

        crewDismissWidget.addButtonListener("yes") {
            // If the crewmember is in the cloning queue, remove them from there.
            ship.clonebay?.queue?.remove(crewToDismiss!!)

            crewToDismiss!!.removeFromShip()
            crewToDismiss = null
            updateButtons()
        }
        crewDismissWidget.addButtonListener("no") {
            crewToDismiss = null
        }
    }

    private fun updateButtons() {
        buttons.clear()
        dismissHighlightButtons.clear()

        buttons += closeButton

        // The buttons can change their icon depending on which of the other two
        // tabs are selected, so re-create them whenever a different tab is selected.

        if (tab != Tab.UPGRADES)
            buttons += TabButton(Tab.UPGRADES, ConstPoint(-GLOW_WIDTH, -GLOW_WIDTH), ConstPoint(109, 48))
        if (tab != Tab.CREW)
            buttons += TabButton(Tab.CREW, ConstPoint(75, -GLOW_WIDTH), ConstPoint(127, 48))
        if (tab != Tab.EQUIPMENT)
            buttons += TabButton(Tab.EQUIPMENT, ConstPoint(175, -GLOW_WIDTH), ConstPoint(123, 48))

        // Switching tabs away from upgrades commits the system upgrades
        if (tab != Tab.UPGRADES) {
            systemUpgradeUndos.clear()
            reactorUpgradeUndo.clear()
        }

        // Update the button window offsets
        positionUpdated()

        // Update any buttons added in the drawing code
        updatingButtons = true
    }

    override fun draw(g: Graphics) {
        val backgroundImage = game.getImg("img/upgradeUI/Equipment/${tab.textureName}_main.png")

        backgroundImage.draw(
            position.x - GLOW_WIDTH,
            position.y - GLOW_WIDTH
        )

        acceptButtonImage.draw(position.x + 405f, position.y + 464f)

        when (tab) {
            Tab.UPGRADES -> drawUpgrades(g)
            Tab.CREW -> drawCrew(g)
            Tab.EQUIPMENT -> drawEquipment(g)
        }

        if (updatingButtons) {
            // Update any newly-created button positions
            positionUpdated()
            updatingButtons = false
        }

        // When we're dismissing crew we grey out everything else, this
        // means fiddling with how the buttons are drawn.
        if (crewToDismiss != null) {
            drawButtonsDismissing(g)
        } else {
            for (button in buttons) {
                button.draw(g)
            }
        }

        equipmentPanel.drawDrag(g)
    }

    private fun drawButtonsDismissing(g: Graphics) {
        for (button in buttons) {
            if (dismissHighlightButtons.contains(button))
                continue

            button.draw(g)
        }

        // The dismiss popup has to be drawn over the buttons that make up other crewmembers
        game.shipUI.drawWindowBackgroundTint(g)

        for (button in dismissHighlightButtons) {
            button.draw(g)
        }

        g.pushTransform()
        g.translate(position.x + crewDismissWidgetPos.x.f, position.y + crewDismissWidgetPos.y.f)
        crewDismissWidget.draw(g)
        g.popTransform()
    }

    override fun shipModified() {
        super.shipModified()
        equipmentPanel.shipModified()
    }

    private fun drawUpgrades(g: Graphics) {
        // Draw the ship name
        val name = game.translator[ship.type.shipTitle!!]

        val nameWidth = shipNameFont.getWidth(name)

        shipNameFont.drawString(
            position.x + 138f + (327f - nameWidth) / 2,
            position.y + 75f,
            name,
            Colour.white
        )

        undoButtonImage.draw(position.x + 3f, position.y + 464f)
        if (updatingButtons) {
            buttons += object : Buttons.BasicButton(
                game, ConstPoint(26, 471), ConstPoint(97, 32),
                game.translator["button_undo"],
                4, game.getFont("HL2", 3f), 25,
                this::undoAllSystems
            ) {
                // Grey the button out when there's nothing to undo
                override val disabled: Boolean get() = systemUpgradeUndos.all { it.value.isEmpty() } && reactorUpgradeUndo.isEmpty()
            }
        }

        // Draw the system power info.
        // Note the reactor button draws this itself.
        val hoveredButton = buttons.filterIsInstance(UpgradeButton::class.java).firstOrNull { it.hovered }
        val hoveredSystem = hoveredButton?.system
        if (hoveredSystem != null) {
            val undoablePower = systemUpgradeUndos[hoveredSystem]?.size ?: 0
            infoPanel.drawDescriptionBoxSystem(hoveredSystem)
            infoPanel.drawSystemPowerBox(g, hoveredSystem.blueprint, hoveredSystem.energyLevels, undoablePower)
        }

        // Draw the systems
        for (i in 0 until 8) {
            if (!updatingButtons)
                continue

            val system = ship.mainSystems.getOrNull(i)

            val price: Int? = when {
                system == null -> null
                system.energyLevels == system.blueprint.maxPower -> null
                else -> system.blueprint.upgradeCost[system.energyLevels - 1]
            }

            val isFullyUpgraded = system != null && price == null

            val baseImage = when {
                system == null -> "img/upgradeUI/upgrade_system_bar_none.png"
                isFullyUpgraded -> "img/upgradeUI/upgrade_system_bar_max_on.png"
                else -> "img/upgradeUI/upgrade_system_bar_on.png"
            }
            val selectImage = when {
                system == null -> "img/upgradeUI/upgrade_system_bar_none.png"
                isFullyUpgraded -> "img/upgradeUI/upgrade_system_bar_max_select2.png"
                else -> "img/upgradeUI/upgrade_system_bar_select2.png"
            }

            buttons += UpgradeButton(
                ConstPoint(32 + 66 * i, 115), ConstPoint(66, 150),
                game.getImg(selectImage), game.getImg(baseImage),
                system, price
            )
        }

        // Draw the subsystems
        // TODO only go until 3 on non-AE?
        val subsystems = ship.rooms.mapNotNull { it.system as? SubSystem }.sortedBy { it.sortingType }
        for (i in 0 until 4) {
            if (!updatingButtons)
                continue

            val system = if (i < subsystems.size) subsystems[i] else null

            val price: Int? = when {
                system == null -> null
                system.energyLevels == system.blueprint.maxPower -> null
                else -> system.blueprint.upgradeCost[system.energyLevels - 1]
            }

            val isFullyUpgraded = system != null && price == null

            val baseImage = when {
                system == null -> "img/upgradeUI/upgrade_subsystem_bar_none.png"
                isFullyUpgraded -> "img/upgradeUI/upgrade_subsystem_bar_max_on.png"
                else -> "img/upgradeUI/upgrade_subsystem_bar_on.png"
            }
            val selectImage = when {
                system == null -> "img/upgradeUI/upgrade_subsystem_bar_none.png"
                isFullyUpgraded -> "img/upgradeUI/upgrade_subsystem_bar_max_select2.png"
                else -> "img/upgradeUI/upgrade_subsystem_bar_select2.png"
            }

            buttons += UpgradeButton(
                ConstPoint(9 + 66 * i, 330), ConstPoint(66, 113),
                game.getImg(selectImage), game.getImg(baseImage),
                system, price
            )
        }

        // Add the reactor button
        if (updatingButtons) {
            val reactorImg = game.getImg("img/upgradeUI/Equipment/equipment_reactor_on.png")
            val reactorHighlight = game.getImg("img/upgradeUI/Equipment/equipment_reactor_select2.png")
            buttons += object : Button(game, ConstPoint(298, 327), reactorImg.imageSize) {
                val currentPrice: Int
                    get() {
                        // Expensive first power on the Cernkov
                        if (ship.purchasedReactorPower < 5)
                            return 30

                        return (ship.purchasedReactorPower / 5) * 5 + 15
                    }

                override fun draw(g: Graphics) {
                    if (hovered) {
                        reactorHighlight.draw(pos)

                        infoPanel.drawDescriptionBox(
                            GameText.localised("upgrade_reactor"),
                            GameText.localised("reactor_desc"),
                            null, emptyList(),
                            InfoPanel.INFO_HEIGHT_SYSTEM
                        )
                    } else {
                        reactorImg.draw(pos)
                    }

                    val fontColour = when (hovered) {
                        true -> Constants.STORE_BUY_HOVER
                        false -> Constants.SECTOR_CUTOUT_TEXT
                    }

                    // Figure out the power range that should show as downgrades
                    val lastNonRefundablePower = ship.purchasedReactorPower - reactorUpgradeUndo.size
                    val refundRange = lastNonRefundablePower until ship.purchasedReactorPower

                    // Draw the energy bars
                    for (level in 0 until ship.maxReactorPower) {
                        g.colour = when {
                            level in refundRange -> Constants.SYS_ENERGY_PURCHASE_UNDOABLE
                            ship.purchasedReactorPower > level -> Constants.SYS_ENERGY_ACTIVE
                            hovered -> Constants.SYS_ENERGY_PURCHASE_HOVER
                            else -> Constants.SYS_ENERGY_PURCHASE
                        }

                        g.fillRect(
                            pos.x + 30f + (level / 5) * 44,
                            pos.y + 66f - (level % 5) * 13,
                            32f, 8f
                        )
                    }

                    // Draw the current price, or two dashes if it's maxed out.
                    val priceStr = when (ship.purchasedReactorPower < ship.maxReactorPower) {
                        true -> currentPrice.toString()
                        false -> "--"
                    }
                    numberFont.drawString(pos.x + 235f, pos.y + 105f, priceStr, fontColour)

                    // Draw the 'n power bars' text - this is annoyingly mixed between two fonts
                    // TODO mix them properly to work in languages where the power number doesn't
                    //  come first - is this something FTL does?
                    val text = game.translator["upgrade_reactor_power"].replaceArg("")
                    reactorFont.drawStringLeftAligned(pos.x + 179f, pos.y + 105f, text, fontColour)
                    numberFont.drawStringLeftAligned(
                        pos.x + 47f,
                        pos.y + 105f,
                        ship.purchasedReactorPower.toString(),
                        fontColour
                    )
                }

                override fun click(button: Int) {
                    if (button == Input.MOUSE_RIGHT_BUTTON) {
                        val undoLast = reactorUpgradeUndo.lastOrNull() ?: return
                        reactorUpgradeUndo.remove(undoLast)
                        undoLast()
                        downgradeSystemSound.play()
                        return
                    }

                    if (button != Input.MOUSE_LEFT_BUTTON)
                        return

                    if (ship.purchasedReactorPower >= ship.maxReactorPower)
                        return

                    // Store the price so undos work properly.
                    val price = currentPrice

                    if (ship.scrap < price) {
                        game.shipUI.playInsufficientScrapAnimation()
                        return
                    }
                    ship.scrap -= price

                    ship.purchasedReactorPower++

                    // This is how the user can revert an upgrade
                    reactorUpgradeUndo += {
                        ship.scrap += price
                        ship.purchasedReactorPower--
                    }

                    upgradeSystemSound.play()
                }
            }
        }
    }

    inner class UpgradeButton(
        pos: ConstPoint, size: ConstPoint,
        private val selectImage: Image,
        private val baseImage: Image,
        val system: AbstractSystem?,
        private val upgradePrice: Int?
    ) : Button(game, pos, size) {
        override val disabled: Boolean get() = system == null

        override fun draw(g: Graphics) {
            // Draw the main button image
            val image = if (hovered) selectImage else baseImage
            image.draw(pos.x, pos.y)

            if (system == null)
                return

            val fontColour = when (hovered) {
                true -> Constants.STORE_BUY_HOVER
                false -> Constants.SECTOR_CUTOUT_TEXT
            }

            // Draw the system icon
            val systemIcon = game.getImg(system.blueprint.onIconPath)
            systemIcon.draw(
                pos.x - SystemBlueprint.ICON_GLOW + 19f,
                pos.y + size.y - SystemBlueprint.ICON_GLOW - 65f
            )

            // Figure out the power range that should show as downgrades
            val undoablePower = systemUpgradeUndos[system]?.size ?: 0
            val lastNonRefundablePower = system.energyLevels - undoablePower + 1 // Power here is 1-indexed
            val refundRange = lastNonRefundablePower..system.energyLevels

            // Draw the energy bars
            for (level in 1..system.blueprint.maxPower) {
                g.colour = when {
                    level in refundRange -> Constants.SYS_ENERGY_PURCHASE_UNDOABLE
                    system.energyLevels >= level -> Constants.SYS_ENERGY_ACTIVE
                    hovered -> Constants.SYS_ENERGY_PURCHASE_HOVER
                    else -> Constants.SYS_ENERGY_PURCHASE
                }

                g.fillRect(
                    pos.x + 24f,
                    pos.y + size.y - 76f - (level - 1) * 8f,
                    15f, 6f
                )
            }

            // Draw the scrap amount
            if (upgradePrice != null) {
                numberFont.drawString(pos.x + 30f, pos.y + size.y - 8f, upgradePrice.toString(), fontColour)
            }

            if (system.energyLevels == system.blueprint.maxPower) {
                val maxStr = game.translator["upgrade_max"]
                maxFont.drawStringCentred(pos.x.f, pos.y + size.y - 8f, size.x.f, maxStr, fontColour)
            }
        }

        override fun click(button: Int) {
            if (button == Input.MOUSE_RIGHT_BUTTON) {
                val undos = systemUpgradeUndos[system] ?: return
                val undoLast = undos.lastOrNull() ?: return
                undos.remove(undoLast)
                undoLast()
                downgradeSystemSound.play()
                return
            }

            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            if (upgradePrice == null)
                return

            if (ship.scrap < upgradePrice) {
                game.shipUI.playInsufficientScrapAnimation()
                return
            }
            ship.scrap -= upgradePrice

            system!!.energyLevels++

            val undos = systemUpgradeUndos.getOrPut(system) { ArrayList() }
            undos += {
                system.energyLevels--
                ship.scrap += upgradePrice
                updateButtons()
            }

            // Replace this button to reflect the upgrade
            updateButtons()

            upgradeSystemSound.play()
        }
    }

    private fun drawCrew(g: Graphics) {
        val crew = ship.sys.playerCrew

        crewMessageFont.drawString(position.x + 19f, position.y + 67f, game.translator["rename_note"], Colour.white)

        // First row
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 0, 88 + 133 * 0), 0)
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 1, 88 + 133 * 0), 1)
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 2, 88 + 133 * 0), 2)

        // Second row
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 0, 88 + 133 * 1), 3)
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 1, 88 + 133 * 1), 4)
        drawCrewBox(g, crew, ConstPoint(68 + 170 * 2, 88 + 133 * 1), 5)

        // Third row
        if (game.playerHasTooManyCrew()) {
            // Too many crew members, show a 3x3 grid
            drawCrewBox(g, crew, ConstPoint(68 + 170 * 0, 88 + 133 * 2), 6)
            drawCrewBox(g, crew, ConstPoint(68 + 170 * 1, 88 + 133 * 2), 7)
            drawCrewBox(g, crew, ConstPoint(68 + 170 * 2, 88 + 133 * 2), 8)
        } else {
            drawCrewBox(g, crew, ConstPoint(154, 88 + 133 * 2), 6)
            drawCrewBox(g, crew, ConstPoint(324, 88 + 133 * 2), 7)
        }

        if (crewToDismiss != null && updatingButtons) {
            crewDismissWidgetButtons = crewDismissWidget.buildButtons(game, this, crewDismissWidgetPos)
            for (button in crewDismissWidgetButtons) {
                button.windowOffset = position
            }
        }

        if (game.playerHasTooManyCrew() && updatingButtons) {
            buttons += ShipEquipmentPanel.SellDropBox.create(
                game,
                ShipEquipmentPanel.SellDropBox.Type.TOO_MANY_CREW,
                ConstPoint(-275, 107)
            ) { null }
        }
    }

    private fun drawCrewBox(g: Graphics, crewList: List<LivingCrew>, boxPos: ConstPoint, id: Int) {
        val crew = crewList.getOrNull(id)

        if (crew == null) {
            val boxEmpty = game.getImg("img/upgradeUI/Equipment/box_crew_off.png")
            boxEmpty.draw(boxPos + position)
            return
        }

        val dismissButtonBox = game.getImg("img/customizeUI/box_crewcustom_on.png")
        val dismissButtonBoxHover = game.getImg("img/customizeUI/box_crewcustom_selected.png")

        val boxNormal = game.getImg("img/upgradeUI/Equipment/box_crew_on.png")
        val boxHover = game.getImg("img/upgradeUI/Equipment/box_crew_selected.png")

        if (crewToDismiss == crew) {
            val margin = 22 // Min space between window and warning
            val boxCentre = boxPos.x + boxNormal.width / 2
            val boxWidth = crewDismissWidget.root.size.x
            val baseRelX = boxCentre - boxWidth / 2
            val relX = baseRelX.coerceIn(margin..size.x - boxWidth - margin)

            crewDismissWidgetPos.x = relX
            crewDismissWidgetPos.y = boxPos.y + 70 + 23
        }

        if (!updatingButtons)
            return

        val mainButton = object : Button(game, boxPos, boxNormal.imageSize) {
            override val makesHoverNoise: Boolean get() = false

            var nameHover = false

            override fun draw(g: Graphics) {
                val box = if (hovered) boxHover else boxNormal
                box.draw(pos)

                val dismissBox = if (hovered) dismissButtonBoxHover else dismissButtonBox
                dismissBox.draw(pos.x.f, pos.y + 62f)

                if (renamingCrew == crew) {
                    // Draw the rename box
                    val width = 150
                    val height = 23
                    val boxX = pos.x + (size.x - width) / 2
                    val boxY = pos.y + 43

                    g.colour = Colour.white
                    g.fillRect(boxX, boxY, width, height)
                    g.colour = Colour.black
                    g.fillRect(boxX + 1, boxY + 1, width - 2, height - 2)

                    val name = crew.info.name
                    val nameWidth = crewMessageFont.getWidth(name) + 2 + 2 // 2+2 for the gap and cursor
                    val nameX = boxX + (width - nameWidth) / 2
                    crewMessageFont.drawString(nameX.f, pos.y + 61f, name, Colour.white)

                    // Draw the cursor
                    cursorFlashTimer += game.renderingDeltaTime
                    val period = 0.5f
                    if (cursorFlashTimer > period)
                        cursorFlashTimer -= period

                    if (cursorFlashTimer < period / 2f) {
                        g.colour = Colour.yellow
                        g.fillRect(nameX + nameWidth - 2, boxY + 8, 2, 13)
                    }
                } else {
                    // Draw the name
                    val name = crew.info.shortName
                    val nameX = (96 - crewNameFont.getWidth(name)) / 2
                    val nameColour = when (nameHover) {
                        true -> Constants.CREW_RENAME_TEXT_COLOUR
                        false -> Colour.white
                    }
                    crewNameFont.drawString(pos.x + 2f + nameX, pos.y + 61f, name, nameColour)
                }

                // Draw the crewmember portrait
                // TODO align properly
                val portrait = crew.icon.currentFrame
                crew.drawPortrait(
                    pos.x + 2 + (96 - portrait.width * 2) / 2,
                    pos.y + 2 + (45 - portrait.height * 2) / 2,
                    false,
                    2f
                )

                // Draw the information about this crewmember while hovering over them
                if (hovered) {
                    infoPanel.drawCrew(g, crew.info)
                }
            }

            override fun update(x: Int, y: Int, blockHover: Boolean) {
                super.update(x, y, blockHover)

                nameHover = x in (pos.x..pos.x + 100) && y in (pos.y + 49..pos.y + 65)
            }

            override fun click(button: Int) {
                if (button != Input.MOUSE_LEFT_BUTTON || !nameHover)
                    return

                renamingCrew = crew
            }
        }

        val dismissButton = Buttons.BasicButton(
            game, boxPos + ConstPoint(4, 70), ConstPoint(92, 12),
            game.translator["crewbox_dismiss"],
            2, dismissFont, 9
        ) {
            crewToDismiss = crew
            updateButtons()
        }

        buttons += mainButton
        buttons += dismissButton

        // Don't grey out the buttons corresponding to crew we're about to dismiss
        if (crewToDismiss == crew) {
            dismissHighlightButtons += mainButton
            dismissHighlightButtons += dismissButton
        }
    }

    private fun drawEquipment(g: Graphics) {
        equipmentPanel.draw(g)

        // Draw the information for the currently-hovered blueprint.
        equipmentPanel.drawInfoPanel(infoPanel)
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        if (crewToDismiss != null) {
            for (btn in crewDismissWidgetButtons) {
                btn.mouseDown(button, x, y)
            }
            return
        }

        // If we were renaming a crewmember, we're not any more
        renamingCrew = null

        super.mouseClick(button, x, y)

        if (tab == Tab.EQUIPMENT)
            equipmentPanel.mouseClick(button, x, y)
    }

    override fun mouseReleased(button: Int, x: Int, y: Int) {
        if (crewToDismiss != null) {
            return
        }

        super.mouseReleased(button, x, y)

        if (tab == Tab.EQUIPMENT)
            equipmentPanel.mouseReleased(button, x, y)
    }

    override fun updateUI(x: Int, y: Int) {
        // If the crew dismiss window is up, block all other interactions.
        if (crewToDismiss != null) {
            for (button in crewDismissWidgetButtons) {
                button.update(x, y, false)
            }
            return
        }

        super.updateUI(x, y)

        if (tab == Tab.EQUIPMENT)
            equipmentPanel.updateUI(x, y)
    }

    override fun escapePressed() {
        close()
    }

    override fun positionUpdated() {
        super.positionUpdated()

        infoPanel.position = position + ConstPoint(size.x + 13, 74)
        equipmentPanel.position = position
    }

    override fun onTextInput(key: Int, c: Char): Boolean {
        val info = renamingCrew?.info ?: return false

        if (key == Input.KEY_BACK) {
            val endPos = max(0, info.name.length - 1)
            info.name = info.name.substring(0, endPos)
            return true
        }

        if (key == Input.KEY_ENTER) {
            renamingCrew = null
            return true
        }

        if (c != 0.toChar()) {
            if (info.name.length < 15)
                info.name += c
            return true
        }

        return false
    }

    private fun undoAllSystems() {
        for (undos in systemUpgradeUndos.values) {
            for (undo in undos) {
                undo()
            }
        }
        for (undo in reactorUpgradeUndo) {
            undo()
        }
        systemUpgradeUndos.clear()
        reactorUpgradeUndo.clear()

        downgradeSystemSound.play()
    }

    enum class Tab(val textureName: String) {
        UPGRADES("upgrades"),
        CREW("crew"),
        EQUIPMENT("equipment"),
    }

    private inner class TabButton(val newTab: Tab, pos: ConstPoint, size: ConstPoint) : Button(game, pos, size) {
        val images =
            ButtonImageSet.select2(game, "img/upgradeUI/Equipment/tabButtons/${tab.textureName}_${newTab.textureName}")

        override val disabled: Boolean
            get() = when (tab) {
                Tab.CREW -> game.playerHasTooManyCrew()
                else -> false
            }

        override fun draw(g: Graphics) {
            val image = when {
                disabled -> images.off
                hovered -> images.hover
                else -> images.normal
            }
            image.draw(pos)
        }

        override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON || disabled)
                return

            tab = newTab
            updateButtons()
        }
    }

    companion object {
        const val GLOW_WIDTH: Int = 7
    }
}

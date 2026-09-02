package xyz.znix.xftl.game

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.ui.ImageView
import xyz.znix.xftl.ui.Label
import xyz.znix.xftl.ui.WidgetContainer
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.min

/**
 * This contains the drag-and-drop equipment UI used in the equipment tab
 * of the player ship UI, and in the sell tab of the store.
 */
class ShipEquipmentPanel(private val game: InGameState, val ship: Ship) {
    var position: IPoint = ConstPoint.ZERO
        set(value) {
            if (field == value)
                return
            field = value
            updateButtons()
        }

    var sellUI: Boolean = false

    private val sectionFont = game.getFont("HL2", 2f)
    private val missingSystemFont = game.getFont("HL2", 3f)
    private val sellBoxFont = game.getFont("HL1", 2f)
    private val sellPriceFont = game.getFont("num_font")
    private val augmentFont = game.getFont("JustinFont12Bold")

    private val sellSound = game.sounds.getSample("sell")

    private val buttons = ArrayList<Button>()
    private val weaponButtons = ArrayList<Buttons.DragDropBlueprintButton>()
    private val droneButtons = ArrayList<Buttons.DragDropBlueprintButton>()
    private val augmentButtons = ArrayList<Buttons.DragDropBlueprintButton>()
    private val cargoButtons = ArrayList<Buttons.DragDropBlueprintButton>()
    private var sellButton: Button? = null // The box for selling and leaving behind items

    private var draggingBlueprint: Buttons.DragDropBlueprintButton? = null
        set(value) {
            field = value
            for (button in buttons) {
                if (button !is Buttons.DragDropBlueprintButton)
                    continue
                button.currentlyDraggedBlueprint = value?.blueprint
            }
        }

    // Note: the buttons are first populated when the position is set

    fun draw(g: Graphics) {
        sectionFont.drawString(
            position.x + 11f,
            position.y + 60f,
            game.translator["equipment_frame_weapons"],
            Constants.JUMP_DISABLED_TEXT
        )
        sectionFont.drawString(
            position.x + 11f,
            position.y + 170f,
            game.translator["equipment_frame_drones"],
            Constants.JUMP_DISABLED_TEXT
        )
        sectionFont.drawString(
            position.x + 11f,
            position.y + 280f,
            game.translator["equipment_frame_cargo"],
            Constants.JUMP_DISABLED_TEXT
        )
        sectionFont.drawString(
            position.x + 300f,
            position.y + 280f,
            game.translator["equipment_frame_augments"],
            Constants.JUMP_DISABLED_TEXT
        )

        if (ship.drones == null) {
            missingSystemFont.drawString(
                position.x + 108f,
                position.y + 227f,
                game.translator["equipment_no_system"],
                Constants.SECTOR_CUTOUT_TEXT
            )
        }

        for (button in buttons) {
            button.draw(g)
        }
    }

    fun drawDrag(g: Graphics) {
        // Draw the dragged bit of equipment above everything else
        draggingBlueprint?.drawDrag(g)
    }

    fun shipModified() {
        updateButtons()
    }

    private fun updateButtons() {
        buttons.clear()

        // Draw the weapons
        // Note: I think all the playable ships have weaponSlots set?
        weaponButtons.clear()
        for (i in 0 until ship.weaponSlots!!) {
            val weapon = ship.hardpoints[i].weapon

            val images = ButtonImageSet.selected(game, "img/upgradeUI/Equipment/box_weapons", true)
            val padding = 8
            val baseX = (579 - (images.normal.width + padding) * ship.weaponSlots!!) / 2
            val buttonPos = ConstPoint(baseX + i * (images.normal.width + padding), 70)

            // Use a separate variable so we can use the button in its callback.
            lateinit var button: Buttons.DragDropBlueprintButton
            button = Buttons.DragDropBlueprintButton(
                buttonPos, game, images,
                { it is AbstractWeaponBlueprint },
                weapon?.type
            ) {
                draggingBlueprint = button
            }
            buttons += button
            weaponButtons += button
        }

        // Draw the drones
        droneButtons.clear()
        val droneSystem = ship.drones
        if (droneSystem != null) {
            val images = ButtonImageSet.selected(game, "img/upgradeUI/Equipment/box_drones", true)
            val padding = 8
            val baseX = (579 - (images.normal.width + padding) * droneSystem.drones.size) / 2

            for ((i, droneInfo) in droneSystem.drones.withIndex()) {
                val buttonPos = ConstPoint(baseX + i * (images.normal.width + padding), 180)
                val drone = droneInfo?.type

                // Use a separate variable so we can use the button in it's callback.
                lateinit var button: Buttons.DragDropBlueprintButton
                button = Buttons.DragDropBlueprintButton(buttonPos, game, images, { it is DroneBlueprint }, drone) {
                    draggingBlueprint = button
                }
                buttons += button
                droneButtons += button
            }
        }

        // Draw the augments area
        augmentButtons.clear()
        for (i in 0 until 3) {
            val augment = ship.augments.getOrNull(i)

            val buttonPos = ConstPoint(323, 293 + i * 60)
            val size = ConstPoint(235, 40)

            // Use a separate variable so we can use the button in its callback.
            lateinit var button: Buttons.DragDropBlueprintButton
            button = object : Buttons.DragDropBlueprintButton(
                buttonPos, game, null, size,
                { it is AugmentBlueprint },
                augment,
                { draggingBlueprint = button }
            ) {
                override fun draw(g: Graphics) {
                    // Draw the empty box
                    g.colour = Constants.AUGMENT_EMPTY_OUTLINE
                    g.fillRect(pos.x.f, pos.y.f, size.x.f, size.y.f)
                    g.colour = Constants.AUGMENT_EMPTY_INSIDE
                    g.fillRect(pos.x + 3f, pos.y + 3f, size.x - 6f, size.y - 6f)

                    // Draw the semi-transparent augment on top of it
                    if (dragPosition == null) {
                        drawAugment(g, pos)
                    }
                }

                override fun drawDrag(g: Graphics) {
                    drawAugment(g, dragPosition ?: return)
                }

                private fun drawAugment(g: Graphics, pos: IPoint) {
                    val aug = blueprint as AugmentBlueprint? ?: return

                    // Draw the borders. Since the middle is semi-transparent, we can't
                    // just fill in the whole thing twice to get our border easily.
                    g.colour = when {
                        dragPosition != null -> Constants.AUGMENT_BOX_OUTLINE
                        hovered -> Constants.AUGMENT_BOX_OUTLINE_HOVER
                        else -> Constants.AUGMENT_BOX_OUTLINE
                    }
                    // Left and right
                    g.fillRect(pos.x + 0f, pos.y + 0f, 3f, size.y.f)
                    g.fillRect(pos.x + size.x - 3f, pos.y + 0f, 3f, size.y.f)

                    // Top and bottom
                    g.fillRect(pos.x + 3f, pos.y + 0f, size.x - 6f, 3f)
                    g.fillRect(pos.x + 3f, pos.y + size.y - 3f, size.x - 6f, 3f)

                    // Fill in the background
                    g.colour = Constants.AUGMENT_BOX_INSIDE
                    g.fillRect(pos.x + 3f, pos.y + 3f, size.x - 6f, size.y - 6f)

                    // Draw the name
                    val name = aug.translateTitle(game)
                    augmentFont.drawStringCentred(pos.x.f, pos.y.f + 27f, size.x.f, name, Constants.AUGMENT_NAME_TEXT)
                }
            }
            buttons += button
            augmentButtons += button
        }

        // Draw the cargo area
        cargoButtons.clear()
        for (i in 0 until 4) {
            val blueprint = ship.cargoBlueprints[i]

            val weapons = ButtonImageSet.selected(game, "img/upgradeUI/Equipment/box_weapons", true)
            val drones = ButtonImageSet.selected(game, "img/upgradeUI/Equipment/box_drones", true)

            val base = ButtonImageSet(
                game.getImg("img/upgradeUI/Equipment/box_base_off.png"),
                game.getImg("img/upgradeUI/Equipment/box_base_off.png"), // There's no 'on' image
                game.getImg("img/upgradeUI/Equipment/box_base_off_selected.png")
            )

            val buttonPos = ConstPoint(
                23 + 130 * (i % 2),
                293 + 80 * (i / 2)
            )

            // Use a separate variable so we can use the button in it's callback.
            lateinit var button: Buttons.DragDropBlueprintButton
            button = object : Buttons.DragDropBlueprintButton(
                buttonPos, game, base,
                { it !is AugmentBlueprint },
                blueprint,
                { draggingBlueprint = button }) {

                // Change the card image depending on our contents
                override val image: ButtonImageSet
                    get() {
                        // If our contents is being dragged, don't keep the weapon/drone
                        // icon on the card.
                        if (dragPosition != null)
                            return base

                        return when (this.blueprint) {
                            is AbstractWeaponBlueprint -> weapons
                            is DroneBlueprint -> drones
                            else -> base
                        }
                    }
            }
            buttons += button
            cargoButtons += button
        }

        // Draw the sell/leave behind box - do this here since
        // the sell and leave behind (when you get a piece of
        // equipment but your cargo is full) UIs are similar.
        sellButton = null
        if (sellUI) {
            sellButton = SellDropBox.create(
                game,
                SellDropBox.Type.SELL_EQUIPMENT,
                ConstPoint(-275, 107)
            ) { draggingBlueprint?.blueprint }

            buttons += sellButton!!
        }

        for (button in buttons) {
            button.windowOffset = position
        }
    }

    fun updateUI(x: Int, y: Int) {
        for (button in buttons) {
            button.update(x, y)
        }

        // If we're dragging a blueprint around, update it.
        draggingBlueprint?.dragPosition = ConstPoint(x, y)
    }

    fun mouseClick(button: Int, x: Int, y: Int) {
        // Copied from Window.
        // Mouse clicking may change the buttons array (eg in the store
        // window when switching tabs), so copy it.
        for (btn in ArrayList(buttons)) {
            btn.mouseDown(button, x, y)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun mouseReleased(button: Int, x: Int, y: Int) {
        if (draggingBlueprint != null)
            dropBlueprint()
    }

    fun getHoveredBlueprint(): Blueprint? {
        // Don't show anything while a blueprint is being dragged.
        if (draggingBlueprint != null)
            return null

        val hoveredButton = buttons.firstOrNull { it.hovered }
        if (hoveredButton !is Buttons.DragDropBlueprintButton)
            return null

        return hoveredButton.blueprint
    }

    fun drawInfoPanel(infoPanel: InfoPanel) {
        val blueprint = getHoveredBlueprint() ?: return
        return when (blueprint) {
            is DroneBlueprint -> infoPanel.drawDrone(blueprint)
            is AugmentBlueprint -> infoPanel.drawAugment(blueprint)
            is AbstractWeaponBlueprint -> infoPanel.drawWeapon(blueprint)
            else -> error("Cannot draw info panel for $blueprint")
        }
    }

    private fun dropBlueprint() {
        val drag = draggingBlueprint!!
        drag.dragPosition = null
        draggingBlueprint = null

        data class SlotAccess(
            val get: () -> Blueprint?,
            val set: (Blueprint?) -> Unit
        )

        fun getAccess(target: Button): SlotAccess? {
            // Check the weapons
            for ((i, button) in weaponButtons.withIndex()) {
                if (button != target)
                    continue

                return SlotAccess(
                    { ship.hardpoints[i].weapon?.type },
                    { ship.hardpoints[i].weapon = (it as? AbstractWeaponBlueprint)?.buildInstance(ship) }
                )
            }

            // Check the drone system
            for ((i, button) in droneButtons.withIndex()) {
                if (button != target)
                    continue

                val drones = ship.drones ?: break

                return SlotAccess(
                    { drones.drones[i]?.type },
                    { drone ->
                        // If the current slot contains an already-deployed drone, get rid of it.
                        drones.drones[i]?.instance?.removeInstance()

                        drones.drones[i] = drone?.let { Drones.DroneInfo(it as DroneBlueprint, null) }
                    }
                )
            }

            // Check the augments
            for ((i, button) in augmentButtons.withIndex()) {
                if (button != target)
                    continue

                // Grab the augment here, in case the ordering changes
                val aug = ship.augments.getOrNull(i)

                return SlotAccess(
                    { aug },
                    {
                        require(it is AugmentBlueprint?)
                        if (aug != null) {
                            require(ship.augments.remove(aug)) { "Augment '${aug.name}' disappeared while dragging!" }
                        }
                        if (it != null) {
                            val index = min(ship.augments.size, i)
                            ship.augments.add(index, it)
                        }
                    }
                )
            }

            // Check the cargo bay
            for ((i, button) in cargoButtons.withIndex()) {
                if (button != target)
                    continue

                return SlotAccess(
                    { ship.cargoBlueprints[i] },
                    { ship.cargoBlueprints[i] = it }
                )
            }

            // Check the sell button
            if (target == sellButton) {
                return SlotAccess({ null }) {
                    // Give the player scrap for the item
                    ship.scrap += (it?.cost ?: 0) / 2

                    // Do nothing with it, thus destroying it.

                    sellSound.play()
                }
            }

            return null
        }

        // Find the blueprint the user is trying to drop the item into
        val hovered = buttons.firstOrNull { it.hovered } ?: return

        // Get access to where this blueprint is being dragged to and from
        val src = getAccess(drag) ?: return
        val dst = getAccess(hovered) ?: return

        // Make sure something crazy hasn't happened
        require(drag.blueprint == src.get())

        // Check if the blueprint will fit - the sell button is the exception
        // here, you can sell anything you can drag.
        if (hovered is Buttons.DragDropBlueprintButton && !hovered.compatible(drag.blueprint!!))
            return

        // If the user drops one blueprint onto another, they swap.
        // Make sure that'll work.
        val replacing = dst.get()
        if (replacing != null && !drag.compatible(replacing))
            return

        // Swap them
        dst.set(drag.blueprint)
        src.set(replacing)

        ship.cargoUpdated()
    }

    class SellDropBox private constructor(
        game: InGameState, pos: IPoint,
        private val type: Type,
        private val container: WidgetContainer,
        private val getDrag: () -> Blueprint?,
    ) :
        Button(game, pos, container.root.size) {

        override val makesHoverNoise: Boolean get() = false

        private val images: List<ImageView> = container.allWidgets.filterIsInstance(ImageView::class.java)

        private val highlightingLabels: List<Label> = listOfNotNull(
            container.byId["message"] as Label,
            container.byId["value_label"] as Label?,
            container.byId["price"] as Label?,
        )

        private val price = container.byId["price"] as Label?

        override fun draw(g: Graphics) {
            val normalPath = when (type) {
                Type.SELL_EQUIPMENT -> "img/dropbox_sell_on.png"
                else -> "img/dropbox_on.png"
            }
            val hoverPath = when (type) {
                Type.SELL_EQUIPMENT -> "img/dropbox_sell_select2.png"
                else -> "img/dropbox_select2.png"
            }

            val draggingBlueprint = getDrag()

            val hoverActive = hovered && draggingBlueprint != null
            val image = when {
                hoverActive -> game.getImg(hoverPath)
                else -> game.getImg(normalPath)
            }
            for (view in images) {
                view.image = image
            }

            val colour = when {
                hoverActive -> Constants.UI_BUTTON_HOVER
                else -> Constants.SECTOR_CUTOUT_TEXT
            }
            for (label in highlightingLabels) {
                label.colour = colour
            }

            if (price == null) {
                // For the over-capacity boxes
            } else if (draggingBlueprint != null) {
                // You only get half of what you paid for it
                val sellPrice = (draggingBlueprint.cost ?: 0) / 2
                price.text = sellPrice.toString()
            } else {
                price.text = ""
            }

            g.pushTransform()
            g.translate(pos.x.f, pos.y.f)
            container.draw(g)
            g.popTransform()
        }

        override fun click(button: Int) {
            // Clicking the sell box doesn't do anything
        }

        companion object {
            fun create(game: InGameState, type: Type, pos: IPoint, getDrag: () -> Blueprint?): SellDropBox {
                val widgetName = when (type) {
                    Type.SELL_EQUIPMENT -> "sell_drop_box"
                    Type.TOO_MUCH_EQUIPMENT -> TODO()
                    Type.TOO_MANY_CREW -> "crew_over_capacity"
                }

                val widget: WidgetContainer = game.uiLoader.load(widgetName).mainWidget

                val titleKey = when (type) {
                    Type.SELL_EQUIPMENT -> "sell_box_title"
                    Type.TOO_MUCH_EQUIPMENT -> "overcapacity_item_title"
                    Type.TOO_MANY_CREW -> "overcapacity_crew_title"
                }
                val messageKey = when (type) {
                    Type.SELL_EQUIPMENT -> "sell_box_text"
                    Type.TOO_MUCH_EQUIPMENT -> "overcapacity_item_text1"
                    Type.TOO_MANY_CREW -> "overcapacity_crew_text"
                }

                (widget.byId["title"] as Label).text = game.translator[titleKey]
                (widget.byId["message"] as Label).text = game.translator[messageKey]

                widget.updateLayout()

                return SellDropBox(game, pos, type, widget, getDrag)
            }
        }

        enum class Type {
            SELL_EQUIPMENT,
            TOO_MUCH_EQUIPMENT,
            TOO_MANY_CREW,
        }
    }
}

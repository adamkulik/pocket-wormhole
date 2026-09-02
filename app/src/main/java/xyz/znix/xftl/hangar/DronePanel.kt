package xyz.znix.xftl.hangar

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.weapons.DroneBlueprint

// TODO this is copy-pasted from WeaponPanel, deduplicate.

class DronePanel(val editor: ShipEditor) : UIObject {
    override val selectPriority: Int get() = -1

    private val slots = (0 until 3).map { DroneSlot(it) }

    val width = 365
    val height = 135

    var baseX: Int = 0 // Set by ShipEditor

    private var baseY: Int = 0

    override fun draw(g: Graphics) {
        baseY = editor.editorHeight - height - 10
        val title = editor.state.translator["equipment_frame_drones"]
        editor.state.windowRenderer.renderWithTitleTab(
            g, editor.titleTab, editor.titleFont,
            baseX, baseY,
            width, height,
            title
        )
    }

    override fun canSelectFrom(mouseX: Int, mouseY: Int): Boolean {
        return false
    }

    fun addObjects(newObjects: ArrayList<UIObject>) {
        newObjects += this
        newObjects += slots
    }

    private inner class DroneSlot(val slot: Int) : UIObject {
        override val selectPriority: Int get() = 0

        val x: Int get() = baseX + 10 + slot * 115
        val y: Int get() = baseY + 40
        val isDisabled get() = editor.ship.droneSlots <= slot

        val image = editor.state.getImg("img/upgradeUI/Equipment/box_drones_on.png")
        val imageHover = editor.state.getImg("img/upgradeUI/Equipment/box_drones_selected.png")
        val imageDisabled = editor.state.getImg("img/upgradeUI/Equipment/box_drones_off.png")

        override fun draw(g: Graphics) {
            val drone = editor.ship.drones.getOrNull(slot)?.let { editor.state.blueprints[it] as DroneBlueprint }

            val hovered = editor.isHovered(this) && !isDisabled

            val img = when {
                isDisabled -> imageDisabled
                hovered -> imageHover
                else -> image
            }

            if (drone == null) {
                img.draw(x, y)
            } else {
                BlueprintSelector.drawDroneCard(g, editor, x, y, drone, img)
            }
        }

        override fun canSelectFrom(mouseX: Int, mouseY: Int): Boolean {
            return false
        }

        override fun canHover(mouseX: Int, mouseY: Int): Boolean {
            return mouseX - x in 9..image.width && mouseY - y in 9..image.height
        }

        override fun onLeftClick(x: Int, y: Int) {
            if (!canHover(x, y) || isDisabled)
                return

            val controller = object : BlueprintSelector.SelectionController {
                override val title: String get() = "SELECT DRONE SLOT ${slot + 1}"

                override fun select(blueprint: Blueprint) {
                    // Cast the blueprint back to a DRONE
                    require(blueprint is DroneBlueprint)

                    val shipDrones = editor.ship.drones

                    if (shipDrones.size <= slot) {
                        shipDrones.add(blueprint.name)
                    } else {
                        shipDrones[slot] = blueprint.name
                    }
                }
            }

            editor.openMenu(BlueprintSelector(editor, editor.droneBlueprints, controller))
        }

        override fun onRightClick(x: Int, y: Int) {
            val clearSlot: PopupMenu.Entry? = when (slot < editor.ship.drones.size) {
                true -> PopupMenu.Entry("Clear slot", onClick = this::clearSlot)
                false -> null
            }

            val lockSlot = when (isDisabled) {
                true -> PopupMenu.Entry("Enable drone slot", onClick = this::enableSlot)
                false -> PopupMenu.Entry("Disable drone slot", onClick = this::disableSlot)
            }

            editor.openPopupMenu(
                clearSlot,
                lockSlot
            )
        }

        private fun enableSlot() {
            editor.ship.droneSlots = slot + 1
        }

        private fun disableSlot() {
            editor.ship.droneSlots = slot

            // Clear drones from locked slots
            val droneList = editor.ship.drones
            while (droneList.size > editor.ship.droneSlots) {
                droneList.remove(droneList.last())
            }
        }

        private fun clearSlot() {
            // Check the index is still valid, in case the drone was somehow
            // removed before the popup menu was closed.
            if (editor.ship.drones.indices.contains(slot))
                editor.ship.drones.removeAt(slot)
        }
    }
}

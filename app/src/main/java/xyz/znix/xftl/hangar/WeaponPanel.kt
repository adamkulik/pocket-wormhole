package xyz.znix.xftl.hangar

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint

class WeaponPanel(val editor: ShipEditor) : UIObject {
    override val selectPriority: Int get() = -1

    private val slots = (0 until 4).map { WeaponSlot(it) }

    val width = 480
    val height = 135

    var baseX: Int = 0 // Set by ShipEditor

    private var baseY: Int = 0

    override fun draw(g: Graphics) {
        baseY = editor.editorHeight - height - 10
        val title = editor.state.translator["equipment_frame_weapons"]
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

    private inner class WeaponSlot(val slot: Int) : UIObject {
        override val selectPriority: Int get() = 0

        val x: Int get() = baseX + 10 + slot * 115
        val y: Int get() = baseY + 40
        val isDisabled get() = editor.ship.weaponSlots <= slot

        val image = editor.state.getImg("img/upgradeUI/Equipment/box_weapons_on.png")
        val imageHover = editor.state.getImg("img/upgradeUI/Equipment/box_weapons_selected.png")
        val imageDisabled = editor.state.getImg("img/upgradeUI/Equipment/box_weapons_off.png")

        override fun draw(g: Graphics) {
            val weapon = editor.ship.weapons.getOrNull(slot)
                ?.let { editor.state.blueprints[it] as AbstractWeaponBlueprint }

            val hovered = editor.isHovered(this) && !isDisabled

            val img = when {
                isDisabled -> imageDisabled
                hovered -> imageHover
                else -> image
            }

            if (weapon == null) {
                img.draw(x, y)
            } else {
                BlueprintSelector.drawWeaponCard(g, editor, x, y, weapon, img)
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
                override val title: String get() = "SELECT WEAPON SLOT ${slot + 1}"

                override fun select(blueprint: Blueprint) {
                    // Cast the blueprint back to a weapon
                    require(blueprint is AbstractWeaponBlueprint)

                    val shipWeapons = editor.ship.weapons

                    if (shipWeapons.size <= slot) {
                        shipWeapons.add(blueprint.name)
                    } else {
                        shipWeapons[slot] = blueprint.name
                    }
                }
            }

            editor.openMenu(BlueprintSelector(editor, editor.weaponBlueprints, controller))
        }

        override fun onRightClick(x: Int, y: Int) {
            val clearSlot: PopupMenu.Entry? = when (slot < editor.ship.weapons.size) {
                true -> PopupMenu.Entry("Clear slot", onClick = this::clearSlot)
                false -> null
            }

            val lockSlot = when (isDisabled) {
                true -> PopupMenu.Entry("Enable weapon slot", onClick = this::enableSlot)
                false -> PopupMenu.Entry("Disable weapon slot", onClick = this::disableSlot)
            }

            editor.openPopupMenu(
                clearSlot,
                lockSlot
            )
        }

        private fun enableSlot() {
            editor.ship.weaponSlots = slot + 1
        }

        private fun disableSlot() {
            editor.ship.weaponSlots = slot

            // Clear weapons from locked slots
            val weaponList = editor.ship.weapons
            while (weaponList.size > editor.ship.weaponSlots) {
                weaponList.remove(weaponList.last())
            }
        }

        private fun clearSlot() {
            // Check the index is still valid, in case the weapon was somehow
            // removed before the popup menu was closed.
            if (editor.ship.weapons.indices.contains(slot))
                editor.ship.weapons.removeAt(slot)
        }
    }
}

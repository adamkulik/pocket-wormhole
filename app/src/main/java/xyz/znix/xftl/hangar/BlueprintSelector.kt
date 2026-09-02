package xyz.znix.xftl.hangar

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.game.UIUtils
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.ceil
import kotlin.math.roundToInt

class BlueprintSelector(
    val editor: ShipEditor,
    val blueprints: List<Blueprint>,
    val controller: SelectionController
) : EditorMenu {
    val size = ConstPoint(800, 600)
    val pos = editor.getCentralScreenPosition(size)

    private val blueprintImg = editor.state.getImg("img/upgradeUI/Equipment/box_weapons_on.png")
    private val blueprintImgSelected = editor.state.getImg("img/upgradeUI/Equipment/box_weapons_selected.png")

    private val blueprintSpacingX = blueprintImg.width + 10
    private val blueprintSpacingY = blueprintImg.height + 10
    private val numColumns = (size.x - FIRST_BP_X) / blueprintSpacingX
    private val rows = ceil(blueprints.size.f / numColumns).toInt()

    private var scroll: Float = 0f
    private val maxScroll = ((blueprintSpacingY * rows) - (size.y - FIRST_BP_Y)).coerceAtLeast(0)

    private val mousePos = Point(0, 0)
    private var hovered: Blueprint? = null

    override fun draw(g: Graphics) {
        val title = controller.title

        val startWidth = 20
        val endWidth = 38
        val textWidth = editor.titleFont.getWidth(title)
        val titleTabWidth = startWidth + textWidth + endWidth

        // Subtract out the glow
        val tabX = pos.x - 7
        val tabY = pos.y - 7

        editor.state.windowRenderer.renderMasked(pos.x, pos.y, size.x, size.y, 0, 0, {
            g.colour = Colour.red // Anything non-transparent will do
            g.fillRect(tabX.f, tabY.f, titleTabWidth.f, editor.titleTab.height.f)
        }, {
            drawBody(g)
        })

        UIUtils.drawTab(editor.titleFont, title, editor.titleTab, tabX.f, tabY.f, startWidth.f, endWidth.f)
        editor.titleFont.drawString(tabX + startWidth.f, pos.y + 24f, title, Constants.JUMP_DISABLED_TEXT)
    }

    fun drawBody(g: Graphics) {
        hovered = null

        // Draw the blueprints
        val baseX = pos.x + FIRST_BP_X
        val baseY = pos.y + FIRST_BP_Y
        var y = baseY - scroll.roundToInt()
        var column = 0
        var i = 0

        // Skip the ones we certainly can't draw.
        // Since this is all masked, it's fine to draw stuff outside
        // the window bounds, since it'll be discarded anyway.
        while (y < baseY - 100) {
            y += blueprintSpacingY
            i += numColumns
        }

        while (i < blueprints.size) {
            val bp = blueprints[i]

            if (column >= numColumns) {
                column = 0
                y += blueprintSpacingY
            }

            // Only stop drawing when we're fully off-screen.
            if (y > pos.y + size.y) {
                break
            }

            val x = baseX + blueprintSpacingX * column

            if (mousePos.x - x in 9..109 && mousePos.y - y in 9..76) {
                hovered = bp
            }

            val img = when (hovered == bp) {
                true -> blueprintImgSelected
                false -> blueprintImg
            }

            // Draw the weapon icon, if this is a weapon
            if (bp is AbstractWeaponBlueprint) {
                drawWeaponCard(g, editor, x, y, bp, img)
            } else if (bp is DroneBlueprint) {
                drawDroneCard(g, editor, x, y, bp, img)
            } else {
                img.draw(x.f, y.f)

                val title = bp.short?.let { editor.state.translator[it] } ?: bp.name
                editor.font.drawStringCentred(x + 11f, y + 70f, 96f, title, Constants.SECTOR_CUTOUT_TEXT)
            }

            i++
            column++
        }

        drawScrollBar(g)
    }

    private fun drawScrollBar(g: Graphics) {
        val x = pos.x + size.x - 20
        val height = size.y - 40

        val totalBlueprintHeight = blueprintSpacingY * rows
        val barHeightFraction = 1 - maxScroll.f / totalBlueprintHeight
        val barHeight = (height * barHeightFraction).toInt()

        val scrollAreaHeight = height - barHeight
        val scrollPosition = scroll / maxScroll
        val scrollOffset = (scrollAreaHeight * scrollPosition).toInt()

        g.colour = Constants.SECTOR_CUTOUT
        g.fillRect(x.f, pos.y + 20f, 10f, height.f)

        g.colour = Constants.SECTOR_CUTOUT_TEXT
        g.fillRect(x + 1f, pos.y + 21f + scrollOffset, 8f, barHeight - 2f)
    }

    override fun mouseWheelMoved(change: Int) {
        scroll -= change / 5f
        scroll = scroll.coerceIn(0f..maxScroll.f)
    }

    override fun mouseMoved(x: Int, y: Int) {
        mousePos.set(x, y)
    }

    override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {
        if (button == Input.MOUSE_LEFT_BUTTON) {
            hovered?.let {
                controller.select(it)
                editor.closeMenu(this)
            }
        }
    }

    companion object {
        private const val FIRST_BP_X = 20
        private const val FIRST_BP_Y = 40

        fun drawWeaponCard(
            g: Graphics, editor: ShipEditor,
            x: Int, y: Int,
            weapon: AbstractWeaponBlueprint,
            cardImage: Image
        ) {
            cardImage.draw(x, y)

            val iconWindowWidth = 96
            val iconWindowHeight = 45
            val animation = editor.state.animations.weaponAnimations[weapon.launcher]
            if (animation != null) {
                val sheet = editor.state.getImg(animation.sheet.sheetPath)
                val icon = animation.spriteAt(sheet, animation.chargedFrame) ?: editor.state.missingImage

                // The sprite is rotated 90°, so swap the width and height.
                val iconX = (iconWindowWidth - icon.height) / 2
                val iconY = (iconWindowHeight - icon.width) / 2

                weapon.drawLauncherUI(icon, g, x + iconX + 11f, y + iconY + 11f)
            }

            val title = weapon.short?.let { editor.state.translator[it] } ?: weapon.name
            editor.font.drawStringCentred(x + 11f, y + 70f, 96f, title, Constants.SECTOR_CUTOUT_TEXT)
        }

        fun drawDroneCard(
            g: Graphics, editor: ShipEditor,
            x: Int, y: Int,
            drone: DroneBlueprint,
            cardImage: Image
        ) {
            cardImage.draw(x, y)

            drone.drawIconUI(editor.state.animations, ConstPoint(x + 60, y + 35), editor.state::getImg)

            val title = drone.short?.let { editor.state.translator[it] } ?: drone.name
            editor.font.drawStringCentred(x + 11f, y + 70f, 96f, title, Constants.SECTOR_CUTOUT_TEXT)
        }
    }

    interface SelectionController {
        val title: String

        fun select(blueprint: Blueprint)
    }
}

package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.SystemInfo
import xyz.znix.xftl.Translator
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.ButtonImageSet
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.HotkeyDelayedTooltip
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.sys.Input

class Doors(blueprint: SystemBlueprint) : SubSystem(blueprint) {
    override val sortingType = SortingType.DOORS

    override fun makeExtraButtons(powerPos: IPoint): List<Button> {
        return listOf(
            DoorButton(
                powerPos,
                ConstPoint(26, -45),
                false,
                ButtonImageSet.select2(ship.sys, "img/systemUI/button_door_top")
            ),
            DoorButton(
                powerPos,
                ConstPoint(26, -22),
                true,
                ButtonImageSet.select2(ship.sys, "img/systemUI/button_door_bottom")
            )
        )
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

        val frame = game.getImg("img/systemUI/button_door_base.png")
        frame.draw(
            x + 22 - 6,
            y - 49 - 6
        )
    }

    // Nothing required, as the doors are serialised individually
    override fun saveSystem(elem: Element, refs: ObjectRefs) = Unit
    override fun loadSystem(elem: Element, refs: RefLoader) = Unit

    private inner class DoorButton(
        val powerPos: IPoint,
        offset: IPoint,
        val open: Boolean,
        val imgSet: ButtonImageSet
    ) :
        Button(ship.sys, powerPos + offset, ConstPoint(20, 20)) {

        override val disabled: Boolean get() = broken || isHackActive

        private val tooltip = HotkeyDelayedTooltip(game, if (open) "open_doors" else "close_doors")

        override fun draw(g: Graphics) {
            val img = when {
                disabled -> imgSet.off
                hovered -> imgSet.hover
                else -> imgSet.normal
            }

            // Same offsets as the base image
            img.draw(
                powerPos.x + 22 - 6,
                powerPos.y - 49 - 6
            )

            if (hovered) {
                g.tooltip = tooltip
            }
        }

        override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            if (disabled)
                return

            if (open) {
                game.shipUI.openAllDoors()
            } else {
                game.shipUI.closeAllDoors()
            }
        }
    }

    companion object {
        val INFO: SystemInfo = DoorInfo
    }
}

private object DoorInfo : SystemInfo("doors") {
    override val canBeManned: Boolean get() = true
    override val isSubSystem: Boolean get() = true

    override fun create(blueprint: SystemBlueprint) = Doors(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        return when (level) {
            0 -> translator["door_1"]
            1 -> translator["door_2"]
            2 -> translator["door_3"]
            3 -> translator["door_4"]
            else -> "INVALID LEVEL ${level + 1}"
        }
    }
}

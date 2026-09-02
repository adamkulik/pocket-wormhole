package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.*
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sys.Input

class BackupBattery(blueprint: SystemBlueprint) : SubSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.BATTERY

    var timeRemaining: Float? = null
        private set

    /**
     * The amount of power this system contributes to the ship's reactor.
     */
    val contributedPower: Int
        get() = when (timeRemaining) {
            null -> 0
            else -> undamagedEnergy * 2
        }

    // TODO draw the white power padlock around the system's power bars while it's active.

    override fun update(dt: Float) {
        super.update(dt)

        if (timeRemaining != null) {
            if (broken || isHackActive) {
                timeRemaining = 0f
            }

            val newTime = timeRemaining!! - dt
            timeRemaining = newTime.coerceAtLeast(0f)

            if (newTime <= 0f) {
                timeRemaining = null
                enterCooldown()
            }
        }
    }

    private fun enterCooldown() {
        var duration = 20f

        if (ship.hasAugment(AugmentBlueprint.BATTERY_CHARGER)) {
            duration /= 2f
        }

        ionTimer += duration

        // Update all the systems, to pull power from them as required.
        for (system in ship.systems) {
            system.powerStateChanged()
        }
    }

    fun startBattery() {
        // This is called constantly by the AI, so we have to check if we're ready.
        if (ionTimer > 0f || timeRemaining != null)
            return
        if (isHackActive)
            return

        timeRemaining = DURATION
    }

    override fun makeExtraButtons(powerPos: IPoint): List<Button> {
        return listOf(BatteryButton(powerPos))
    }

    override fun hotkeyPressed(key: Hotkey) {
        super.hotkeyPressed(key)

        if (key.id == VanillaHotkeys.SYS_ACTION_BATTERY) {
            startBattery()
        }
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrFloat(elem, "timeRemaining", timeRemaining)
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        timeRemaining = SaveUtil.getAttrFloatOrNull(elem, "timeRemaining")
    }

    private inner class BatteryButton(powerPos: IPoint) : SystemPowerButton(ship.sys, 3, powerPos) {

        // The battery base image is slightly different to properly fit the square subsystem icon.
        override val base = game.getImg("img/systemUI/button_battery3_base.png")

        override val timeRemaining: Float? get() = this@BackupBattery.timeRemaining
        override val duration: Float get() = DURATION
        override val isOff: Boolean get() = ionTimer != 0f || isHackActive

        override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            if (disabled)
                return

            startBattery()
        }
    }

    companion object {
        val INFO: SystemInfo = BatteryInfo

        const val DURATION: Float = 30f
    }
}

private object BatteryInfo : SystemInfo("battery") {
    override val canBeManned: Boolean get() = false
    override val isSubSystem: Boolean get() = true

    override fun create(blueprint: SystemBlueprint): AbstractSystem = BackupBattery(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        val power = 2 * (level + 1)
        return translator["battery_power"].replaceArg(power)
    }
}

object BatteryEnergySource : EnergySource {
    override val serialisationId: String get() = "battery"

    override fun adjustShipPower(ship: Ship, power: MutableMap<EnergySource, Int>) {
        val battery = ship.backupBattery ?: return

        when {
            battery.isHackActive -> {
                power[ReactorEnergySource] = power[ReactorEnergySource]!! - 2
            }

            else -> {
                power[BatteryEnergySource] = battery.contributedPower
            }
        }
    }

    override fun getSystemPower(system: MainSystem): Int {
        return 0
    }

    override fun drawSystemPowerBar(g: Graphics, system: AbstractSystem, x: Int, y: Int, width: Int, height: Int) {
        if (system.isHackActive || system.isIonised) {
            ReactorEnergySource.drawSystemPowerBar(g, system, x, y, width, height)
            return
        }

        drawReactorPowerBar(g, x, y, width, height)
    }

    override fun drawReactorPowerBar(g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        g.colour = Constants.SYS_ENERGY_BATTERY_OUTLINE
        g.drawRect(x, y, width - 1, height - 1)

        g.colour = Constants.SYS_ENERGY_ACTIVE
        g.fillRect(x + 2, y + 2, width - 4, height - 4)
    }
}

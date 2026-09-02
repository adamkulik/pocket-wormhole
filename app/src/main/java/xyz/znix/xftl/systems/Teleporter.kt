package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.sys.Input

class Teleporter(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.TELEPORTER
    override val insertButtonSpace: Boolean get() = true

    private val teleportSound by onInit { it.sounds.getSample("teleport") }

    private val padOffImage by onInit { it.getImg("img/ship/interior/teleporter_off.png") }
    private val padOnImage by onInit { it.getImg("img/ship/interior/teleporter_selected.png") }
    private val padActiveImage by onInit { it.getImg("img/ship/interior/teleporter_on.png") }

    private var onCooldown = false

    private var sendButton: TeleporterButton? = null
    private var recvButton: TeleporterButton? = null

    val isSendAvailable: Boolean
        get() {
            if (isPowerLocked || powerSelected == 0)
                return false

            // There must be an enemy ship
            if (ship.sys.getEnemyOf(ship) == null)
                return false

            // There must be at least one crew member standing in the room
            return room!!.crew.any { it.standingPosition != null && it.mode == AbstractCrew.SlotType.CREW && it is LivingCrew }
        }

    val isReceiveAvailable: Boolean
        get() {
            if (isPowerLocked || powerSelected == 0)
                return false

            // There must be another ship present, whether or not it's
            // currently hostile - if not, we can still teleport crew home
            // from it.
            val enemy = when (ship) {
                ship.sys.enemy -> ship.sys.player
                else -> ship.sys.enemy ?: return false
            }

            // If this is somehow run from a ship that's at a different beacon
            // (which it really shouldn't be!) then block the teleport.
            if (!ship.sys.isShipPresent(enemy))
                return false

            // There must be at least one crew member in the enemy ship
            require(ship != enemy)
            return enemy.intruders.isNotEmpty()
        }

    private var commandedTeleport: TeleportAction? = null

    override fun update(dt: Float) {
        super.update(dt)

        if (ionTimer == 0f) {
            onCooldown = false
        }

        // If there's a command ready, grab and action it
        val command = commandedTeleport ?: return

        // If the enemy ship is cloaked, stop now without removing the command.
        // Thus the command will stay there until the enemy comes out of cloak.
        if (command.room.ship.isCloakActive)
            return

        commandedTeleport = null

        if (command.send) {
            if (!isSendAvailable)
                return

            if (command.room.ship.superShield > 0)
                return

            // Crew are only teleported if they're assigned a slot on the teleporter.
            val ourCrew = room!!.crew.filter {
                it.mode == AbstractCrew.SlotType.CREW && it is LivingCrew && room!!.isCrewAssigned(it)
            }

            // Don't waste a cooldown if we're not teleporting anyone
            if (ourCrew.isEmpty())
                return

            for (crew in ourCrew) {
                crew.teleportAnimatedTo(command.room)
            }

            ship.crewAI.onCrewTeleportedOut()
        } else {
            if (!isReceiveAvailable)
                return

            if (command.room.ship.superShield > 0)
                return

            var ourCrew = command.room.crew
                .filterIsInstance<LivingCrew>()
                .filter { it.mode == AbstractCrew.SlotType.INTRUDER }

            if (!ship.isPlayerShip) {
                // Don't abduct the player's mind-controlled crew.
                ourCrew = ourCrew.filterNot { it.ownerShip?.isPlayerShip == true }
            }

            // Limit the crew we can teleport to four (at least on a two-person
            // teleporter, you can't teleport more than four crew, even if
            // they're all inside the same room by walking thorough).
            if (ourCrew.size > 4) {
                ourCrew = ourCrew.shuffled().subList(0, 4)
            }

            // Don't waste a cooldown if we're not teleporting anyone
            if (ourCrew.isEmpty())
                return

            for (crew in ourCrew) {
                crew.teleportAnimatedTo(room!!)
            }
        }

        teleportSound.play()

        // Ion-stun for 20s at 1 power, 15s at 2, and 10s at 3.
        ionTimer += cooldownTime(powerSelected).f

        // Render the teleport pads blue until the ion timer runs out.
        // This prevents an ion weapon making the pads go blue.
        onCooldown = true
    }

    override fun makeExtraButtons(powerPos: IPoint): List<Button> {
        val bottom = ButtonImageSet.select2(ship.sys, "img/systemUI/button_teleport_bottom")
        val top = ButtonImageSet.select2(ship.sys, "img/systemUI/button_teleport_top")

        val buttonBase = powerPos + ConstPoint(22, -49)

        val bgButton = TeleporterButtonBackground(buttonBase, powerPos)

        sendButton = TeleporterButton(buttonBase, ConstPoint(4, 4), true, top, bgButton.superShieldWarning)
        recvButton = TeleporterButton(buttonBase, ConstPoint(4, 27), false, bottom, bgButton.superShieldWarning)

        return listOf(
            // The background comes first so it doesn't draw on top of the others
            bgButton,

            sendButton!!,
            recvButton!!
        )
    }

    override fun drawRoom(g: Graphics) {
        if (!room!!.playerHasVision) {
            super.drawRoom(g)
            return
        }

        val padImage = when {
            powerSelected == 0 -> padOffImage
            onCooldown -> padActiveImage
            else -> padOnImage
        }

        for (x in 0 until room!!.width) {
            for (y in 0 until room!!.height) {
                padImage.drawAlignedCentred(
                    room!!.offsetX + x * Constants.ROOM_SIZE + Constants.ROOM_SIZE / 2,
                    room!!.offsetY + y * Constants.ROOM_SIZE + Constants.ROOM_SIZE / 2,
                )
            }
        }

        // Draw the icon on top
        super.drawRoom(g)
    }

    override fun hotkeyPressed(key: Hotkey) {
        super.hotkeyPressed(key)

        // Calling Button.click bit hacky, but it properly shows the super-shield warning
        if (key.id == VanillaHotkeys.SYS_ACTION_TELEPORT_SEND) {
            sendButton?.click(Input.MOUSE_LEFT_BUTTON)
        }
        if (key.id == VanillaHotkeys.SYS_ACTION_TELEPORT_RECV) {
            recvButton?.click(Input.MOUSE_LEFT_BUTTON)
        }
    }

    fun selectTeleportAction(send: Boolean, room: Room) {
        commandedTeleport = TeleportAction(room, send)
    }

    // Surprisingly, we have very little to serialise - the crew
    // handle their teleportation state themselves, and our cooldown
    // is handled via ion damage.
    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        // If the player has selected a destination to teleport their
        // crew to, remember that across saves.
        val command = commandedTeleport ?: return

        val commandElem = Element("teleportCommand")
        commandElem.setAttribute("roomId", command.room.id.toString())
        commandElem.setAttribute("roomShip", refs[command.room.ship])
        commandElem.setAttribute("isSending", command.send.toString())
        elem.addContent(commandElem)
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        val commandElem = elem.getChild("teleportCommand") ?: return

        val roomId = commandElem.getAttributeValue("roomId")!!.toInt()
        val shipRef = commandElem.getAttributeValue("roomShip")!!
        val send = commandElem.getAttributeValue("isSending")!!.toBoolean()

        refs.asyncResolve(Ship::class.java, shipRef) {
            val room = it!!.rooms[roomId]
            commandedTeleport = TeleportAction(room, send)
        }
    }

    private inner class TeleporterButton(
        val base: IPoint,
        offset: IPoint,
        val isSend: Boolean,
        val images: ButtonImageSet,
        val superShieldWarning: WarningFlasher
    ) :
        Button(ship.sys, base + offset, ConstPoint(20, 20)) {

        override val disabled: Boolean
            get() = when {
                isSend -> !isSendAvailable
                else -> !isReceiveAvailable
            }

        override fun draw(g: Graphics) {
            val img = when {
                disabled -> images.off
                hovered -> images.hover

                // If we're selecting, or have selected, a teleport of this type,
                // then keep the button highlighted.
                ship.sys.shipUI.teleportMode == isSend -> images.hover
                commandedTeleport?.send == isSend -> images.hover

                else -> images.normal
            }
            img.draw(base.x.f - BASE_GLOW, base.y.f - BASE_GLOW)
        }

        public override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            if (disabled)
                return

            var enemyShip = game.getEnemyOf(ship)

            // Allow the player to teleport crew off a non-hostile ship
            if (!isSend && ship == game.player) {
                enemyShip = game.enemy
            }

            if (enemyShip == null)
                return

            if (enemyShip.superShield > 0) {
                superShieldWarning.startFor(3.5f)
                return
            }

            // If there's already an order queued up, clear it out.
            commandedTeleport = null

            ship.sys.shipUI.teleportSelected(isSend)
        }
    }

    private inner class TeleporterButtonBackground(pos: IPoint, powerPos: IPoint) :
        Button(ship.sys, pos, ConstPoint.ZERO) {

        override val makesHoverNoise: Boolean get() = false

        // Put this here so there's only one copy of it.
        val superShieldWarning = WarningFlasher(
            game, powerPos + ConstPoint(17, -59),
            "warning_super_shield_teleporter",
            false, colour = GlowColour.WHITE
        )

        override fun draw(g: Graphics) {
            val img = ship.sys.getImg("img/systemUI/button_teleport_base.png")
            img.draw(pos.x.f - BASE_GLOW, pos.y.f - BASE_GLOW)

            superShieldWarning.draw(g)
        }

        override fun click(button: Int) = Unit
    }

    private class TeleportAction(val room: Room, val send: Boolean)

    companion object {
        private const val BASE_GLOW: Int = 6

        val INFO: SystemInfo = TeleporterInfo

        fun cooldownTime(power: Int): Int {
            return 5 * (5 - power)
        }
    }
}

private object TeleporterInfo : SystemInfo("teleporter") {
    override val canBeManned: Boolean get() = false

    override fun create(blueprint: SystemBlueprint) = Teleporter(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        val time = Teleporter.cooldownTime(level + 1)
        return translator["teleporter_on"].replaceArg(time)
    }
}

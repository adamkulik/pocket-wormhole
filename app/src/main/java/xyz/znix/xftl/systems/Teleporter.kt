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
            if (isPowerLocked || powerSupplied == 0)
                return false

            // There must be an enemy ship
            if (ship.sys.getEnemyOf(ship) == null)
                return false

            // There must be at least one crew member standing in the room
            return room!!.crew.any { it.standingPosition != null && it.mode == AbstractCrew.SlotType.CREW && it is LivingCrew }
        }

    val isReceiveAvailable: Boolean
        get() {
            if (isPowerLocked || powerSupplied == 0)
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

            if (command.room.ship.superShieldBlocksRemoteAction(ship))
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

            if (command.room.ship.superShieldBlocksRemoteAction(ship))
                return

            var ourCrew = command.room.crew
                .filterIsInstance<LivingCrew>()
                .filter { it.mode == AbstractCrew.SlotType.INTRUDER }

            if (!ship.isPlayerShip) {
                // Don't abduct the player's mind-controlled crew.
                ourCrew = ourCrew.filterNot { it.ownerShip?.isPlayerShip == true }

                // Only recall our own boarders: an intruder left behind by
                // an earlier ship would otherwise be pulled aboard, start
                // wrecking a ship that isn't theirs and end the fight early
                // (GitHub issue #104).
                ourCrew = ourCrew.filter { belongsToShip(it) }
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
        ionTimer += cooldownTime(powerSupplied).f

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
            powerSupplied == 0 -> padOffImage
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

    /**
     * The effect of an enemy hacking pulse disrupting this Teleporter
     * (GitHub issue #164; wiki Hacking, Crew Teleporter): the teleporter is
     * forced to recall its own crew from the other ship. In other words, if
     * the enemy hacks your teleporter, your boarding party is sent back
     * home - and if you hack theirs, their boarders are ejected from your
     * ship.
     *
     * Vanilla rules (wiki Hacking / Crew Teleporter):
     * - "Forcibly recalls hostile boarders, putting the system on cooldown
     *   if anyone was successfully recalled."
     * - "Does not retrieve crew from a cloaked ship."
     * - "Depowering a hacked Teleporter system will prevent sending your
     *   crew back by the enemy hacking pulse" - hence the power check here,
     *   at the start of the pulse.
     * - "Can retrieve up to 4 crew from the enemy ship."
     *
     * Mind control: crew belong to whichever ship is controlling them, so
     * enemy crew the hacker has mind-controlled are recalled to the hacker's
     * ship ("when your teleporter is hacked, it will abduct enemy crew that
     * you have mind-controlled on the enemy ship"). Crew on the teleporter's
     * own ship are never considered - they're already home.
     */
    fun hackedDisrupt(hacker: Ship) {
        // A depowered teleporter can't recall anyone, and doesn't go on
        // cooldown either (wiki).
        if (powerSupplied == 0)
            return

        // Zoltan shields and cloaking both block teleportation.
        if (hacker.isCloakActive || hacker.superShield > 0)
            return

        var ourCrew = hacker.crew
            .filterIsInstance<LivingCrew>()
            .filter { belongsToShip(it) }

        // A teleporter can only retrieve four crew at a time - the same
        // limit the normal receive path applies.
        if (ourCrew.size > 4) {
            ourCrew = ourCrew.shuffled().subList(0, 4)
        }

        // No cooldown if we didn't actually bring anyone back.
        if (ourCrew.isEmpty())
            return

        for (crew in ourCrew) {
            crew.teleportAnimatedTo(room!!)
        }

        teleportSound.play()

        // Ion-stun for 20s at 1 power, 15s at 2, and 10s at 3 - the same
        // cooldown a normal teleport applies.
        ionTimer += cooldownTime(powerSupplied).f
        onCooldown = true
    }

    /**
     * True if the given crewmember belongs to this teleporter's ship.
     *
     * Mind-controlled crew follow their controller (vanilla: the teleporter
     * abduction rule), so the owner ship is only consulted when the
     * crewmember isn't under mind control.
     */
    private fun belongsToShip(crew: LivingCrew): Boolean {
        val controller = crew.mindControlledBy
        return if (controller != null) controller == ship.mindControl else crew.ownerShip == ship
    }

    // Surprisingly, we have very little to serialise - the crew
    // handle their teleportation state themselves, and our cooldown
    // is handled via ion damage.
    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        // If the player has selected a destination to teleport their
        // crew to, remember that across saves.
        val command = commandedTeleport ?: return

        // The destination ship may no longer be part of the save (a
        // crew-killed enemy that was left behind); drop the pending order.
        if (!refs.contains(command.room.ship))
            return

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

            if (enemyShip.superShieldBlocksRemoteAction(ship)) {
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

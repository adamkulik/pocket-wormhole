package xyz.znix.xftl.game

import org.jdom2.Element
import org.newdawn.slick.geom.Rectangle
import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sector.Choice
import xyz.znix.xftl.sector.Event
import xyz.znix.xftl.sector.EventStatus
import xyz.znix.xftl.sector.EventSystemUpgrade
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class DialogueWindow private constructor(val game: InGameState, val playerShip: Ship, val close: () -> Unit) :
    Window() {

    override val size: IPoint get() = ConstPoint(602, 377)

    private val resourceNumFont = game.getFont("JustinFont10")
    private val font = game.getFont("JustinFont11Bold")

    private val augmentStringWidth = font.getWidth(game.translator["augment"])

    private val shipUnlockSound = game.sounds.getSample("unlock")

    /**
     * The currently-shown event. This is null if there's only [syntheticEvents]
     * remaining.
     */
    private var currentEvent: EvaluatedEvent? = null

    private lateinit var options: List<EvaluatedEvent>

    private val optionBoundingBoxes = ArrayList<Rectangle>()
    private var hoveredOption: Int? = null

    /**
     * The list of [SyntheticEvent]s that should be shown, one after the other.
     *
     * Adding a synthetic event will cause it to be shown in front of the
     * current event, even if that's already visible.
     */
    private val syntheticEvents = ArrayList<SyntheticEvent>()

    private val textX get() = position.x + 18

    private var extraText = ""

    private val continueEvent = EvaluatedEvent(null, game, null, 0)

    constructor(game: InGameState, playerShip: Ship, startingEvent: Event?, seed: Int, close: () -> Unit)
            : this(game, playerShip, close) {

        // Starting event can be null to get the window open before adding
        // a synthetic event.
        if (startingEvent != null) {
            loadEvent(EvaluatedEvent(startingEvent, game, null, seed))
        } else {
            // We have to set this for serialisation, and although it should
            // never be observed, it's still worth adding the continue event
            // to avoid the player getting stuck if something happens.
            options = listOf(continueEvent)
        }
    }

    private fun loadEvent(event: EvaluatedEvent) {
        extraText = ""

        game.loadEventShip(event.event)

        if (event.event.isStore) {
            // This does a few things: show the store on the map, enable the store button
            // and cause the store window to open when this window is closed.
            game.currentBeacon.hasStore = true
        }

        // If this event triggers a quest, we have to evaluate it now to find out
        // what text to display (whether it was added to the current or next
        // sector, or there's no time as the next sector is the last stand).
        val questName = event.event.questName
        if (questName != null) {
            val quest = game.eventManager[questName].resolve()
            val messageId = when (game.addQuest(quest)) {
                InGameState.QuestAddResult.CURRENT_SECTOR -> "added_quest"
                InGameState.QuestAddResult.NEXT_SECTOR -> "added_quest_sector"
                InGameState.QuestAddResult.TOO_LATE -> "no_time"
            }
            extraText += "\n\n" + game.translator[messageId]
        }

        // When ships are first unlocked, they add on an extra line.
        // We also need to play the unlock sound now, so we might as well also
        // update the user's profile while we're here.
        val unlockShip = event.event.unlockShip
        if (unlockShip != null) {
            val family = game.content.shipFamilies.families.firstOrNull { it.unlockId == unlockShip }
            checkNotNull(family) { "No ship family with unlock ID: '$unlockShip'" }
            val oldStatus = game.mainGame.profile.getShipUnlock(family)
            game.mainGame.profile.unlockShip(family, game.difficulty)

            if (oldStatus == null) {
                shipUnlockSound.play()
                extraText += "\n\n" + game.translator["unlock_ship_$unlockShip"]
            }
        }

        // Events that have no text are valid, usually they are the result of a choice
        // Eg, to give the player some items and close the menu
        if (event.text == null) {
            // Events that have text and give resources don't add the resources
            // until they're closed. That means the addResources call is made
            // when an option is selected. For these events that'll never happen,
            // so add the resources now.
            addResources(event)

            currentEvent = null

            // Stay open if a synthetic event was just created, we'll close when
            // that's done.
            checkClosed()

            return
        }

        currentEvent = event

        val newOptions = ArrayList<EvaluatedEvent>()
        for ((idx, choice) in event.event.choices.withIndex())
            newOptions.add(EvaluatedEvent(choice.event.resolve(), game, choice, event.choiceSeeds[idx]))

        // Walk backwards from the end of the options list, removing
        // anything that's not suitable. Aside from iteration order,
        // we specifically need to go backwards to keep the last suitable
        // option among many with max_group set.
        val maxGroupHit = HashSet<Int>()
        for (i in newOptions.size - 1 downTo 0) {
            val option = newOptions[i]
            val choice = option.choice!!

            // If we don't have the blue option requirements, hide the option.
            // This doesn't consider regular resources (missiles, fuel, etc) since
            // if there's not enough of those, the option is merely greyed out.
            if (!hasRequiredEquipment(option)) {
                newOptions.removeAt(i)
                continue
            }

            // If multiple options with the same max_group are set,
            // only the last one (first one we see in this iteration
            // order) is kept.
            if (choice.maxGroup == null)
                continue

            if (maxGroupHit.contains(choice.maxGroup)) {
                newOptions.removeAt(i)
                continue
            }
            maxGroupHit.add(choice.maxGroup)
        }

        options = newOptions

        if (options.isEmpty()) {
            options = listOf(continueEvent)
        }

        optionBoundingBoxes.clear()
    }

    override fun draw(g: Graphics) {
        // Draw the frame
        game.windowRenderer.render(position.x, position.y, size.x, size.y)

        if (syntheticEvents.isNotEmpty()) {
            drawSyntheticEvent(g, syntheticEvents.first())
            return
        }

        // If syntheticEvent is empty, then currentEvent must not be null.
        val event = currentEvent!!

        var textY = drawEventOutcome(g, event.text!! + extraText, event.resources)

        val rebuildBBs = optionBoundingBoxes.isEmpty()

        // You can think of each option having a top and bottom Y. For text
        // events there's a spacing between the top of the space and the baseline
        // of the text (TEXT_OPTION_TOP_OFFSET), and then another offset from
        // the baseline to the bottom of the option (which is the top of the next
        // option) - TEXT_OPTION_BOTTOM_MARGIN.
        // When resources are involved, the top of the resource box sits exactly
        // at the option's Y top, and there's 10 pixels (RESOURCE_BOTTOM_MARGIN)
        // between the bottom of the box and the start of the next option.
        for ((i, option) in options.withIndex()) {
            val choice = option.choice
            val enabled = isConditionsSatisfied(option)
            val colour = when {
                !enabled -> Constants.TEXT_OPTION_DISABLED
                hoveredOption == i -> Constants.TEXT_OPTION_HOVER
                choice == null -> Colour.white
                choice.blue -> Constants.TEXT_OPTION_BLUE
                else -> Colour.white
            }

            val prefix = "${i + 1}. "
            var text = prefix + option.choiceText

            // Handle the 'continue' option, since it fails if
            // we try to read its resources.
            if (option.isContinue) {
                drawText(textY, text, colour, rebuildBBs)
                continue
            }

            // Warn the user if they'll have to fire a crewmember
            if (option.resources.crew.isNotEmpty() && game.isPlayerCrewFull) {
                text += " " + game.translator["event_crew_full"]
            }

            // Draw the resources for this event, if appropriate
            if (option.choiceResources.hasAnything) {
                val boxSize = findResourceBoxSize(option.choiceResources)

                val textResourceMargin = 10

                // To support text wrapping, we do this in three parts:
                // 1. Figure out the maximum width of the text
                // 2. Draw the text, which tells us how wide it is
                // 3. Draw the resource box to line up with the text, or
                //    at it's maximum x position if the text is multi-line.
                // This can be tested with the ENGI_REFUGEES or
                // ZOLTAN_TRADE_HUB events (the latter with a blue option).
                // (note ENGI_REFUGEES isn't part of the final game, so
                //  you can't test it there).
                // This logic might not exactly match the base game for
                // other events, if so it should be fixed but it's not
                // likely to be a big problem.

                // Find the maximum box position
                val maxBoxRHS = position.x + size.x - 34
                val maxBoxX = maxBoxRHS - boxSize.x
                val maxTextX = maxBoxX - textResourceMargin

                // Align the text with the middle of the box
                val textTop = textY + (boxSize.y - FONT_HEIGHT) / 2
                val lineY = textTop + FONT_HEIGHT
                val textEndY = drawText(lineY, text, colour, rebuildBBs, maxTextX)

                if (textEndY == lineY) {
                    // The event is only one line long, draw the resource
                    // box after the text.
                    val boxX = textX + font.getWidth(text) + textResourceMargin
                    val boxPos = ConstPoint(boxX, textY)
                    drawResourceBox(g, option.choiceResources, boxPos, boxSize, colour)
                } else {
                    // The text was multiple lines long, draw the resource box
                    // on the right-mode side and centred on the text.
                    // This does leave a notably larger spacing between the line
                    // above as space was made for the box, but that's there in
                    // vanilla FTL too (though slightly shorter - though it's
                    // close enough, and I've had enough of fiddling with
                    // layouts to care about this particular inconsistency).
                    val firstLineTop = lineY - FONT_HEIGHT
                    val textHeight = textEndY - firstLineTop
                    val centreY = firstLineTop + textHeight / 2 - boxSize.y / 2
                    val boxPos = ConstPoint(maxBoxX, centreY)
                    drawResourceBox(g, option.choiceResources, boxPos, boxSize, colour)
                }

                textY = max(textEndY + TEXT_OPTION_BOTTOM_MARGIN, boxSize.y + RESOURCE_BOTTOM_MARGIN)
            } else {
                textY += TEXT_OPTION_TOP_OFFSET
                textY = drawText(textY, text, colour, rebuildBBs)
                textY += TEXT_OPTION_BOTTOM_MARGIN
            }
        }
    }

    private fun drawEventOutcome(g: Graphics, text: String, resourcesGained: ResourceSet): Int {
        var textY = position.y + 35
        textY = drawText(textY, text)

        if (resourcesGained.hasAnything) {
            // The box is centred 43 pixels below the baseline of the last line of
            // the event text.
            val boxMiddleY = textY + 43

            val boxSize = findResourceBoxSize(resourcesGained)
            val boxX = position.x + (size.x - boxSize.x) / 2
            val boxY = boxMiddleY - boxSize.y / 2
            val boxPos = ConstPoint(boxX, boxY)
            drawResourceBox(g, resourcesGained, boxPos, boxSize, Colour.white)

            // The spacing between the event text and the options is constant, regardless
            // of the size of the rewards.
            textY += 95
        } else {
            textY += 45
        }

        // At this point, textY is where the choices should go.

        return textY
    }

    private fun drawSyntheticEvent(g: Graphics, event: SyntheticEvent) {
        val textY = drawEventOutcome(g, event.text, event.resources)

        val rebuildBBs = optionBoundingBoxes.isEmpty()

        // Draw the continue option
        val colour = when (hoveredOption) {
            0 -> Constants.TEXT_OPTION_HOVER
            else -> Colour.white
        }

        val text = "1. " + game.translator["continue"]
        drawText(textY, text, colour, rebuildBBs)
    }

    private fun findResourceBoxSize(resourceSet: ResourceSet): IPoint {
        var width = RESOURCE_LEFT_START

        // Add up the width used by each of fuel/scrap/drones/missiles
        for ((_, amount) in resourceSet.entries) {
            // For the resource icon and it's padding to the number
            width += RESOURCE_ICON_SPACING

            width += resourceNumFont.getWidth(amount.toString())

            // The padding until the next number, or the end of the box
            width += RESOURCE_RIGHT_SPACING
        }

        var height = 0

        if (resourceSet.isNotEmpty())
            height += 32

        for (bp in resourceSet.items) {
            val bpWidth: Int = when (bp) {
                is AbstractWeaponBlueprint -> {
                    val nameWidth = resourceNumFont.getWidth(game.translator[bp.title!!])
                    val img = bp.getLauncher(game).getChargedImage(game)
                    height += img.width + 9
                    10 + img.height + 10 + nameWidth + 16
                }

                is DroneBlueprint -> {
                    val nameWidth = resourceNumFont.getWidth(game.translator[bp.title!!])

                    // Not sure if +9 is appropriate, or iconSize should be bigger.
                    height += bp.iconSize.y + 9

                    10 + bp.iconSize.x + 8 + nameWidth + 12 // Approximate numbers
                }

                is AugmentBlueprint -> {
                    height += 32

                    val nameWidth = resourceNumFont.getWidth(bp.translateTitle(game))
                    10 + augmentStringWidth + 8 + nameWidth + 11
                }

                else -> error("Can't draw non-weapon/drone/augment blueprint: $bp")
            }

            width = max(width, bpWidth)
        }

        for (crew in resourceSet.crew) {
            // There's 30 pixels for the crew member to fit on the left of their name
            val crewWidth = 30 + resourceNumFont.getWidth(crew.name) + 14
            width = max(width, crewWidth)

            // Always 32 pixels high
            height += 32
        }

        for (crew in resourceSet.lostCrew) {
            val messageKey = if (crew.info.turnHostile) "traitor_crew" else "dead_crew"
            val message = game.translator[messageKey].replaceArg(crew.crew.info.name)

            // There's 30 pixels for the crew member to fit on the left
            // of the 'so-and-so is gone' message.
            val crewWidth = 30 + resourceNumFont.getWidth(message) + 14
            width = max(width, crewWidth)

            // Always 32 pixels high
            height += 32
        }

        if (resourceSet.intruders.isNotEmpty()) {
            val message = game.translator["intruder_alert"]
            width = max(width, 32 + resourceNumFont.getWidth(message) + 30)
            height += 32
        }

        if (resourceSet.damage.isNotEmpty()) {
            val (message, _) = getHullDamageText(resourceSet)
            if (message != null) {
                width = max(width, 30 + resourceNumFont.getWidth(message) + 30)
                height += 32
            }
        }

        for (upgrade in resourceSet.upgrades) {
            val (message, _) = getUpgradeText(upgrade)
            width = max(width, 25 + resourceNumFont.getWidth(message) + 30)
            height += 32
        }

        if (resourceSet.modifyPursuit != 0) {
            val (message, _) = getFleetPursuitText(resourceSet.modifyPursuit)
            width = max(width, 24 + resourceNumFont.getWidth(message) + 31)
            height += 32
        }

        return ConstPoint(width, height)
    }

    private fun drawResourceBox(g: Graphics, resourceSet: ResourceSet, pos: IPoint, size: IPoint, textColour: Colour) {
        var y = pos.y

        g.colour = Constants.REWARDS_BACKGROUND
        g.fillRect(pos.x.f, y.f, size.x.f, size.y.f)

        g.colour = textColour
        g.drawRect(pos.x.f, y.f, size.x - 1f, size.y - 1f)
        g.drawRect(pos.x.f + 1, y + 1f, size.x - 3f, size.y - 3f)

        var resourceX = pos.x + RESOURCE_LEFT_START

        for (pair in resourceSet.toList().sortedBy { it.first.ordinal }) {
            // Draw the icon
            val colour = if (pair.second > 0) Constants.REWARDS_ICONS else Constants.REWARDS_NEGATIVE_ICONS
            pair.first.getIcon(game).draw(resourceX.f, y.f, colour)
            resourceX += RESOURCE_ICON_SPACING

            // Draw the text
            val numText = pair.second.toString()
            resourceNumFont.drawString(resourceX.f, y + 21f, numText, textColour)
            resourceX += resourceNumFont.getWidth(numText)

            // Add some padding before the next resource
            resourceX += RESOURCE_RIGHT_SPACING
        }

        // If we drew some resources, move the blueprints down so they don't overlap
        if (resourceSet.isNotEmpty()) {
            y += 32
        }

        for (crew in resourceSet.crew) {
            y += drawRewardCrew(crew, pos.x, y, textColour)
        }

        for (crew in resourceSet.lostCrew) {
            y += drawLostCrew(crew, pos.x, y)
        }

        for (bp in resourceSet.items) {
            y += drawRewardBlueprint(g, bp, pos.x, y, textColour)
        }

        if (resourceSet.intruders.isNotEmpty()) {
            val message = game.translator["intruder_alert"]
            resourceNumFont.drawString(pos.x + 32f, y + 21f, message, Constants.SYS_ENERGY_BROKEN)
            y += 32
        }

        if (resourceSet.damage.isNotEmpty()) {
            val (message, colour) = getHullDamageText(resourceSet)
            if (message != null) {
                resourceNumFont.drawString(pos.x + 30f, y + 21f, message, colour)
                y += 32
            }
        }

        for (upgrade in resourceSet.upgrades) {
            val (message, colour) = getUpgradeText(upgrade)
            resourceNumFont.drawString(pos.x + 25f, y + 21f, message, colour)
            y += 32
        }

        if (resourceSet.modifyPursuit != 0) {
            val (message, colour) = getFleetPursuitText(resourceSet.modifyPursuit)
            resourceNumFont.drawString(pos.x + 24f, y + 21f, message, colour)
            y += 32
        }
    }

    private fun drawText(
        y: Int, msg: String, colour: Colour = Colour.white,
        addOption: Boolean = false, maxX: Int = -1
    ): Int {
        // The x coordinate where line wrapping is triggered
        val textFarX = if (maxX != -1) maxX.f else position.x + size.x - 25f
        val maxWidth = textFarX - textX

        var textY = y
        var longestLine = 0

        for (line in font.wrapString(msg, maxWidth.toInt())) {
            longestLine = longestLine.coerceAtLeast(font.getWidth(line))
            font.drawString(textX.f, textY.f, line, colour)
            textY += 20
        }

        // Remove the space used by the last line.
        textY -= 20

        if (addOption) {
            optionBoundingBoxes += Rectangle(textX.f, y.f - 11, longestLine.f, textY - y + 14f)
        }

        return textY
    }

    private fun drawRewardBlueprint(g: Graphics, bp: Blueprint, boxX: Int, textY: Int, textColour: Colour): Int {
        when (bp) {
            is AbstractWeaponBlueprint -> {
                val anim = bp.getLauncher(game)

                bp.drawLauncherUI(game, g, boxX + 10f, textY + 2f)

                // Draw the name
                val name = game.translator[bp.title!!]
                val chargedImage = anim.getChargedImage(game)
                resourceNumFont.drawString(boxX + chargedImage.height + 20f, textY + 22f, name, textColour)

                return 42
            }

            is DroneBlueprint -> {
                // Draw the drone icon - the Y here isn't right, but it's good enough for now. TODO do it properly.
                val height = bp.iconSize.y + 9
                val imgCentreY = textY + height / 2
                bp.drawIconUI(game, ConstPoint(boxX + 21, imgCentreY))

                // Draw the name
                val name = game.translator[bp.title!!]
                val textX = boxX + 10 + bp.iconSize.x + 8
                resourceNumFont.drawString(textX.f, imgCentreY.f + FONT_HEIGHT / 2, name, textColour)

                return 20
            }

            is AugmentBlueprint -> {
                val nameX = boxX + 10 + augmentStringWidth + 8
                val nameY = textY + 21f

                font.drawString(boxX + 10f, nameY, game.translator["augment"], textColour)
                resourceNumFont.drawString(nameX.f, nameY, bp.translateTitle(game), textColour)

                return 32
            }

            else -> error("Can't draw non-weapon/drone/augment blueprint: $bp")
        }
    }

    private fun drawRewardCrew(crew: LivingCrewInfo, x: Int, y: Int, textColour: Colour): Int {
        crew.drawPortrait(x - 2, y - 2)

        resourceNumFont.drawString(x + 30f, y + 21f, crew.name, textColour)

        return 32
    }

    private fun drawLostCrew(crew: RemoveCrewEval, x: Int, y: Int): Int {
        crew.crew.drawPortrait(x - 2, y - 2, false)

        val messageKey = if (crew.info.turnHostile) "traitor_crew" else "dead_crew"
        val message = game.translator[messageKey].replaceArg(crew.crew.info.name)
        resourceNumFont.drawString(x + 30f, y + 21f, message, Constants.SYS_ENERGY_BROKEN)

        return 32
    }

    /**
     * Checks if we've got all the stuff required for an event (both
     * consumables and scrap if they're subtracted by the event, and
     * also equipment/crew used for blue options).
     */
    private fun isConditionsSatisfied(event: EvaluatedEvent): Boolean {
        if (event.isContinue)
            return true

        val res = event.resources

        // Check we have enough stuff, if any of the resources are negative
        if (playerShip.scrap < -res.scrap)
            return false
        if (playerShip.missilesCount < -res.missiles)
            return false
        if (playerShip.dronesCount < -res.droneParts)
            return false
        if (playerShip.fuelCount < -res.fuel)
            return false

        return hasRequiredEquipment(event)
    }

    /**
     * Checks if the player has all the stuff requiredd for an event,
     * excluding resources.
     *
     * This is distinct from [isConditionsSatisfied], since the option is hidden
     * if the user lacks any of the required equipment.
     */
    private fun hasRequiredEquipment(event: EvaluatedEvent): Boolean {
        if (event.isContinue)
            return true

        // Check for the option requirement, which is usually used
        // for blue-option requirements.
        return event.choice?.let { checkReq(it) } ?: true
    }

    private fun checkReq(choice: Choice): Boolean {
        val req = choice.req ?: return true

        // Req is the requirement name of an event choice. Per Choice.req, it is:
        // "any race, weapon, drone, augmentation or system/subsystem"

        // Check for matching races
        if (playerShip.crew.any { it.codename == req }) {
            return true
        }

        // Check for matching weapons, drones and augments. We search through all
        // the places a blueprint could be, but none of these checks are specific
        // to that type of item. For example, we could find a weapon in the cargo
        // hold just the same as if it's in the weapons slots.
        fun hasBlueprint(name: String): Boolean {
            if (playerShip.hardpoints.any { it.weapon?.type?.name == name }) {
                return true
            }
            if (playerShip.drones?.drones?.any { it?.type?.type?.name == name } == true) {
                return true
            }
            if (playerShip.augmentValues.any { it.key.name == name }) {
                return true
            }
            return playerShip.cargoBlueprints.any { it?.name == name }
        }
        if (hasBlueprint(req))
            return true

        // Req can be a blueprint list, in which case match anything from that.
        // For example, DISTRESS_SATELLITE_DEFENSE's ion weapon option.
        val bpList = game.blueprintManager.blueprints[req] as? BlueprintList
        if (bpList != null) {
            for (bp in bpList.list()) {
                if (hasBlueprint(bp.name))
                    return true
            }
        }

        // Check for any matching systems
        val matchingSystem = playerShip.systems.firstOrNull { it.codename == req }
        if (matchingSystem != null) {
            // Check if we're outside the level bounds of this system
            choice.minLevel?.let { minLevel ->
                if (matchingSystem.energyLevels < minLevel)
                    return false
            }
            choice.maxLevel?.let { maxLevel ->
                if (maxLevel < matchingSystem.energyLevels)
                    return false
            }

            // Note that choice.maxGroup is processed during event setup.

            return true
        }

        // If the system is 'reactor', we just have to check it's
        // between the minimum and maximum level.
        if (req == "reactor") {
            choice.minLevel?.let { minLevel ->
                if (playerShip.purchasedReactorPower < minLevel)
                    return false
            }
            choice.maxLevel?.let { maxLevel ->
                if (maxLevel < playerShip.purchasedReactorPower)
                    return false
            }
            return true
        }

        return false
    }

    override fun updateUI(x: Int, y: Int) {
        super.updateUI(x, y)

        hoveredOption = null
        for ((i, bb) in optionBoundingBoxes.withIndex()) {
            if (bb.contains(x.f, y.f)) {
                hoveredOption = i
            }
        }
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON) return

        val idx = hoveredOption ?: return

        selectOption(idx)
    }

    override fun escapePressed() {
        game.shipUI.showPauseWindow()
    }

    fun selectOption(idx: Int) {
        // Pressing continue on a synthetic event closes it
        val syntheticEvent = syntheticEvents.firstOrNull()
        if (syntheticEvent != null) {
            if (idx == 0) {
                syntheticEvents.removeAt(0)

                game.givePlayerResources(syntheticEvent.resources)

                // Re-calculate the bounding boxes now the event is visible again
                optionBoundingBoxes.clear()

                // Check if we're out of stuff to show and have to close
                checkClosed()
            }

            return
        }

        // If syntheticEvent is empty, then currentEvent must not be null.
        val event = currentEvent!!

        if (idx < 0 || idx >= options.size)
            return

        val choice = options[idx]

        // Block the selection of disabled options
        if (!isConditionsSatisfied(choice))
            return

        // Add any resources the currently-visible event dropped.
        addResources(event)

        if (choice.isContinue) {
            currentEvent = null
            checkClosed()
            return
        }

        loadEvent(choice)
    }

    fun addSyntheticEvent(syntheticEvent: SyntheticEvent) {
        syntheticEvents.add(syntheticEvent)

        // Re-calculate the bounding boxes to only show the continue option.
        optionBoundingBoxes.clear()
    }

    private fun addResources(event: EvaluatedEvent) {
        game.givePlayerResources(event.resources)

        // Remove blueprints
        removeStuff@ for (name in event.event.removedStuff) {
            val blueprint = game.blueprintManager[name]
            if (blueprint is BlueprintList) {
                // Try and remove one item from the list.
                // This isn't used in vanilla, but seems like a logical behaviour
                // that mods will probably use.
                for (item in blueprint.list().shuffled()) {
                    if (playerShip.removeBlueprint(item))
                        continue@removeStuff
                }
            } else {
                // Cast is safe, as IBlueprint is always either
                // Blueprint or BlueprintList.
                playerShip.removeBlueprint(blueprint as Blueprint)
            }
        }

        // Apply the status effects
        effectLoop@ for (effect in event.event.statuses) {
            val ship = when (effect.target) {
                EventStatus.Target.PLAYER -> playerShip
                EventStatus.Target.ENEMY -> game.enemy
                    ?: error("Cannot add enemy status in event '${event.event.debugId}' where no enemy is present!")
            }

            val beacon = game.currentBeacon

            // Find the system this effect applies to
            val system = ship.systems.firstOrNull { it.codename == effect.system }
            if (system == null) {
                println("Warning: event '${event.event.debugId}' is applying a status effect to system '${effect.system}', which ship ${effect.target} doesn't have!")
                continue
            }

            // Note: we currently don't apply effects cumulatively. I don't know
            // if vanilla FTL does, but it seems quite unlikely that it'd matter.

            val maxPower: Int? = when (effect.op) {
                EventStatus.Operation.CLEAR -> {
                    // Clear removes all effects from a given system
                    null
                }

                EventStatus.Operation.DIVIDE -> system.energyLevels / effect.amount
                EventStatus.Operation.LIMIT -> effect.amount
                EventStatus.Operation.LOSS -> system.energyLevels - effect.amount
            }

            if (effect.target == EventStatus.Target.PLAYER) {
                if (maxPower == null) {
                    beacon.powerLimitEffects.remove(system.codename)
                } else {
                    beacon.powerLimitEffects[system.codename] = maxPower.coerceIn(0..system.energyLevels)
                }

                // It's a bit unnecessary to call this multiple times if multiple
                // effects are applied, but it's not a notable waste of resources.
                ship.updateScriptedPowerLimits()
            } else {
                system.scriptedPowerLimit = maxPower
            }
        }

        if (event.event.revealMap) {
            game.currentBeacon.sector.mapRevealed = true
        }
    }

    private fun getHullDamageText(resourceSet: ResourceSet): Pair<String?, Colour> {
        val baseDamage = resourceSet.damage.sumBy { it.amount }

        if (baseDamage < 0) {
            // Limit the displayed health gain if some will be wasted
            // because the player will have full health.
            val realHealing = min(playerShip.maxHealth - playerShip.health, -baseDamage)

            if (realHealing == 0) {
                return Pair(null, Constants.SYS_ENERGY_BROKEN)
            }

            val message = game.translator["heal_alert"].replaceArg(realHealing)
            return Pair(message, Constants.SYS_ENERGY_ACTIVE)
        }

        val realDamage = min(playerShip.health, baseDamage)
        val message = game.translator["damage_alert"].replaceArg(realDamage)
        return Pair(message, Constants.SYS_ENERGY_BROKEN)
    }

    private fun getUpgradeText(upgrade: EventSystemUpgrade): Pair<String, Colour> {
        val isReactor = upgrade.system == "reactor"
        val system = playerShip.systems.firstOrNull { it.codename == upgrade.system }
        val systemName = game.translator[upgrade.system]

        if (system == null && !isReactor) {
            val message = game.translator["upgrade_fail_missing"].replaceArg(systemName)
            return Pair(message, Colour.white)
        }

        val isMaxed = when {
            isReactor -> playerShip.purchasedReactorPower >= playerShip.maxReactorPower
            else -> system!!.energyLevels >= system.blueprint.maxPower
        }

        if (isMaxed) {
            val message = game.translator["upgrade_fail_max"].replaceArg(systemName)
            return Pair(message, Colour.white)
        }

        val message = game.translator["upgrade_success"]
            .replaceArg(systemName).replaceArg(upgrade.amount, 2)
        return Pair(message, Constants.SYS_ENERGY_ACTIVE)
    }

    private fun getFleetPursuitText(amount: Int): Pair<String, Colour> {
        when {
            amount == 1 -> {
                val message = game.translator["fleet_speed_1"]
                return Pair(message, Constants.SYS_ENERGY_BROKEN)
            }

            amount > 1 -> {
                val message = game.translator["fleet_speed"].replaceArg(amount)
                return Pair(message, Constants.SYS_ENERGY_BROKEN)
            }

            amount == -1 -> {
                val message = game.translator["fleet_delayed_1"]
                return Pair(message, Constants.SYS_ENERGY_BROKEN)
            }

            else -> {
                val message = game.translator["fleet_delayed"].replaceArg(-amount)
                return Pair(message, Constants.SYS_ENERGY_BROKEN)
            }
        }
    }

    private fun checkClosed() {
        if (syntheticEvents.isNotEmpty() || currentEvent != null)
            return

        close()
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        currentEvent?.let { event ->
            val currentEventElem = Element("currentEvent")
            saveEEToXML(event, currentEventElem, refs)
            elem.addContent(currentEventElem)
        }

        for (ev in syntheticEvents) {
            val syntheticElem = Element("syntheticEvent")
            SaveUtil.addAttr(syntheticElem, "text", ev.text)
            elem.addContent(syntheticElem)

            val resourcesElem = Element("resources")
            ev.resources.saveToXML(resourcesElem, refs)
            syntheticElem.addContent(resourcesElem)
        }

        for (option in options) {
            val optionElem = Element("option")
            saveEEToXML(option, optionElem, refs)
            elem.addContent(optionElem)
        }
    }

    /**
     * Deserialise an event window from XML
     */
    constructor(game: InGameState, playerShip: Ship, elem: Element, refs: RefLoader, close: () -> Unit)
            : this(game, playerShip, close) {

        val currentEventElem = elem.getChild("currentEvent")
        if (currentEventElem != null) {
            currentEvent = loadEEFromXML(currentEventElem, refs)
        }

        for (syntheticElem in elem.getChildren("syntheticEvent")) {
            val text = SaveUtil.getAttr(syntheticElem, "text")
            val event = SyntheticEvent(text)

            val resourcesElem = syntheticElem.getChild("resources")
            event.resources += ResourceSet(resourcesElem, refs, game)

            syntheticEvents += event
        }

        options = ArrayList()
        for (optionElem in elem.getChildren("option")) {
            (options as ArrayList).add(loadEEFromXML(optionElem, refs))
        }
    }

    private fun saveEEToXML(event: EvaluatedEvent, elem: Element, refs: ObjectRefs) {
        if (event.isContinue) {
            elem.setAttribute("continue", "true")
            return
        }

        SaveUtil.addAttr(elem, "eventId", event.event.deserialisationId)
        SaveUtil.addAttr(elem, "choiceId", event.choice?.deserialisationId ?: "")
        SaveUtil.addAttrInt(elem, "seed", event.seed)
    }

    private fun loadEEFromXML(elem: Element, refs: RefLoader): EvaluatedEvent {
        if (elem.getAttributeValue("continue") == "true") {
            return continueEvent
        }

        val eventId = SaveUtil.getAttr(elem, "eventId")
        val event = game.eventManager.getByDeserialisationId(eventId)
        var choiceId: String? = SaveUtil.getAttr(elem, "choiceId")
        if (choiceId == "") {
            choiceId = null
        }
        val choice = choiceId?.let { game.eventManager.getChoiceByDeserialisationId(it) }

        val seed = SaveUtil.getAttrInt(elem, "seed")

        return EvaluatedEvent(event, game, choice, seed)
    }

    /**
     * Represents an event that doesn't appear in XML, but is inserted by
     * code when something happens. This includes breaking down duplicate
     * augments, and the crew clone success/failure messages.
     */
    class SyntheticEvent(val text: String) {
        val resources = ResourceSet()
    }

    /**
     * Represents an event with all the random stuff resolved, such as the title, text and choice text (if applicable)
     */
    private class EvaluatedEvent(
        private val eventInt: Event?,
        game: InGameState,
        val choice: Choice?,
        val seed: Int
    ) {
        // The order this is used in must be deterministic, so only
        // the resources can lazy-use this.
        private val rand = Random(seed)

        val isContinue: Boolean = eventInt == null
        val event: Event get() = eventInt ?: throw Exception("Continue does not have an event")
        val resources by lazy { event.resolveResources(game, rand) }
        val text: String? = eventInt?.text?.resolve(rand)

        /**
         * If this event is being displayed as a choice inside another event,
         * this is the text it's option uses.
         */
        val choiceText: String = choice?.text?.resolve(rand) ?: game.translator["continue"]

        /**
         * The seeds used to initialise [EvaluatedEvent]s for each of
         * the choices contained within this event.
         *
         * Note that unlike [choiceText], [choiceResources] and [choice], this
         * field refers to the choices nested inside this event - NOT stuff
         * related to when this event is being used as a choice inside another
         * event.
         */
        val choiceSeeds: List<Int> = when {
            isContinue -> emptyList()
            else -> (0 until event.choices.size).map { rand.nextInt() }
        }

        /**
         * The resources that should be displayed in a choice. This filters out
         * crew losses, hidden items, and anything else that shouldn't show up.
         */
        val choiceResources: ResourceSet by lazy {
            val result = ResourceSet()

            // If hidden is set, don't show any resources.
            if (choice?.hidden == true)
                return@lazy result

            result += resources
            result.lostCrew.clear()
            result.damage.clear()
            result.upgrades.clear()
            result.modifyPursuit = 0

            if (event.itemsModifySteal) {
                result.scrap = 0
                result.fuel = 0
                result.droneParts = 0
                result.missiles = 0
            }

            return@lazy result
        }
    }

    companion object {
        private const val FONT_HEIGHT = 11

        // Text-only options (no resources) are spaced 32 pixels apart
        private const val TEXT_OPTION_SPACING = 32

        // The pixels between the top of an option and where it's text is placed
        private const val TEXT_OPTION_TOP_OFFSET = 15
        private const val TEXT_OPTION_BOTTOM_MARGIN = TEXT_OPTION_SPACING - TEXT_OPTION_TOP_OFFSET

        private const val RESOURCE_BOTTOM_MARGIN = 10

        private const val RESOURCE_LEFT_START = 5
        private const val RESOURCE_ICON_SPACING = 30
        private const val RESOURCE_RIGHT_SPACING = 9
    }
}

package xyz.znix.xftl.game

import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sys.Input
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// Note that the actual window appears at 340, if we want to be resizable we'll have to fix
// that (and the height). Currently we run much smaller than FTL so their size doesn't fit
// for us atm.
class JumpWindow(val game: InGameState, showSectorMap: () -> Unit, val jump: (Beacon?) -> Unit) : Window() {
    override val size = ConstPoint(752, 534)

    private val sectorInfoTab = game.getImg("img/map/side_sector.png")
    private val titleTab = game.getImg("img/map/side_beaconmap.png")
    private val nextSectorTab = game.getImg("img/map/side_nextsector.png")
    private val font = game.getFont("HL2", 3f)
    private val cancelButtonOutline = game.getImg("img/main_menus/button_cancel_base.png")
    private val sectorInfoFont = game.getFont("c&cnew", 2f)
    private val beaconLabelFont = game.getFont("HL1")
    private val distressBeaconFont = game.getFont("HL1", 2f) // Out-of-fuel UI

    private val outlineImage = game.getImg("img/window_outline.png")

    private val lineImg = game.getImg("img/map/dotted_line.png")
    private val targetBox = game.getImg("img/map/map_targetbox.png")

    private val fleetAdvanceImg = game.getImg("img/map/map_warningcircle_point.png")
    private val fleetAdvanceColour = Colour(246, 128, 125, 21)
    private val fleetControlImg = game.getImg("img/map/map_warningcircle.png")
    private val fleetControlTile = game.getImg("img/map/map_warningcircle_tile.png")

    private val beaconShadow = game.getImg("img/map/map_icon_diamond_shadow.png")
    private val beaconEnvironmentHazard = game.getImg("img/map/map_icon_hazard.png")
    private val beaconYellow = game.getImg("img/map/map_icon_diamond_yellow.png")
    private val beaconBlue = game.getImg("img/map/map_icon_diamond_blue.png")
    private val beaconDanger = game.getImg("img/map/map_icon_triangle_red.png")
    private val beaconShipPresent = game.getImg("img/map/map_icon_triangle_yellow.png")
    private val beaconWillOvertakeCircle = game.getImg("img/tutorial/player_circle.png") // This is the right image!
    private val beaconOvertaken = game.getImg("img/map/map_icon_warning.png")

    private val playerShip = game.getImg("img/map/map_icon_ship.png")
    private val playerShipNoFuel = game.getImg("img/map/map_icon_ship_fuel.png")

    private val noFuelImage = game.getImg("img/map/map_fuel_text_nofuel.png")
    private val waitDistressFrame = game.getImg("img/map/side_wait_distress.png")
    private val fuelDistressFlashLight = game.getImg("img/map/side_distressbeacon_redlight.png")

    private val beaconOffset = Point(-beaconYellow.width / 2, -beaconYellow.height / 2)

    // The offsets to the coordinate system the beacons are positioned on
    private val mapBase = Point(0, 0)

    private val sector = game.currentBeacon.sector

    private val flashTimerBase = System.nanoTime()

    // The set of all the beacons we've visited a neighbour of - this
    // is used to figure out which beacons to display information about.
    private val neighbourVisSet = sector.beacons.filter { it.visited }.flatMap { it.neighbours }.toSet()

    private val labelWhite = (1..3).map { game.getImg("img/map/map_box_white_$it.png") }
    private val labelGreen = (1..3).map { game.getImg("img/map/map_box_green_$it.png") }
    private val labelPurple = (1..3).map { game.getImg("img/map/map_box_purple_$it.png") }

    val cancelButton: Button
    val nextSectorButton: Button
    val noFuelButtons: List<Button>

    val background = game.getImg("img/map/zone_1.png")

    /**
     * True if the player's out-of-fuel distress beacon is enabled.
     */
    var fuelDistressOn = false

    private var fuelDistressFlashTimer: Float = 0f
    private val fuelDistressFlashOn: Boolean get() = fuelDistressOn && fuelDistressFlashTimer < 0.5f

    var hovered: Beacon? = null

    private var playerRotation: Float = (0f..10f).random(VisualRandom)

    private val outOfFuel: Boolean get() = game.player.fuelCount == 0

    init {
        // The buttons are fiddly to set up, as they move depending on
        // the length of the text inside them.

        // Cancel button
        val cancelText = game.translator["button_cancel"]
        val cancelTextWidth = font.getWidth(cancelText)
        val cancelButtonWidth = cancelTextWidth + 3 * 2
        cancelButton = Buttons.BasicButton(
            game, size + ConstPoint(-31 - cancelButtonWidth, 7),
            ConstPoint(cancelButtonWidth, 30), cancelText,
            3, font, 24,
            ::cancelClicked
        )
        buttons += cancelButton

        // Next sector button
        val nsText = game.translator["button_nextsector"]
        val nsTextWidth = font.getWidth(nsText)
        val nsButtonWidth = nsTextWidth + 4 * 2
        nextSectorButton = Buttons.BasicButton(
            game, ConstPoint(size.x - 12 - nsButtonWidth, 12),
            ConstPoint(nsButtonWidth, 36), nsText,
            3, font, 27, showSectorMap
        )

        // Upstream 9037aed accidentally gated the clickable button on being
        // out of fuel (regression vs vanilla: at an exit beacon with fuel the
        // button was drawn but dead, making sector exit impossible). Drawn
        // whenever we're at an exit; jumping itself costs fuel elsewhere.
        if (game.currentBeacon.isExit)
            buttons += nextSectorButton

        // Hacky, the rendering code is also what populates the buttons
        noFuelButtons = ArrayList()
        drawNoFuelUI(noFuelButtons)
        if (outOfFuel)
            buttons += noFuelButtons
    }

    /**
     * Draw the insides of the window. This must be done with a stencil.
     */
    private fun drawMapContent(g: Graphics) {
        // Draw the background image, offset 4px to account for the line wall of the window.
        background.draw(position.x + 4f, position.y + 4f)

        mapBase.x = position.x + Sector.OFFSET.x
        mapBase.y = position.y + Sector.OFFSET.y

        // In the last stand, the danger stripes sit behind everything else.
        // TODO draw the player and boss ships below this.
        if (sector.isLastStand) {
            drawLastStandDangerZone(g)
        }

        // Draw the connections to the adjacent beacons.
        drawBeaconLinesTo(g, game.currentBeacon, Constants.BEACON_LINE_PLAYER) { true }

        // Draw the line showing where the flagship will next jump
        for (boss in sector.bosses) {
            val currentBeacon = boss.beacon ?: continue
            val nextBeacon = boss.nextBeacon ?: continue

            if (!boss.jumping) {
                // Draw a dotted line if the flagship isn't jumping this turn
                drawBeaconLine(
                    g,
                    currentBeacon,
                    nextBeacon,
                    Constants.BEACON_LINE_FLAGSHIP
                )
            } else {
                // Draw a wide, continuous line if the flagship is jumping this turn.
                val a = mapBase + currentBeacon.pos
                val b = mapBase + nextBeacon.pos

                boss.drawJumpArc(g, a, b)
            }
        }

        // Make a local immutable copy to avoid nullability errors
        val hovered = hovered

        if (hovered != null && hovered != game.currentBeacon) {
            // Draw the lines between the hovered beacon and it's neighbours
            drawBeaconLinesTo(g, hovered, Constants.BEACON_LINE_HOVER) { it != game.currentBeacon }
        }

        val nextFleetPos = Point(sector.dangerZoneCentre)
        nextFleetPos.x += sector.getFleetAdvanceFor(game.currentBeacon)

        // Whether or not the ship is equipped with long-range scanners
        val hasLRS = game.player.hasAugment(AugmentBlueprint.LONG_RANGE_SCANNERS)

        // Draw the beacons
        for (beacon in sector.beacons) {
            val centrePos = mapBase + beacon.pos
            val pos = centrePos + beaconOffset

            val isNeighbour = neighbourVisSet.contains(beacon)
            val showBasicInfo = beacon.visited || sector.mapRevealed || isNeighbour
            val showAdvInfo = beacon.visited || sector.mapRevealed || (isNeighbour && hasLRS)

            // Draw the little blue transparent circle below nebulas
            if (beacon.environmentType.isNebula) {
                g.colour = Constants.BEACON_NEBULA_CIRCLE
                g.fillOval(centrePos.x - 9f, centrePos.y - 9f, 20f, 20f)
            }

            if (showAdvInfo && beacon.environmentType.isDangerous) {
                beaconEnvironmentHazard.draw(pos.x - 5, pos.y - 5)
            }

            // Draw the flashing background if this beacon
            // will be overtaken after this jump.
            val willBeOvertaken = beacon.pos.distToSq(nextFleetPos) < Sector.DANGER_ZONE_RADIUS_SQUARED
            if (willBeOvertaken && beacon.state != Beacon.State.OVERTAKEN) {
                val baseTimer = (System.nanoTime() - flashTimerBase) / 1000000000f
                val flashTimer = (baseTimer + beacon.overtakeFlashAnimationOffset).rem(2)

                val opacity = if (flashTimer < 1f) flashTimer else 2f - flashTimer

                beaconWillOvertakeCircle.draw(
                    pos.x + 1f, pos.y + 2f, pos.x + 31f, pos.y + 32f,
                    0f, 0f, 40f, 40f,
                    opacity, Colour.white
                )
            }

            beaconShadow.draw(pos)

            val beaconImg = when (beacon.state) {
                Beacon.State.UNVISITED -> when {
                    showAdvInfo && beacon.event.loadShipName != null -> beaconShipPresent
                    else -> beaconYellow
                }

                Beacon.State.VISITED_CLEAR -> beaconBlue
                Beacon.State.VISITED_DANGER -> beaconDanger
                Beacon.State.OVERTAKEN -> beaconOvertaken
            }
            beaconImg.draw(pos)

            if (beacon.event.isDistressBeacon && !beacon.visited && showBasicInfo && !willBeOvertaken)
                drawBeaconLabel(labelWhite, Constants.SECTOR_CUTOUT_TEXT, pos, game.translator["map_icon_distress"])

            if (beacon.event.isLastStandRepair && !beacon.visited && !willBeOvertaken)
                drawBeaconLabel(labelWhite, Constants.SECTOR_CUTOUT_TEXT, pos, game.translator["map_icon_repair"])

            // Note that quests and stores are cleared when the beacon is overrun,
            // so we don't have to check if willBeOvertaken is set.
            if (beacon.hasQuest && !beacon.visited)
                drawBeaconLabel(
                    labelPurple,
                    Constants.SECTOR_CUTOUT_TEXT_PURPLE,
                    pos,
                    game.translator["map_icon_quest"]
                )

            if (beacon.hasStore && showBasicInfo)
                drawBeaconLabel(labelWhite, Constants.SECTOR_CUTOUT_TEXT, pos, game.translator["map_icon_store"])

            if (beacon.isExit)
                drawBeaconLabel(labelGreen, Constants.SECTOR_CUTOUT_TEXT_GREEN, pos, game.translator["map_icon_exit"])

            if (beacon.isBase)
                drawBeaconLabel(labelPurple, Constants.SECTOR_CUTOUT_TEXT_PURPLE, pos, game.translator["map_icon_base"])

            if (beacon == hovered && canJumpTo(hovered)) {
                drawTargetBox(g, pos)
            }

            // Draw the player ship rotating around the beacon.
            val rotationSpeed = TWO_PI / 20f // Go around every 20 seconds
            if (beacon == game.currentBeacon) {
                val icon = when (fuelDistressFlashOn) {
                    true -> playerShipNoFuel
                    false -> playerShip
                }

                // The player's ship doesn't rotate when they're out of fuel
                if (!outOfFuel) {
                    playerRotation += game.renderingDeltaTime * rotationSpeed
                }

                // These offsets are approximate
                g.pushTransform()
                g.rotate(centrePos.x.f, centrePos.y.f, -playerRotation)
                icon.draw(centrePos.x - 8, centrePos.y - 32)
                g.popTransform()
            }

            // Draw the flagship rotating around the beacon.
            for (boss in sector.bosses) {
                if (boss.beacon != beacon || boss.jumping)
                    continue

                boss.drawMapIcon(g, centrePos)
            }
        }

        // TODO draw the fleet advance after the beacons and the circling ship,
        //  but before the labels (which we currently draw at the same time
        //  as the beacons).
        if (!sector.isLastStand) {
            drawDangerZone(g)
        }

        // For debugging, this can draw the grid the sectors fit in
        if (game.debugFlags.showMapGrid.set) {
            for (x in 0 until Sector.GRID_SIZE.x) {
                for (y in 0 until Sector.GRID_SIZE.y) {
                    g.colour = Colour.red
                    g.drawRect(mapBase.x + x * 110f, mapBase.y + y * 110f, 110f, 110f)
                }
            }
        }

        // If we're out of fuel, tint the map black and draw the text on top
        if (outOfFuel) {
            g.colour = Constants.JUMP_NO_FUEL_TINT
            g.fillRect(position.x, position.y, size.x, size.y)

            noFuelImage.drawAlignedCentred(
                position.x + size.x / 2, position.y + size.y / 2,
                noFuelImage.width * 3f, noFuelImage.height * 3f
            )
        }
    }

    private fun drawDangerZone(g: Graphics) {
        val dangerZoneRHS = sector.dangerZoneCentre.x + Sector.DANGER_ZONE_RADIUS
        val nextDangerZoneRHS = dangerZoneRHS + sector.getFleetAdvanceFor(game.currentBeacon)
        fleetAdvanceImg.draw(
            mapBase.x + nextDangerZoneRHS - 181f,
            mapBase.y + sector.dangerZoneCentre.y - 498f
        )
        g.colour = fleetAdvanceColour
        g.fillRect(
            position.x.f,
            position.y.f,
            (mapBase.x + nextDangerZoneRHS - 181f) - position.x,
            size.y.f
        )

        var fleetControlX = mapBase.x + dangerZoneRHS - 181f
        val fleetControlY = mapBase.y + sector.dangerZoneCentre.y - 498f
        fleetControlImg.draw(fleetControlX, fleetControlY)

        // Draw a bunch of tiles filling in the area controlled by
        // the fleet but not covered by the curved front image.
        while (fleetControlX >= mapBase.x) {
            fleetControlX -= fleetControlTile.width

            var tileY = fleetControlY
            while (tileY < position.y + size.y) {
                tileY += fleetControlTile.height

                fleetControlTile.draw(fleetControlX, tileY)
            }
        }
    }

    private fun drawLastStandDangerZone(g: Graphics) {
        // The last stand is entirely covered in stripes.
        g.colour = fleetAdvanceColour
        g.fillRect(position.x.f, position.y.f, size.x.f, size.y.f)

        for (tileX in position.x..position.x + size.x step fleetControlTile.width) {
            for (tileY in position.y..position.y + size.y step fleetControlTile.height) {
                fleetControlTile.draw(tileX, tileY)
            }
        }
    }

    override fun draw(g: Graphics) {
        Utils.drawStenciled(Utils.StencilMode.MASKING, {
            // Draw the stencil
            g.colour = Colour.red // Any non-transparent colour will work

            // The window edge width means the inside of the window has a four pixel boundary
            // This does leave a tiny area in the bevelled right-hand corner unstenciled,
            // but it seems unlikely anything will actually draw there.
            // FIXME this is visible - the background clips out
            g.fillRect(position.x + 4, position.y + 4, size.x - 8, size.y - 8)
        }, {
            // Draw the contents of the map
            drawMapContent(g)
        })

        // Draw the top-left map label tab
        val tab = game.translator["map_title"]
        val tabWidth = UIUtils.drawTab(font, tab, titleTab, position.x.f - GLOW, position.y.f - GLOW, 20f, 38f)
        font.drawString(position.x + 14f, position.y + 25f, tab, Constants.JUMP_DISABLED_TEXT)

        // Draw the rest of the top, going clockwise to the right side
        drawSide(Direction.UP, tabWidth.toInt())
        drawCorner(Direction.UP_RIGHT)
        drawSide(Direction.RIGHT)

        // Draw the bottom line connecting
        // Note these have to be drawn before the cancel button - see below
        drawSide(Direction.DOWN, sectorInfoTab.width)
        drawCorner(Direction.RIGHT_DOWN)

        // Cancel button
        // This is done in a slightly weird way - the bottom line is drawn exactly how it usually is, but
        // the cancel button is drawn just below it, cutting off part of its glow. The glow at the edges
        // of the cancel button image are modified specially to fit the glow of the line, so it looks
        // seamless. Note that this must be done after the lines are drawn, otherwise their glow would overlap
        // the cancel button frame.
        // Note we also have to stretch it to fit around the localised cancel button.
        val cancelY = position.y + size.y - 1
        val cancelH = cancelButtonOutline.height
        cancelButtonOutline.drawSection(cancelButton.pos.x - 24, cancelY, 34, cancelH)
        cancelButtonOutline.drawSection(cancelButton.pos.x + 10, cancelY, 20, cancelH, 34, 0, cancelButton.size.x - 20)
        cancelButtonOutline.drawSection(cancelButton.pos.x + cancelButton.size.x - 10, cancelY, 35, cancelH, 138)
        cancelButton.draw(g)

        // Draw the 'next sector' button frame, which has a similar glow trick to the cancel button
        if (game.currentBeacon.isExit) {
            val nsY = position.y + 4
            val nsH = nextSectorTab.height
            nextSectorTab.drawSection(nextSectorButton.pos.x - 19, nsY, 29, nsH)
            nextSectorTab.drawSection(nextSectorButton.pos.x + 10, nsY, 20, nsH, 24, 0, nextSectorButton.size.x - 20)
            nextSectorTab.drawSection(nextSectorButton.pos.x + nextSectorButton.size.x - 10, nsY, 18, nsH, 235)
            nextSectorButton.draw(g)
        }

        // Draw the sector info
        sectorInfoTab.draw(position.x - GLOW, position.y + size.y - 20)

        // Cutouts for text
        fun drawCutout(x: Int, y: Int, width: Int, text: String) {
            g.colour = Constants.SECTOR_CUTOUT
            g.fillRect(x + 3f, y.f, width - 6f, 2f)
            g.fillRect(x + 2f, y + 1f, width - 4f, 2f)
            g.fillRect(x + 1f, y + 2f, width - 2f, 2f)
            g.fillRect(x.f, y + 3f, width.f, 20f)
            g.fillRect(x + 1f, y + 22f + 0f, width - 2f, 2f)
            g.fillRect(x + 2f, y + 22f + 1f, width - 4f, 2f)
            g.fillRect(x + 3f, y + 22f + 2f, width - 6f, 2f)

            val txtFont = sectorInfoFont
            val textWidth = txtFont.getWidth(text)
            val tx = width / 2 - textWidth / 2
            txtFont.drawString(x + tx.f, y + 20f, text, Constants.SECTOR_CUTOUT_TEXT)
        }

        val sectorText = game.translator["map_sector"]
        val sectorTextY = position.y + size.y + 22f
        font.drawString(position.x + 6f, sectorTextY, sectorText, Constants.JUMP_DISABLED_TEXT)

        val sectorName = game.translator["sectorname_" + sector.type.name]
        drawCutout(position.x + 134, position.y + size.y - 1, 38, (sector.sectorNumber + 1).toString())
        drawCutout(position.x + 183, position.y + size.y - 1, 276, sectorName)

        // The top and bottom tabs are slightly different sizes, this compensates for them
        drawSide(Direction.LEFT, 45, size.y + GLOW * 2 - 27)

        // Draw the out-of-fuel UI
        if (outOfFuel) {
            drawNoFuelUI(null)

            for (button in noFuelButtons) {
                button.draw(g)
            }
        }
    }

    // It's a bit horrible, but this function can both draw and create buttons, since
    // most of the work is calculating the positions of everything.
    private fun drawNoFuelUI(buttons: MutableList<Button>?) {
        val rightX = position.x + size.x
        val frameY = position.y + size.y - 57

        // Calculate all the text-based positions
        val offText = game.translator["button_distress_off"]
        val onText = game.translator["button_distress_on"]
        val toggleTextWidth = max(font.getWidth(offText), font.getWidth(onText))
        val toggleButtonWidth = toggleTextWidth + 4 * 2
        val toggleX = rightX - 12 - toggleButtonWidth

        val distressText = game.translator["map_distress_beacon"]
        val distressLabelWidth = distressBeaconFont.getWidth(distressText)
        val labelAreaWidth = 7 + distressLabelWidth + 8
        val labelAreaX = toggleX - 7 - labelAreaWidth

        val waitText = game.translator["button_wait"]
        val waitButtonWidth = font.getWidth(waitText) + 4 * 2
        val waitButtonX = labelAreaX - 5 - 4 - waitButtonWidth

        val leftX = waitButtonX - 4 - 5

        if (buttons == null) {
            // Draw the background box
            val imgY = frameY - 10
            val imgH = waitDistressFrame.height
            waitDistressFrame.drawSection(leftX - 10, imgY, 22, imgH)
            waitDistressFrame.drawSection(waitButtonX + 3, imgY, 1, imgH, 22, 0, waitButtonWidth - 6)
            waitDistressFrame.drawSection(labelAreaX - 12, imgY, 22, imgH, 27)
            waitDistressFrame.drawSection(labelAreaX + 10, imgY, 1, imgH, 49, 0, labelAreaWidth - 10 - 5)
            waitDistressFrame.drawSection(toggleX - 7 - 5, imgY, 12, imgH, 48)
            waitDistressFrame.drawSection(toggleX, imgY, 1, imgH, 60, 0, toggleButtonWidth - 3)
            waitDistressFrame.drawSection(rightX - 15, imgY, 15, imgH, 62)

            // Draw the beacon toggle button's label text
            // Note the Y value comes from the DE localisation.
            distressBeaconFont.drawStringCentredVertical(
                labelAreaX, frameY + 33,
                labelAreaWidth,
                18,
                distressText,
                Constants.SECTOR_CUTOUT_TEXT
            )

            if (fuelDistressFlashOn) {
                fuelDistressFlashLight.draw(rightX - 2 - 33, frameY + 45 - 23)
            }

            if (fuelDistressOn) {
                fuelDistressFlashTimer = (fuelDistressFlashTimer + game.renderingDeltaTime).mod(1f)

                // This sound is special-cased
                (game.sounds as? RealSoundManager)?.playingFuelDistressSound = true
            } else {
                // Reset, so it immediately lights up when turned back on
                fuelDistressFlashTimer = 0f
            }
        } else {
            // Wait button
            buttons += Buttons.BasicButton(
                game,
                ConstPoint(waitButtonX, frameY + 9), ConstPoint(waitButtonWidth, 36),
                waitText, 4, font, 27
            ) {
                waitOutOfFuel()
            }

            // Toggle distress beacon
            buttons += object : Button(game, ConstPoint(toggleX, frameY + 9), ConstPoint(toggleButtonWidth, 36)) {
                override fun draw(g: Graphics) {
                    g.colour = when {
                        disabled -> Constants.JUMP_DISABLED
                        hovered -> Constants.UI_BUTTON_HOVER
                        else -> Constants.SECTOR_CUTOUT_TEXT
                    }
                    Buttons.drawRounded(g, pos.x, pos.y, size.x, size.y, 4)

                    // Draw the background, which is rounded on the right only
                    // Draw a bunch of overlapping rectangles, making a diagonal corner.
                    for (i in 0..4) {
                        g.fillRect(pos.x, pos.y + 4 - i, size.x - i, size.y - (4 - i) * 2)
                    }

                    val label = when (fuelDistressOn) {
                        true -> onText
                        false -> offText
                    }

                    font.drawStringCentred(pos.x.f, pos.y + 27f, size.x.f, label, Constants.JUMP_DISABLED_TEXT)
                }

                override fun click(button: Int) {
                    fuelDistressOn = !fuelDistressOn
                }
            }
        }
    }

    override fun updateUI(x: Int, y: Int) {
        super.updateUI(x, y)

        hovered = null

        // Can't hover over beacons while out of fuel
        if (outOfFuel) {
            org.newdawn.slick.util.Log.info("JumpWindow.updateUI at ($x,$y): out of fuel")
            return
        }

        val closest = sector.beacons.map {
            val bp = it.pos + mapBase
            val dist = bp.distToSq(ConstPoint(x, y))
            Pair(it, dist)
        }.minByOrNull { it.second } ?: return

        val hoverDist = 12
        if (closest.second > hoverDist * hoverDist)
            return

        hovered = closest.first
    }

    /**
     * True if the player has the Adv. FTL Navigation augment, which lets
     * them jump to any beacon they've previously visited in this sector.
     */
    private val hasAdvFtlNavigation: Boolean
        get() = game.player.hasAugment(AugmentBlueprint.ADVANCED_FTL_NAVIGATION)

    /**
     * Work out whether the player can jump to the given beacon right now.
     *
     * Ordinarily the only valid destinations are the beacons neighbouring
     * the player's current one, but the Adv. FTL Navigation augment also
     * permits jumping to any previously visited beacon in the sector
     * (including ones the Rebel Fleet has since overtaken). The 'aj'
     * (anyjump) debug flag takes precedence over all of that, letting the
     * player jump to any beacon on the map - convenient for testing.
     */
    private fun canJumpTo(beacon: Beacon): Boolean {
        if (game.debugFlags.anyJump.set)
            return true

        if (beacon == game.currentBeacon)
            return false

        if (game.currentBeacon.neighbours.contains(beacon))
            return true

        return beacon.visited && hasAdvFtlNavigation
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        super.mouseClick(button, x, y)

        if (button != Input.MOUSE_LEFT_BUTTON)
            return

        val hovered = hovered ?: return

        if (!canJumpTo(hovered))
            return

        jump(hovered)

        game.player.fuelCount--

        // Advance the fleet pursuit *before* changing the beacon, since
        // if we're in a nebula that slows down the fleet pursuit.
        game.advanceFleet()

        // Play the jump-out animation (issue #4); the beacon switch - and
        // the arrival animation at the new beacon - happens when it ends.
        game.beginJumpOut(hovered)
    }

    private fun waitOutOfFuel() {
        // Close the window and advance the fleet
        jump(game.currentBeacon)
        game.advanceFleet()

        // TODO the escape events that play when you were in a dangerous environment

        // Pick a suitable event
        val eventName: String = when {
            // Rebels have overtaken this beacon
            // TODO make the rebel fleet background and PDS (once implemented) show up
            game.currentBeacon.isOvertaken -> when (game.content.enableAdvancedEdition) {
                true -> "NO_FUEL_FLEET_DLC"
                false -> "NO_FUEL_FLEET"
            }

            fuelDistressOn -> "NO_FUEL_DISTRESS"
            else -> "NO_FUEL"
        }
        val event = game.eventManager[eventName].resolve()

        // TODO make this seed reproducable if the game is reloaded?
        game.shipUI.showEventDialogue(event, Random.nextInt())

        // Clear the environment, so if the beacon was overtaken it now
        // has the enemy background etc there.
        game.currentBeacon.clearEnvironment()
    }

    override fun escapePressed() {
        cancelClicked()
    }

    private fun drawTargetBox(g: Graphics, pos: IPoint) {
        drawTargetMarkers(g, targetBox, pos.x + targetBox.width / 2, pos.y + targetBox.height / 2)
    }

    private fun drawBeaconLinesTo(g: Graphics, beacon: Beacon, colour: Colour, predicate: (Beacon) -> Boolean) {
        for (neighbour in beacon.neighbours) {
            if (!predicate(neighbour))
                continue

            drawBeaconLine(g, beacon, neighbour, colour)
        }
    }

    private fun drawBeaconLine(g: Graphics, from: Beacon, to: Beacon, colour: Colour) {
        g.pushTransform()

        val fromPos = from.pos + mapBase + beaconOffset
        val toPos = to.pos + mapBase + beaconOffset

        // Find the delta vector between the two points we're drawing between, and the length of said vector
        val delta = toPos - fromPos
        val dist = delta.distTo(ConstPoint.ZERO)
        val angle = atan2(delta.y.f, delta.x.f) * 180 / PIf

        // Translate to the start position, and rotate so that positive x runs along our line.
        // The beacons are drawn at their image origins, and they're 32px² - so +16 for x,y.
        g.translate(fromPos.x + 16f, fromPos.y + 16f)
        g.rotate(0f, 1.5f, angle)

        val segmentWidth = 10

        // Step along the path of the vector, drawing images in each place
        for (i in 6..(dist - 5) step segmentWidth) {
            // Draw the line itself
            lineImg.drawSection(i, 0, segmentWidth, 4, 1, 0, colour = colour)
        }

        g.popTransform()
    }

    private fun drawBeaconLabel(images: List<Image>, colour: Colour, pos: IPoint, text: String) {
        // AFAIK the labels in FTL are drawn with alpha=204, TODO use that

        val strWidth = beaconLabelFont.getWidth(text)

        val boxTop = pos.y - 11
        val boxTopWithGlow = boxTop - 6

        images[0].draw(pos.x + 15, boxTopWithGlow)
        images[1].draw(pos.x + 34f, boxTopWithGlow.f, strWidth - 8f, 32f)
        images[2].draw(pos.x + 26 + strWidth, boxTopWithGlow)

        beaconLabelFont.drawString(pos.x + 30f, boxTop + 10f, text, colour)
    }

    private fun drawCorner(edge: Direction) {
        val x = position.x - GLOW + edge.x.coerceAtLeast(0) * (size.x + GLOW * 2 - 33)
        val y = position.y - GLOW + edge.y.coerceAtLeast(0) * (size.y + GLOW * 2 - 36)
        val tx = edge.x.coerceAtLeast(0) * outlineImage.width / 2
        val ty = edge.y.coerceAtLeast(0) * outlineImage.height / 2

        outlineImage.drawSection(x, y, 33, 36, tx, ty)
    }

    private fun drawSide(edge: Direction, start: Int? = null, stop: Int? = null) {
        val paddedSizeX = size.x + GLOW * 2
        val paddedSizeY = size.y + GLOW * 2

        val xb = position.x - GLOW + edge.x.coerceAtLeast(0) * (paddedSizeX - 33)
        val yb = position.y - GLOW + edge.y.coerceAtLeast(0) * (paddedSizeY - 36)

        val texPos = when (edge) {
            Direction.UP -> ConstPoint(33, 0)
            Direction.LEFT -> ConstPoint(0, 36)
            Direction.DOWN, Direction.RIGHT -> ConstPoint(33, 36)
            else -> error("Invalid side edge $edge")
        }

        val texSize = when (edge) {
            Direction.UP, Direction.DOWN -> ConstPoint(1, 36)
            Direction.LEFT, Direction.RIGHT -> ConstPoint(33, 1)
            else -> error("Invalid side edge $edge")
        }

        val x: Int
        val y: Int

        val drawSize: ConstPoint
        when (edge) {
            Direction.UP, Direction.DOWN -> {
                x = xb + (start ?: 33)
                y = yb
                drawSize = ConstPoint((stop ?: (paddedSizeX - 33)) - (start ?: 33), 36)
            }

            Direction.LEFT, Direction.RIGHT -> {
                x = xb
                y = yb + (start ?: 36)
                drawSize = ConstPoint(33, (stop ?: (paddedSizeY - 36)) - (start ?: 36))
            }

            else -> error("Invalid edge $edge")
        }

        outlineImage.draw(
            x.f,
            y.f,
            x.f + drawSize.x,
            y.f + drawSize.y,
            texPos.x.f,
            texPos.y.f,
            texPos.x.f + texSize.x,
            texPos.y.f + texSize.y
        )
    }

    private fun cancelClicked() {
        jump(null)
    }

    companion object {
        // The width of the glow around the edge of the window
        private const val GLOW = 7

        /**
         * This draws the little rounded corners that move in and out, which is used
         * both on the beacon and sector map.
         */
        fun drawTargetMarkers(g: Graphics, targetBox: Image, centreX: Int, centreY: Int) {
            val period = 1_000_000_000
            val timePoint = (System.nanoTime() % period) / period.toFloat()
            val distFactor = (1 + sin(timePoint * (Math.PI * 2))) / 2
            val spacing = (distFactor * 4).roundToInt()

            g.pushTransform()
            g.translate(centreX.f, centreY.f)

            val boxX = -spacing - targetBox.width / 2
            val boxY = -spacing - targetBox.height / 2

            targetBox.draw(boxX, boxY)
            g.rotate(0f, 0f, 90f)
            targetBox.draw(boxX, boxY)
            g.rotate(0f, 0f, 90f)
            targetBox.draw(boxX, boxY)
            g.rotate(0f, 0f, 90f)
            targetBox.draw(boxX, boxY)

            g.popTransform()
        }
    }
}

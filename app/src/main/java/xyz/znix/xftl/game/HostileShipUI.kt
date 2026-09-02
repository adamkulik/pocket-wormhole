package xyz.znix.xftl.game

import xyz.znix.xftl.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

class HostileShipUI(private val game: InGameState, private val enemy: Ship) {
    private val mutableShipPos = Point(0, 0)
    val shipPos: IPoint get() = mutableShipPos

    private val font = game.getFont("HL2")
    private val titleFont = game.getFont("HL2", 2f)
    private val statusFont = game.getFont("JustinFont8")
    private val jumpWarningFont = game.getFont("HL1", 2f)

    private val shieldIconStandard = game.getImg("img/combatUI/box_hostiles_shield1.png")
    private val shieldIconBroken = game.getImg("img/combatUI/box_hostiles_shield2.png")
    private val shieldIconStandardHacked = game.getImg("img/combatUI/box_hostiles_shield2_hacked_charged.png")
    private val shieldIconBrokenHacked = game.getImg("img/combatUI/box_hostiles_shield2_hacked.png")

    private val superShieldBar = game.getImg("img/combatUI/box_hostiles_shield_super5.png")
    private val superShieldBarBoss = game.getImg("img/combatUI/box_hostiles_shield_super12.png")
    private val shieldChargeBar = game.getImg("img/combatUI/box_hostiles_shield_charge.png")

    private val boxNormal = game.getImg("img/combatUI/box_hostiles2.png")
    private val boxBoss = game.getImg("img/combatUI/box_hostiles_boss.png")

    private val maskNormal = game.getImg("img/combatUI/box_hostiles_mask.png")
    private val maskBoss = game.getImg("img/combatUI/box_hostiles_boss_mask.png")

    fun render(gc: GameContainer, g: Graphics, hoveredRoom: Room?, interiorVisible: Boolean, isHostile: Boolean) {
        val box = when (enemy.isUsingBossUI) {
            true -> boxBoss
            false -> boxNormal
        }
        val mask = when (enemy.isUsingBossUI) {
            true -> maskBoss
            false -> maskNormal
        }

        val filter = when (isHostile) {
            true -> Constants.SHIP_BOX_HOSTILE
            false -> Constants.SHIP_BOX_NEUTRAL
        }
        val textColour = when (isHostile) {
            true -> Constants.SHIP_BOX_TEXT_HOSTILE
            false -> Constants.SHIP_BOX_TEXT_NEUTRAL
        }

        // The position of the box, when you remove the glow padding
        val boxX: Int
        val boxY: Int

        val leftGlow: Int
        val rightGlow: Int
        val topGlow: Int
        val bottomGlow: Int
        if (enemy.isUsingBossUI) {
            leftGlow = 4
            rightGlow = 4
            topGlow = 4
            bottomGlow = 4
            boxX = gc.width - (box.width - rightGlow) - 10 + leftGlow
            boxY = 11
        } else {
            leftGlow = 10
            rightGlow = 20
            topGlow = 9
            bottomGlow = 17
            boxX = gc.width - (box.width - rightGlow) - 18 + leftGlow
            boxY = 54
        }

        // The position of the image
        val rawBoxX = boxX - leftGlow
        val rawBoxY = boxY - topGlow

        val boxRightX = rawBoxX + box.width - rightGlow
        val boxBottomY = rawBoxY + box.height - bottomGlow

        // It's not quite the same as FTL, but works well enough for now
        mutableShipPos.x = boxX + (box.width - enemy.hullImage.width) / 2
        mutableShipPos.y = boxY + (box.height - enemy.hullImage.height) / 2
        mutableShipPos -= enemy.hullOffset

        box.draw(rawBoxX, rawBoxY, filter)

        Utils.drawStenciled(Utils.StencilMode.MASKING, {
            mask.draw(rawBoxX, rawBoxY)
        }) {
            g.pushTransform()
            g.translate(shipPos.x.f, shipPos.y.f)
            enemy.render(g, interiorVisible, hoveredRoom)

            val playerWeapons = game.shipUI.ship.weapons
            if (playerWeapons != null) {
                enemy.renderTargeting(g, playerWeapons.selectedTargets)
            }

            g.popTransform()
        }

        val textX = boxX + 2

        // Draw the title
        val titleX = textX + when (enemy.isUsingBossUI) {
            true -> 7
            else -> 1
        }
        titleFont.drawString(titleX.f, boxY + 16f, game.translator["target_window"], textColour)

        // Draw the class and relationship text
        val statusTextX = boxRightX - 11
        val classY = boxY + 38
        drawStatus(statusTextX, classY, isHostile)

        // Draw the FTL charging warning, if relevant.
        val centreX = rawBoxX + box.width / 2
        drawEscapeWarning(centreX, boxY)

        // Draw the hull level
        val hullY = boxY + 20 + 4
        renderSmallbar(textX, hullY, "status_hull", filter, textColour)
        val hpWidth = 11 * enemy.health
        val hpX = textX + 5
        val hpY = hullY + 12
        val hull = game.getImg("img/combatUI/box_hostiles_hull2.png")
        hull.draw(
            hpX.f, hpY.f, hpX.f + hpWidth, hpY.f + hull.height,
            0f, 0f, hpWidth.f, hull.height.f,
            1f, Constants.SHIP_HEALTH_HIGH
        )

        enemy.shields?.let { shields ->
            // Draw the shield bubbles
            val shieldsY = hullY + 27
            renderSmallbar(textX, shieldsY, "status_shields", filter, textColour)

            var bubbleX = textX + 7

            for (i in 0 until shields.selectedShieldBars) {
                val intact = i < shields.activeShields
                val hacked = shields.isHackActive
                val img = when {
                    hacked && intact -> shieldIconStandardHacked
                    hacked && !intact -> shieldIconBrokenHacked
                    intact -> shieldIconStandard
                    else -> shieldIconBroken
                }
                img.draw(bubbleX, shieldsY + 15)
                bubbleX += 23
            }

            if (enemy.superShield != 0) {
                // TODO support ships with a super-shield but no shields system
                bubbleX += 10
                val superShieldY = shieldsY + 15 + 5

                superShieldBar.draw(bubbleX, superShieldY)

                val width = 50f * enemy.superShield / enemy.maxSuperShield
                g.colour = Constants.SYS_ENERGY_ACTIVE
                g.fillRect(bubbleX + 3f, superShieldY + 3f, width, 7f)
            } else if (shields.rechargeTimer != 0f) {
                // Draw the charge bar
                shieldChargeBar.draw(textX + 5, shieldsY + 39)

                val progress = shields.rechargeTimer / shields.rechargeDelay
                val colour = when {
                    shields.isHackActive -> Constants.SHIELD_BAR_HACKED
                    else -> Constants.SHIELD_BAR_NORMAL
                }

                val width = (56 * progress)
                g.colour = colour
                g.fillRect(textX.f + 5 + 3, shieldsY.f + 39 + 3, width, 6f)
            }
        }

        val powerVisible = game.player.sensors?.showsEnemyPowerLevels == true
                || game.debugFlags.showEverything.set

        // Draw the enemy's systems
        // TODO sorting
        for ((i, sys) in enemy.systems.withIndex()) {
            val y = boxBottomY - 49
            val x = boxX + i * 30 + 49

            val roomPowerVisible = powerVisible || sys.hackedBy?.isPoweredUp == true

            sys.drawIconAndPower(game, g, false, roomPowerVisible, false, x, y)
        }
    }

    private fun drawStatus(x: Int, classY: Int, isHostile: Boolean) {
        val relationY = classY + 15

        if (enemy.type.shipClass != null) {
            val shipClassName = game.translator[enemy.type.shipClass]
            val classStr = game.translator["combat_class"].replaceArg(shipClassName)

            statusFont.drawStringLeftAligned(x.f, classY.f, classStr, Constants.SHIP_STATUS_PLAIN)
        }

        val relationText = when (isHostile) {
            true -> game.translator["hostile"]
            false -> game.translator["neutral"]
        }
        val relationColour = when (isHostile) {
            true -> Constants.SHIP_STATUS_HOSTILE
            false -> Constants.SHIP_STATUS_PLAIN
        }
        val relationStr = game.translator["combat_relationship"].replaceArg(relationText)
        statusFont.drawStringLeftAligned(x.f, relationY.f, relationStr, relationColour)
    }

    private fun drawEscapeWarning(centreX: Int, boxY: Int) {
        val timeRemaining = enemy.escapeTimer ?: return

        val warningKey = when {
            !enemy.canChargeFTL -> "warning_ftl_delayed"
            timeRemaining < 5f -> "warning_ftl_imminent"
            else -> "warning_ftl_charging"
        }

        var alpha = 1f

        // Flash the alpha if a jump is imminent.
        if (warningKey == "warning_ftl_imminent") {
            // Run the flashing animation while paused
            var timeNS = System.nanoTime()
            if (timeNS < 0) {
                timeNS += Long.MAX_VALUE
            }

            val period = 700_000_000
            val progress = (timeNS % period).toFloat() / period

            // Fade up then down
            alpha = progress * 2
            if (alpha > 1) {
                alpha = 2 - alpha
            }
        }

        val message = game.translator[warningKey]

        val leftX = centreX - jumpWarningFont.getWidth(message) / 2
        UIUtils.drawStringWithGlow(game, jumpWarningFont, message, leftX, boxY - 10, GlowColour.RED, alpha)
    }

    private fun renderSmallbar(x: Int, y: Int, key: String, filter: Colour, textColour: Colour) {
        val text = game.translator[key]

        val textX = 2
        val textWidth = font.getWidth(text)

        val left = game.getImg("img/combatUI/box_hostiles_smallbar_left.png")
        left.draw(x, y, filter)
        val middle = game.getImg("img/combatUI/box_hostiles_smallbar_middle.png")
        val midWidth = textWidth + textX - left.width
        middle.draw(
            x.f + left.width, y.f,
            x.f + left.width + midWidth, y.f + middle.height,
            0f, 0f, middle.width.f, middle.height.f,
            1f, filter
        )

        val rightImg = game.getImg("img/combatUI/box_hostiles_smallbar_right.png")
        rightImg.draw(x + left.width + midWidth, y, filter)

        font.drawString(x + 2f, y + 9f, text, textColour)
    }
}

package xyz.znix.xftl.game

import xyz.znix.xftl.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.PlatformSpecific
import kotlin.math.roundToInt

class HostileShipUI(private val game: InGameState, private val enemy: Ship) {
    companion object {
        private const val FLY_OUT_TIME = 1.2f
        private const val FLY_OUT_SCALE_END = 0.35f

        /**
         * How much smaller than vanilla the enemy ship box is drawn on
         * touch layouts - see [boxScale].
         */
        private const val TOUCH_BOX_SCALE = 0.9f
    }


    private val mutableShipPos = Point(0, 0)
    val shipPos: IPoint get() = mutableShipPos

    /**
     * The scale the enemy's box - frame, labels, bars and the ship drawn
     * inside it - is rendered at. Vanilla is 1; on touch layouts the box
     * is drawn slightly smaller ([TOUCH_BOX_SCALE]) and anchored to the
     * vanilla content top/right edges, so it clears the scaled systems
     * strip while leaving the FTL escape warning (drawn above the box)
     * its vanilla headroom. While any touch targeting mode is active
     * (weapon targeting or one of the teleporter/hacking/mind-control
     * room pickers) it returns to full size ("scaled slightly up" from
     * the shrunken default), making the rooms easier to tap. Hit-testing
     * code must convert through [convertScreenToShipRender] /
     * [shipRenderToScreen].
     */
    private val boxScale: Float
        get() = when {
            enemy.isUsingBossUI -> 1f
            game.isTouchTargeting() -> 1f
            PlatformSpecific.INSTANCE.isTouchUi -> TOUCH_BOX_SCALE
            else -> 1f
        }

    // Issue #4: the jump-away animation state (goes from 1 to 0). Started
    // by [startFlyOut] when the ship's escape timer expires; InGameState
    // removes the ship once [isFlyOutDone] is true.
    private var flyOut = 0f
    val isFlyOutDone: Boolean get() = flyOut <= 0f

    fun startFlyOut() {
        flyOut = 1f
    }

    /**
     * Convert a screen-space position to the enemy ship's render space
     * (what (mouse - shipPos) used to be), accounting for the touch
     * layout's scaled box. Complements [getShipPos]. Only valid while
     * the enemy ship is displayed.
     */
    fun convertScreenToShipRender(point: Point) {
        point.x = ((point.x - mutableShipPos.x) / boxScale).roundToInt()
        point.y = ((point.y - mutableShipPos.y) / boxScale).roundToInt()
    }

    /**
     * Convert a position in the enemy ship's render space (e.g. a
     * crewmember's screenX/screenY) to screen space, for hit-testing
     * things on the enemy ship - your boarding party, mainly.
     */
    fun shipRenderToScreen(x: Int, y: Int): IPoint = Point(
        (mutableShipPos.x + boxScale * x).roundToInt(),
        (mutableShipPos.y + boxScale * y).roundToInt()
    )

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
        // Tick the jump-away animation (issue #4). Ticked from render so it
        // keeps playing while the game is frozen for the animation.
        if (flyOut > 0) {
            flyOut = (flyOut - game.renderingDeltaTime / FLY_OUT_TIME).coerceAtLeast(0f)
        }

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

        val leftGlow: Int
        val rightGlow: Int
        val topGlow: Int
        val bottomGlow: Int
        // The vanilla gap between the box's content edge and the screen's
        // right edge, and the vanilla y of the content box's top.
        val rightMargin: Int
        val vanillaBoxY: Int
        if (enemy.isUsingBossUI) {
            leftGlow = 4
            rightGlow = 4
            topGlow = 4
            bottomGlow = 4
            rightMargin = 10
            vanillaBoxY = 11
        } else {
            leftGlow = 10
            rightGlow = 20
            topGlow = 9
            bottomGlow = 17
            rightMargin = 18
            vanillaBoxY = 54
        }

        // On touch layouts the whole box - frame, text, bars and the ship
        // inside it - is drawn scaled down by [boxScale], anchored to the
        // vanilla content top/right position. The full-size box's bottom
        // (content bottom y = 583) collides with the scaled systems
        // strip's weapon/drone boxes (y~541), and the previous fix of
        // lifting the box pushed the FTL escape warning - drawn at
        // boxY - 10 - off the top of the screen. Drawing everything
        // slightly smaller keeps the warning's vanilla headroom while
        // the content bottom rises to ~530, clearing the strip. The boss
        // frame stays full-size: even scaled it can't clear the strip,
        // and it rarely overlaps one that long
        // (touch-strip-scaling-plan.md).
        val scale = boxScale

        // The screen-space position of the box image's top-left corner,
        // keeping the vanilla content top and right edges.
        val anchorX = gc.width - (box.width - rightGlow) - rightMargin +
                ((box.width - rightGlow) * (1 - scale)).roundToInt()
        val anchorY = vanillaBoxY - (topGlow * scale).roundToInt()

        // Everything from here to the escape warning is drawn through a
        // translate+scale transform, in coordinates local to the box
        // image (the vanilla layout with the image's top-left corner at
        // the origin). At a scale of 1 every element lands exactly where
        // vanilla draws it.
        val boxX = leftGlow
        val boxY = topGlow
        val boxRightX = box.width - rightGlow
        val boxBottomY = box.height - bottomGlow

        // It's not quite the same as FTL, but works well enough for now
        val localShipX = boxX + (box.width - enemy.hullImage.width) / 2
        val localShipY = boxY + (box.height - enemy.hullImage.height) / 2
        mutableShipPos.x = (anchorX + scale * (localShipX - enemy.hullOffset.x)).roundToInt()
        mutableShipPos.y = (anchorY + scale * (localShipY - enemy.hullOffset.y)).roundToInt()

        g.pushTransform()
        g.translate(anchorX.f, anchorY.f)
        g.scale(scale, scale)

        box.draw(0, 0, filter)

        Utils.drawStenciled(Utils.StencilMode.MASKING, {
            mask.draw(0, 0)
        }) {
            g.pushTransform()
            g.translate(localShipX.f - enemy.hullOffset.x, localShipY.f - enemy.hullOffset.y)

            // Issue #4: jumping away - scale down, then the glowing star
            // sweeps from stern to bow (right to left for the mirrored
            // enemy ship) while the ship fades out.
            if (flyOut > 0) {
                val p = 1f - flyOut
                val centreX = enemy.hullCentreX
                val centreY = enemy.hullCentreY

                if (p < FLY_OUT_SCALE_END) {
                    val t = p / FLY_OUT_SCALE_END
                    val smooth = t * t * (3f - 2f * t)
                    val scale = 1f - 0.88f * smooth

                    g.translate(centreX, centreY)
                    g.scale(scale, scale)
                    g.translate(-centreX, -centreY)
                } else {
                    val fade = (p - FLY_OUT_SCALE_END) / (1f - FLY_OUT_SCALE_END)
                    val smooth = fade * fade * (3f - 2f * fade)
                    enemy.renderAlpha = 1f - smooth

                    g.translate(centreX, centreY)
                    g.scale(0.12f, 0.12f)
                    g.translate(-centreX, -centreY)
                }
            }

            enemy.render(g, interiorVisible, hoveredRoom)

            // The star rides on top, sweeping stern -> bow during the
            // fade-out phase.
            if (flyOut > 0) {
                val p = 1f - flyOut
                if (p >= FLY_OUT_SCALE_END) {
                    val fade = (p - FLY_OUT_SCALE_END) / (1f - FLY_OUT_SCALE_END)
                    val sweep = fade * fade * (3f - 2f * fade)
                    val starSize = enemy.hullImage.height *
                            (0.2f + 1.6f * (Math.sin(Math.PI * sweep)).toFloat())
                    val flareX = enemy.hullRightX +
                            (enemy.hullLeftX - enemy.hullRightX) * sweep
                    val centreY = enemy.hullCentreY
                    val alpha = 0.55f + 0.45f * (Math.sin(Math.PI * sweep)).toFloat()

                    game.jumpFlare.draw(
                        flareX - starSize / 2f, centreY - starSize / 2f,
                        starSize, starSize, Colour(1f, 1f, 1f, alpha)
                    )
                }
                enemy.renderAlpha = 1f
            }

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

        g.popTransform()

        // Draw the FTL charging warning, if relevant. It sits above the
        // box and is drawn in screen space at the vanilla position, so
        // it keeps its full size and headroom even when the box itself
        // is scaled down on touch layouts.
        val centreX = (anchorX + scale * box.width / 2).roundToInt()
        drawEscapeWarning(centreX, vanillaBoxY - 10)
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

    private fun drawEscapeWarning(centreX: Int, y: Int) {
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
        UIUtils.drawStringWithGlow(game, jumpWarningFont, message, leftX, y, GlowColour.RED, alpha)
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

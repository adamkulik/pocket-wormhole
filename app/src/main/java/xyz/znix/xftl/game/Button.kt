package xyz.znix.xftl.game

import xyz.znix.xftl.*
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.*
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sign

abstract class Button(protected val game: InGameState, pos: IPoint, size: IPoint) {
    val basePos = pos.const
    var windowOffset: IPoint = ConstPoint.ZERO
        set(value) {
            field = value
            positionUpdated()
        }

    var pos = basePos
        private set
    val size = size.const

    var hovered: Boolean = false
        private set

    /**
     * Like [hovered], but also set to true if the button is disabled.
     */
    var rawHovered: Boolean = false
        private set

    /**
     * True if the mouse is hovering over a button in front of this one.
     */
    var mouseObstructed = false
        private set

    /**
     * True if the 'hoverBeep' sound should play when this button is moused over.
     */
    open val makesHoverNoise: Boolean get() = true

    /**
     * True if this button is disabled and can't be hovered.
     *
     * Note this doesn't prevent [click] from being called.
     */
    open val disabled: Boolean get() = false

    abstract fun draw(g: Graphics)

    /**
     * Alert the button that the mouse has been clicked at a specified location.
     *
     * @return true if this button absorbed the click, false otherwise
     */
    open fun mouseDown(button: Int, x: Int, y: Int): Boolean {
        if (!contains(x, y)) return false
        if (mouseObstructed) return false
        click(button)
        return true
    }

    protected abstract fun click(button: Int)

    fun contains(point: IPoint) = contains(point.x, point.y)

    open fun contains(x: Int, y: Int): Boolean {
        return pos.x <= x && x < pos.x + size.x && pos.y <= y && y < pos.y + size.y
    }

    open fun update(x: Int, y: Int, blockHover: Boolean = false) {
        mouseObstructed = blockHover

        rawHovered = contains(x, y)

        if (disabled || mouseObstructed) {
            hovered = false
            return
        }

        val lastHover = hovered
        hovered = rawHovered

        if (hovered && !lastHover && makesHoverNoise) {
            game.sounds.getSample("hoverBeep").play()
        }
    }

    protected open fun positionUpdated() {
        pos = basePos + windowOffset
    }
}

class SimpleButton(
    game: InGameState, pos: IPoint, size: IPoint, imgOffset: IPoint,
    val normal: Image, val hover: Image?, val tooltip: ITooltipProvider?,
    private val callback: (Int) -> Unit
) : Button(game, pos, size) {
    val imgOffset = imgOffset.const
    private var imagePos = pos - imgOffset

    override fun draw(g: Graphics) {
        var image = normal
        if (hovered && hover != null)
            image = hover
        image.draw(imagePos)

        if (hovered) {
            g.tooltip = tooltip
        }
    }

    override fun positionUpdated() {
        super.positionUpdated()
        imagePos = pos - imgOffset
    }

    override fun click(button: Int) = callback(button)

    companion object {
        // Create a new button by specifying the clickable region within it,
        // rather than using pos and imgOffset to achive it.
        fun byRegion(
            game: InGameState, pos: IPoint, buttonOffset: IPoint, buttonSize: IPoint,
            normal: Image, hover: Image?, tooltip: ITooltipProvider?,
            callback: (Int) -> Unit
        ): SimpleButton {
            return SimpleButton(game, pos + buttonOffset, buttonSize, buttonOffset, normal, hover, tooltip, callback)
        }
    }
}

data class ButtonImageSet(val normal: Image, val off: Image, val hover: Image, val offHover: Image? = null) {
    companion object {
        fun select2(game: InGameState, prefix: String): ButtonImageSet {
            val normal = game.getImg("${prefix}_on.png")
            val off = game.getImg("${prefix}_off.png")
            val hover = game.getImg("${prefix}_select2.png")
            return ButtonImageSet(normal, off, hover)
        }

        fun selected(game: InGameState, prefix: String, withOffHover: Boolean = false): ButtonImageSet {
            val normal = game.getImg("${prefix}_on.png")
            val off = game.getImg("${prefix}_off.png")
            val hover = game.getImg("${prefix}_selected.png")
            var offHover: Image? = null
            if (withOffHover)
                offHover = game.getImg("${prefix}_off_selected.png")
            return ButtonImageSet(normal, off, hover, offHover)
        }

        fun static(game: InGameState, path: String): ButtonImageSet {
            val image = game.getImg(path)
            return ButtonImageSet(image, image, image)
        }
    }

    /**
     * Pick the image matching the disabled/hovered/normal state
     * of the given button.
     */
    fun pick(button: Button): Image {
        return when {
            button.disabled -> off
            button.hovered -> hover
            else -> normal
        }
    }
}

object Buttons {
    fun drawRounded(g: Graphics, x: Int, y: Int, width: Int, height: Int, radius: Int) {
        // Note: this loop's range is inclusive, and will run for both 0 and radius.
        for (i in 0..radius) {
            // Draw a bunch of overlapping rectangles, making a diagonal corner.
            g.fillRect(x.f + i, y.f + radius - i, width.f - i * 2, height.f - (radius - i) * 2)
        }
    }

    class JumpButton(pos: IPoint, val ship: Ship, game: InGameState, private val callback: () -> Unit) :
        Button(game, pos, ConstPoint(74, 29)) {

        private val font = game.getFont("HL2", 2f)
        private val labelFont = game.getFont("HL1", 1f)
        private val jumpStatusFont = game.getFont("HL2", 1f)

        private val outOfFuel: Boolean get() = ship.fuelCount == 0 && game.getEnemyOf(ship) != null
        override val disabled: Boolean get() = !ship.isFtlReady || !ship.canChargeFTL || outOfFuel

        // The progress of the slide-in/slide-out jump unavailable pullout.
        private var pulloutPos: Float = 0f

        // Color fading timer for charge/ready text.
        private var fadeTime: Float = 0f

        private var firstUpdate = true

        private val tooltip = HotkeyDelayedTooltip(game, "jump_button")

        override fun draw(g: Graphics) {
            val ftlX = pos.x + 6
            val ftlY = pos.y + 4

            val canCharge = ship.canChargeFTL

            // Animate the jump unavailable/not charging panel
            var delta = game.renderingDeltaTime / 0.1f * when {
                canCharge -> -1f
                else -> 1f
            }

            // Set the initial values to stop the panel from flickering when
            // the buttons are repopulated.
            if (firstUpdate) {
                firstUpdate = false
                delta = delta.sign
            }

            pulloutPos = (pulloutPos + delta).coerceIn(0f..1f)

            // Draw the pullout under the main button
            fun drawPullout() {
                val offset = (35 * (1 - pulloutPos)).roundToInt()
                val pulloutY = pos.y + 41 - offset
                game.getImg("img/buttons/FTL/FTL_pullout.png").draw(pos.x + 11 - 7, pulloutY - 7)

                val hasPilot = ship.isAutoScout || ship.friendlyCrew.any { it.room == ship.piloting!!.room }
                val pilotPath = when (ship.piloting!!.undamagedEnergy > 0 && hasPilot) {
                    true -> "img/buttons/FTL/FTL_pilot_on.png"
                    false -> "img/buttons/FTL/FTL_pilot_off1.png"
                }

                val enginesPath = when (ship.engines!!.powerSelected >= 1) {
                    true -> "img/buttons/FTL/FTL_engine_on.png"
                    false -> "img/buttons/FTL/FTL_engine_off1.png"
                }

                game.getImg(pilotPath).draw(pos.x + 18, pulloutY + 10)
                game.getImg(enginesPath).draw(pos.x + 45, pulloutY + 10)
            }

            // Use a stencil to prevent the pullout from being visible while it's animating,
            // since the button frame image is transparent.
            if (pulloutPos == 1f) {
                drawPullout()
            } else if (pulloutPos != 0f) {
                Utils.drawStenciled(Utils.StencilMode.BLOCKING, {
                    // There's a 6px gap between the bottom of the button and the white outline around it,
                    // and pos refers to the corner of the image not the button.
                    g.fillRect(pos.x, pos.y, size.x + 6 * 2, size.y + 6 * 2)
                }) {
                    drawPullout()
                }
            }

            game.getImg("img/buttons/FTL/FTL_base.png").draw(pos.x - 7, pos.y - 7)

            if (outOfFuel) {
                // If the player is in combat without any fuel, their jump button is disabled.
                g.colour = Constants.WARNING_COLOUR_RED
                drawRounded(g, pos.x + 5, pos.y + 6, size.x, size.y, 3)

                val jumpText = game.translator["button_jump"]
                font.drawStringCentred(pos.x.f, pos.y + 26f, 84f, jumpText, Constants.JUMP_DISABLED_TEXT)

                g.colour = Constants.JUMP_DISABLED_TEXT
                g.fillRect(pos.x + 7, pos.y + 19, 70, 2)
            } else if (ship.isFtlCharged) {
                g.colour = if (canCharge) Constants.JUMP_READY else Constants.JUMP_DISABLED
                drawRounded(g, pos.x + 5, pos.y + 6, size.x, size.y, 3)

                val textColour = when {
                    !canCharge -> Constants.JUMP_DISABLED_TEXT
                    hovered -> Constants.JUMP_READY_TEXT_HOVER
                    else -> Constants.JUMP_READY_TEXT
                }
                val jumpText = game.translator["button_jump"]
                font.drawStringCentred(pos.x.f, pos.y + 26f, 84f, jumpText, textColour)
            } else {
                val suffix = if (canCharge) "" else "_off"
                val width = (ship.ftlChargeProgress * 74).toInt().coerceAtMost(74)
                game.getImg("img/buttons/FTL/FTL_loadingbars$suffix.png").drawSection(ftlX - 1, ftlY + 2, width, 29)
            }

            // Draw the 'FTL Drive' label
            labelFont.drawStringCentred(
                pos.x.f, pos.y - 3f, 84f,
                game.translator["ftl_drive"],
                Constants.SECTOR_CUTOUT_TEXT
            )

            // Draw the jump status text below the 'FTL Drive' button
            if (canCharge || ship.isFtlReady) {
                val statusText = if (ship.isFtlReady) game.translator["ftl_ready"] else game.translator["ftl_charging"]
                val statusTextColor =
                    Colour.lerp(Constants.SECTOR_CUTOUT_TEXT, Constants.JUMP_READY, fadingCycle(1.5f, 0f, 1f))
                jumpStatusFont.drawStringCentred(
                    pos.x.toFloat(), pos.y + 51f, 84f,
                    statusText,
                    statusTextColor
                )
            }
            fadeTime += game.renderingDeltaTime

            if (hovered) {
                g.tooltip = tooltip
            }
        }

        private fun fadingCycle(period: Float, min: Float, max: Float): Float {
            val progress = fadeTime.rem(period) / period
            val changing = when {
                progress < 0.5f -> progress * 2
                else -> 2 - progress * 2
            }

            return min + changing * (max - min)
        }

        override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON) return
            if (disabled) return

            callback()
        }

        // Apply an offset to make the hoverable area the big yellow centre region - by default the hoverable
        // section starts at the button's 0,0, and the main region is offset. Thus translate the mouse coordinates
        // back so 0,0 becomes the origin of the yellow area.
        override fun contains(x: Int, y: Int) = super.contains(x - 5, y - 7)
    }

    open class BasicButton(
        game: InGameState, pos: IPoint, size: IPoint, val label: String,
        private val radius: Int, private val font: SILFontLoader, private val yOffset: Int,
        private val cb: () -> Unit
    ) : Button(game, pos, size) {

        override fun draw(g: Graphics) {
            g.colour = when {
                disabled -> Constants.JUMP_DISABLED
                hovered -> Constants.UI_BUTTON_HOVER
                else -> Constants.SECTOR_CUTOUT_TEXT
            }
            drawRounded(g, pos.x, pos.y, size.x, size.y, radius)

            val x = (size.x - font.getWidth(label)) / 2f
            font.drawString(pos.x + x, pos.y + yOffset.f, label, Constants.JUMP_DISABLED_TEXT)
        }

        override fun click(button: Int) {
            if (button == Input.MOUSE_LEFT_BUTTON)
                cb()
        }
    }

    class ShipButton(pos: IPoint, game: InGameState, private val cb: () -> Unit) :
        Button(game, pos, ConstPoint(60, 41)) {
        private val imgPos = pos - ConstPoint(7, 7)

        private val imgOff = game.getImg("img/statusUI/top_ship_off.png")
        private val imgOn = game.getImg("img/statusUI/top_ship_on.png")
        private val imgHighlight = game.getImg("img/statusUI/top_ship_select2.png")

        override val disabled: Boolean get() = game.isInDanger

        private val tooltip = HotkeyDelayedTooltip(game, "ship_info")

        override fun draw(g: Graphics) {
            val img = when {
                disabled -> imgOff
                hovered -> imgHighlight
                else -> imgOn
            }

            img.draw(imgPos)

            if (rawHovered) {
                g.tooltip = tooltip
            }
        }

        override fun click(button: Int) {
            if (button == Input.MOUSE_LEFT_BUTTON)
                cb()
        }
    }

    class StoreButton(pos: IPoint, game: InGameState, private val callback: () -> Unit) :
        Button(game, pos, ConstPoint(88, 41)) {

        private val imgPos = pos - ConstPoint(7, 7)

        private val imgBase = game.getImg("img/statusUI/top_store_base.png")

        private val font = game.getFont("HL2", 2f)

        private val tooltip = HotkeyDelayedTooltip(game, "open_store")

        override fun draw(g: Graphics) {
            imgBase.draw(imgPos)

            // Change the colour if the button is hovered. Since there's only
            // one image, draw a rounded box over it - this is how vanilla
            // FTL does it.
            if (hovered) {
                g.colour = Constants.UI_BUTTON_HOVER
                drawRounded(g, imgPos.x + 12, imgPos.y + 12, 78, 31, 3)
                g.tooltip = tooltip
            }

            font.drawString(pos.x + 11f, pos.y + 26f, "STORE", Constants.JUMP_DISABLED_TEXT)
        }

        override fun click(button: Int) {
            if (button == Input.MOUSE_LEFT_BUTTON)
                callback()
        }
    }

    abstract class BlueprintButton(
        pos: IPoint, size: IPoint, game: InGameState,
        private val defaultImage: ButtonImageSet?
    ) :
        Button(game, pos, size) {

        constructor(pos: IPoint, game: InGameState, image: ButtonImageSet) :
                this(pos, image.normal.imageSize, game, image)

        open val image: ButtonImageSet
            get() = defaultImage ?: error("BlueprintButton: no background image, image not overridden!")

        private val systemNameFont = game.getFont("c&c", 2f)
        private val weaponNameFont = game.getFont("JustinFont8")

        abstract val blueprint: Blueprint?

        /**
         * Set to true if the button is disabled for some reason other than
         * not having an associated blueprint - for example, you can't buy
         * more weapons when your weapons and cargo bay are full.
         */
        open val customDisabled: Boolean get() = false

        open val empty: Boolean get() = blueprint == null

        override val disabled: Boolean get() = empty || customDisabled

        protected val textColour: Colour
            get() = when {
                disabled -> Constants.SECTOR_CUTOUT_TEXT
                hovered -> Constants.STORE_BUY_HOVER
                else -> Constants.SECTOR_CUTOUT_TEXT
            }

        override fun draw(g: Graphics) {
            val image = when {
                disabled -> image.off
                hovered -> image.hover
                else -> image.normal
            }
            image.draw(pos)

            // Stop Kotlin from complaining the blueprint could change
            val blueprint = this.blueprint ?: return

            when (blueprint) {
                is SystemBlueprint -> {
                    systemNameFont.drawString(
                        pos.x + 48f,
                        pos.y + 26f,
                        blueprint.translateTitle(game),
                        textColour
                    )

                    val icon = game.getImg(blueprint.onIconPath)
                    icon.draw(
                        pos.x - SystemBlueprint.ICON_GLOW + 6f,
                        pos.y - SystemBlueprint.ICON_GLOW + 7f
                    )
                }

                is AbstractWeaponBlueprint -> {
                    // Draw the weapon name
                    val name = blueprint.translateShort(game)
                    val nameWindowWidth = 96
                    val nameX = (nameWindowWidth - weaponNameFont.getWidth(name)) / 2

                    weaponNameFont.drawString(
                        pos.x + 11f + nameX,
                        pos.y + 70f,
                        name,
                        textColour
                    )

                    // Draw the weapon icon
                    val iconWindowWidth = 96
                    val iconWindowHeight = 45
                    val icon = blueprint.getLauncher(game).getChargedImage(game)

                    // The sprite is rotated 90°, so swap the width and height.
                    val iconX = (iconWindowWidth - icon.height) / 2
                    val iconY = (iconWindowHeight - icon.width) / 2

                    blueprint.drawLauncherUI(game, g, pos.x + iconX + 11f, pos.y + iconY + 11f)
                }

                is DroneBlueprint -> {
                    // Draw the drone name
                    val name = blueprint.translateShort(game)
                    val nameWindowWidth = 96
                    val nameX = (nameWindowWidth - weaponNameFont.getWidth(name)) / 2

                    weaponNameFont.drawString(
                        pos.x + 11f + nameX,
                        pos.y + 70f,
                        name,
                        textColour
                    )

                    // Draw the drone icon
                    blueprint.drawIconUI(game, pos + ConstPoint(60, 35))
                }

                else -> throw Exception("Can't draw blueprint button for $blueprint")
            }
        }

        // Leave click for child classes to override.
    }

    open class DragDropBlueprintButton(
        homePos: IPoint, game: InGameState,
        image: ButtonImageSet?, size: IPoint,
        val compatible: (Blueprint) -> Boolean,
        override val blueprint: Blueprint?, val callback: () -> Unit
    ) : BlueprintButton(homePos, size, game, image) {

        constructor(
            homePos: IPoint, game: InGameState, image: ButtonImageSet,
            compatible: (Blueprint) -> Boolean,
            blueprint: Blueprint?, callback: () -> Unit
        ) : this(homePos, game, image, image.normal.imageSize, compatible, blueprint, callback)

        override val makesHoverNoise: Boolean get() = false

        private val overlay = game.getImg("img/upgradeUI/Equipment/box_overlay_red.png")

        // Null means we're not dragging, non-null indicates the cursor position
        var dragPosition: IPoint? = null

        // If a blueprint is being dragged from another blueprint, this is set to
        // highlight which cells it can and can't be dropped into (this is for
        // drones and weapons).
        var currentlyDraggedBlueprint: Blueprint? = null

        override val disabled: Boolean
            get() {
                // Un-disable the button when a blueprint is being dragged.
                // Otherwise, hovered will always be false and dropping won't work.
                val bp = currentlyDraggedBlueprint
                if (bp != null && compatible(bp)) {
                    return false
                }

                return super.disabled
            }

        override fun draw(g: Graphics) {
            val isCompatible: Boolean? = currentlyDraggedBlueprint?.let(compatible)

            if (dragPosition == null && !empty) {
                super.draw(g)
            } else {
                // Hide the card when the item is being dragged, but use the selected
                // image if the user is dragging an item over us.
                // This is why we also run this path if this button is empty, to get
                // the highlighting.
                val img = when {
                    hovered && isCompatible == true -> image.offHover ?: image.hover
                    else -> image.off
                }
                img.draw(pos)
            }

            val colour = when (isCompatible) {
                null -> return // Nothing being dragged
                true -> Colour(100, 255, 100, 127) // Transparent SYS_ENERGY_ACTIVE
                false -> Colour(255, 50, 50, 127) // Transparent SYS_ENERGY_BROKEN
            }
            overlay.draw(pos.x.f, pos.y.f, colour)
        }

        open fun drawDrag(g: Graphics) {
            // Stop dragPosition from changing under us (it actually won't,
            // but Kotlin doesn't know that).
            val dragPosition = dragPosition ?: return

            // Draw only the item itself being dragged, without the whole card.
            when (val blueprint = blueprint) {
                is AbstractWeaponBlueprint -> {
                    val icon = blueprint.getLauncher(game).getChargedImage(game)

                    // The sprite is rotated 90°, so swap the width and height.
                    val iconX = -icon.height / 2
                    val iconY = -icon.width / 2

                    blueprint.drawLauncherUI(game, g, dragPosition.x + iconX.f, dragPosition.y + iconY.f)
                }

                is DroneBlueprint -> {
                    blueprint.drawIconUI(game, dragPosition)
                }

                else -> throw Exception("Can't draw dragged blueprint for $blueprint")
            }
        }

        override fun click(button: Int) {
            if (button != Input.MOUSE_LEFT_BUTTON)
                return

            if (blueprint == null)
                return

            callback()
        }
    }
}

/**
 * The click-to-activate timer-based button used for cloaking, hacking and mind control.
 */
abstract class SystemPowerButton(
    game: InGameState,
    val powerHeight: Int,
    val powerPos: IPoint
) :
    Button(game, powerPos + calculatePosOffset(powerHeight), calculateSize(powerHeight)) {

    open val base = game.getImg("img/systemUI/button_cloaking${powerHeight}_base.png")
    val buttonImage = ButtonImageSet.select2(game, "img/systemUI/button_cloaking${powerHeight}")
    val timerIcon = game.getImg("img/systemUI/button_cloaking${powerHeight}_charging_on.png")

    /**
     * The time remaining on this activation, until the system goes into cooldown.
     *
     * Null if the system is not active.
     */
    abstract val timeRemaining: Float?

    /**
     * If this system was activated now, how long it would run for.
     *
     * If the system is active, this is the original value of [timeRemaining].
     */
    abstract val duration: Float

    /**
     * Use something like powerSelected==0 || isPowerLocked
     */
    abstract val isOff: Boolean

    /**
     * True if the button should show its highlight image
     * even if it's not hovered, for example while aiming
     * a hacking drone.
     */
    open val forceHighlight: Boolean get() = false

    override val disabled: Boolean get() = timeRemaining != null || isOff

    val active: Boolean get() = timeRemaining != null

    override fun draw(g: Graphics) {
        // Note all the images are the same size

        // 23px between the left-hand side (LHS) of the power button and the LHS of the cloak button background
        //  6px of padding inside the power button background image
        val imageX = powerPos.x + 23f - 6f

        // 17px between the top of the power icon and the bottom of the button background
        // 79px between the bottom of the button background and it's top
        //  7px of padding between the top of the background and the top of it's image
        val imageY = powerPos.y + 17f - 79f - 7f

        // Draw the outline image
        base.draw(imageX, imageY)

        // Draw the button itself
        val image = when {
            active -> timerIcon
            isOff -> buttonImage.off
            hovered || forceHighlight -> buttonImage.hover
            else -> buttonImage.normal
        }

        val time = timeRemaining
        val height = if (time != null) {
            // If we're cloaked, figure out how much of the image we should show.
            // The level 1,2,3,4 (there's four levels in the images) have 5,8,11,14 bars
            // The bars each represent a different amount of time, so combined
            // they represent the full cloak duration.
            val totalBars = 2 + powerHeight * 3
            val timePerBar = duration / totalBars

            // Round up, so at least one bar is always visible.
            val visibleBars = ceil(time / timePerBar).toInt()
            26 + visibleBars * 4
        } else {
            image.height
        }

        // Draw the bottom {height} pixels of the image
        val topY = image.height - height
        image.draw(
            imageX, imageY + topY, imageX + image.width, imageY + image.height,
            0f, topY.f, image.width.f, image.height.f
        )

        // Debugging aid:
        // g.colour = Colour.red
        // g.drawRect(imageX, imageY + topY, 5f, height.f)
    }

    companion object {
        private fun calculatePosOffset(power: Int): IPoint {
            // Find the position of the button relative to the system power icon
            val height = 11 + power * 12
            return ConstPoint(27, -3 - height)
        }

        private fun calculateSize(power: Int): IPoint {
            val height = 11 + power * 12
            return ConstPoint(24, height)
        }
    }
}

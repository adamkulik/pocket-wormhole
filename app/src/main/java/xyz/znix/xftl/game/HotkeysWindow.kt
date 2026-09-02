package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.pop
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.DelayedTooltip
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.replaceArg
import xyz.znix.xftl.sys.Input
import kotlin.math.max
import kotlin.math.roundToInt

class HotkeysWindow(val game: InGameState, val close: () -> Unit) : Window() {
    override val size: IPoint get() = ConstPoint(1050, 650)

    private val nameFont = game.getFont("JustinFont11Bold")
    private val titleFont = game.getFont("HL2", 2f)
    private val titleTab = game.getImg("img/map/side_beaconmap.png")

    private var scroll: Float = 0f
    private var maxY: Int = size.y
    private var offsetY: Int = 0
    private val mousePos = Point(0, 0)

    private val multiColumn: List<HotkeyGroup>
    private val leftColumn: List<HotkeyGroup>
    private val rightColumn: List<HotkeyGroup>

    private val contentWidth = -PADDING + size.x - SCROLL_BAR_GAP - SCROLL_BAR_WIDTH - PADDING

    private val conflicts = HashMap<Hotkey, List<Hotkey>>()

    private val tooltip = HotkeyTooltip()

    private var currentlyBinding: Boolean = false
    private var hovered: Hotkey? = null
    private var foundHover: Boolean = false

    init {
        val baseList = game.hotkeyManager.groups

        // We'll show the multi-column groups at the top, and below that build
        // two columns which are (as far as possible) equal in height.
        multiColumn = baseList.filter { it.columns > 1 }

        // Split the columns into two groups, ignoring their position in the XML file
        val byCount = baseList
            .filter { it.columns == 1 }
            .sortedBy { it.hotkeys.size }
        val remaining = ArrayList(byCount)

        val unsortedLeft = ArrayList<HotkeyGroup>()
        val unsortedRight = ArrayList<HotkeyGroup>()
        var leftCount = 0
        var rightCount = 0

        while (remaining.isNotEmpty()) {
            val entry = remaining.pop()
            if (leftCount <= rightCount) {
                unsortedLeft += entry
                leftCount += entry.hotkeys.size
            } else {
                unsortedRight += entry
                rightCount += entry.hotkeys.size
            }
        }

        // As far as possible, sort the columns back into their original
        // order in the XML.
        val groupToIndex = baseList.withIndex().associate { (idx, group) -> Pair(group, idx) }
        leftColumn = unsortedLeft.sortedBy { groupToIndex.getValue(it) }
        rightColumn = unsortedRight.sortedBy { groupToIndex.getValue(it) }

        updateBindCache()
    }

    /**
     * Update our cache of which key each hotkey is bound to.
     */
    private fun updateBindCache() {
        game.updateHotkeyBindings()

        // Calculate the conflicting keys (those where multiple
        // hotkeys map to the same physical button)
        val boundTo = HashMap<HotkeyButton, ArrayList<Hotkey>>()
        for ((action, button) in game.reverseHotkeyBindings) {
            boundTo.computeIfAbsent(button) { ArrayList() }.add(action)
        }

        conflicts.clear()
        for (actions in boundTo.values) {
            if (actions.size <= 1)
                continue

            for (action in actions) {
                conflicts[action] = actions
            }
        }
    }

    override fun draw(g: Graphics) {
        val titleText = game.translator["configure_controls_tab"]

        val startWidth = 33
        val endWidth = 45
        val textWidth = titleFont.getWidth(titleText)
        val tabWidth = startWidth + textWidth + endWidth

        val intScroll = scroll.roundToInt()

        game.windowRenderer.renderMasked(position.x, position.y, size.x, size.y, 0, intScroll, {
            g.colour = Colour.red // Anything non-transparent will do
            g.fillRect(position.x - 7, position.y - 7, tabWidth, titleTab.height)
        }, {
            g.pushTransform()
            offsetY = position.y + intScroll + 30
            g.translate(position.x.f, offsetY.f)
            drawContent(g)
            g.popTransform()

            drawScrollBar(g)
        })

        UIUtils.drawTab(
            titleFont,
            titleText,
            titleTab,
            position.x - 7f,
            position.y - 7f,
            startWidth.f,
            endWidth.f
        )
        titleFont.drawString(position.x.f - 7f + startWidth, position.y + 22f, titleText, Constants.JUMP_DISABLED_TEXT)
    }

    private fun drawContent(g: Graphics) {
        // This is used to check whether a user has moused off something, and
        // thus set hovered=null. We can't just set hovered=null here, since
        // hotkeys react when you hover over another hotkey they conflict with.
        foundHover = false

        var y = 30

        for (group in multiColumn) {
            y = drawGroup(g, group, y, Layout.BOTH)
        }

        var leftColY = y
        var rightColY = y

        // Draw the side-by-side columns
        for (group in leftColumn) {
            leftColY = drawGroup(g, group, leftColY, Layout.LEFT)
        }
        for (group in rightColumn) {
            rightColY = drawGroup(g, group, rightColY, Layout.RIGHT)
        }

        // This is used to set the scrollbar length.
        maxY = max(leftColY, rightColY)

        // If the user moused off a hotkey, unmark it as the hovered item.
        if (!foundHover) {
            hovered = null
        }
    }

    private fun drawScrollBar(g: Graphics) {
        val areaHeight = size.y - PADDING * 2
        val barHeight = (areaHeight * size.y.f / maxY).roundToInt()
        val barY = (areaHeight * -scroll / maxY).roundToInt()

        // The box showing the area the scroll bar can occupy
        g.colour = Constants.REWARDS_BACKGROUND
        g.fillRect(
            position.x + size.x - PADDING - SCROLL_BAR_WIDTH, position.y + PADDING,
            SCROLL_BAR_WIDTH, areaHeight
        )

        // The scroll bar itself
        g.colour = Constants.SECTOR_CUTOUT_TEXT
        g.fillRect(
            position.x + size.x - PADDING - SCROLL_BAR_WIDTH, position.y + PADDING + barY,
            SCROLL_BAR_WIDTH, barHeight
        )
    }

    private fun drawGroup(g: Graphics, group: HotkeyGroup, startY: Int, layout: Layout): Int {
        var y = startY

        val hotkeySpacing = 32

        // Sets the colour of the box the text sits on
        g.colour = Constants.SECTOR_CUTOUT_TEXT

        val titleSplitX = calcBoxX(0, 2) + KEY_BOX_WIDTH + PADDING

        when (layout) {
            Layout.BOTH -> {
                Buttons.drawRounded(g, PADDING, y - 17, contentWidth, 22, 4)
                titleFont.drawStringCentred(
                    PADDING.f, y.f, contentWidth.f,
                    game.translator[group.name],
                    Constants.JUMP_DISABLED_TEXT
                )
            }

            Layout.LEFT -> {
                Buttons.drawRounded(g, PADDING, y - 17, titleSplitX - PADDING * 2, 22, 4)
                titleFont.drawStringCentred(
                    PADDING.f, y.f, contentWidth / 2f,
                    game.translator[group.name],
                    Constants.JUMP_DISABLED_TEXT
                )
            }

            Layout.RIGHT -> {
                Buttons.drawRounded(
                    g,
                    titleSplitX + PADDING, y - 17,
                    contentWidth - titleSplitX, 22,
                    4
                )
                titleFont.drawStringCentred(
                    size.x / 2f, y.f, size.x / 2f,
                    game.translator[group.name],
                    Constants.JUMP_DISABLED_TEXT
                )
            }
        }

        y += 30

        var nextColumn = 0

        for (key in group.hotkeys) {
            if (layout != Layout.BOTH) {
                val colX = calcBoxX(if (layout == Layout.LEFT) 0 else 1, 2)
                drawHotkey(g, key, y, colX)
                y += hotkeySpacing
                continue
            }

            val colX = calcBoxX(nextColumn, group.columns)

            if (nextColumn == 0) {
                // Every time we start a new row, advance the Y
                drawHotkey(g, key, y, colX)
                y += hotkeySpacing
            } else {
                // Otherwise we're adding to an exising row
                drawHotkey(g, key, y - hotkeySpacing, colX)
            }

            nextColumn = (nextColumn + 1) % group.columns
        }

        return y + 30
    }

    private fun drawHotkey(g: Graphics, key: Hotkey, y: Int, boxX: Int) {
        val middleOfText = y - nameFont.baselineToTop / 2
        val boxY = middleOfText - KEY_BOX_HEIGHT / 2

        // If currentlyBinding is set (we've clicked on a key) then only highlight
        // the box the user had clicked on.
        val mouseX = mousePos.x - position.x
        val mouseY = mousePos.y - offsetY
        val hover = when (currentlyBinding) {
            true -> hovered == key
            false -> mouseX in boxX..boxX + KEY_BOX_WIDTH && mouseY in boxY..boxY + KEY_BOX_HEIGHT
        }

        val otherRebinding = currentlyBinding && !hover

        val hasConflict = conflicts.containsKey(key)
        val conflictsWithHover = hovered?.let { conflicts[it] }?.contains(key) == true

        val mainColour = when {
            hover -> Constants.UI_BUTTON_HOVER
            conflictsWithHover -> Constants.WEAPONS_ITEM_TARGETING
            otherRebinding -> Constants.WEAPONS_ITEM_DESELECTED
            hasConflict -> Constants.WEAPONS_ITEM_TARGETING
            else -> Colour.white
        }
        val textColour = when {
            conflictsWithHover -> Constants.WEAPONS_ITEM_TARGETING
            otherRebinding -> Constants.WEAPONS_ITEM_DESELECTED
            else -> Constants.SECTOR_CUTOUT_TEXT
        }

        nameFont.drawStringLeftAligned(
            boxX - 15f,
            y.f,
            game.translator[key.name],
            textColour
        )

        g.colour = Constants.REWARDS_BACKGROUND
        g.fillRect(boxX, boxY, KEY_BOX_WIDTH, KEY_BOX_HEIGHT)
        g.colour = mainColour
        g.drawRect(boxX, boxY, KEY_BOX_WIDTH - 1, KEY_BOX_HEIGHT - 1)
        g.drawRect(boxX + 1, boxY + 1, KEY_BOX_WIDTH - 3, KEY_BOX_HEIGHT - 3)

        val keyName = game.reverseHotkeyBindings[key]?.text?.let { game.translator[it] } ?: "..."
        nameFont.drawStringCentred(boxX.f, boxY + 17f, KEY_BOX_WIDTH.f, keyName, mainColour)

        if (hover) {
            hovered = key
            foundHover = true
            g.tooltip = tooltip
        }
    }

    private fun calcBoxX(column: Int, count: Int): Int {
        val rightEdgeX = PADDING + contentWidth * (column + 1) / count
        return rightEdgeX - KEY_BOX_WIDTH
    }

    override fun escapePressed() {
        close()
    }

    override fun mouseScroll(change: Int) {
        scroll += change / 3f

        // Can't scroll too far down
        val maxDownScroll = size.y - maxY
        if (scroll < maxDownScroll) {
            scroll = maxDownScroll.f
        }

        // Can't scroll too far up
        if (scroll > 0f) {
            scroll = 0f
        }
    }

    override fun updateUI(x: Int, y: Int) {
        mousePos.set(x, y)
        super.updateUI(x, y)
    }

    override fun mouseClick(button: Int, x: Int, y: Int) {
        // If we're rebinding a key, clicking anywhere cancels
        if (currentlyBinding) {
            currentlyBinding = false
            return
        }

        super.mouseClick(button, x, y)

        // Start rebinding a key?
        if (button == Input.MOUSE_LEFT_BUTTON && hovered != null) {
            currentlyBinding = true
        }
    }

    override fun onTextInput(key: Int, c: Char): Boolean {
        if (!currentlyBinding)
            return false
        val hotkey = hovered ?: return false

        // Check if we know about this key
        val button = HotkeyButton.BY_KEY_ID[key] ?: return false

        // Escape un-binds a key
        when (key) {
            Input.KEY_ESCAPE -> game.mainGame.profile.unbindKey(hovered!!)
            else -> game.mainGame.profile.setKeybind(hotkey, button)
        }
        currentlyBinding = false
        updateBindCache()
        return true
    }

    private inner class HotkeyTooltip : DelayedTooltip(game) {
        override fun getText(): String {
            val key = hovered ?: return ""

            var text = when (val default = key.default) {
                null -> game.translator["xftl_hotkeys_default_tooltip_none"]
                else -> game.translator["xftl_hotkeys_default_tooltip"].replaceArg(game.translator[default.text])
            }

            // List the conflicts for this key
            val conflicts = conflicts[key]
            if (conflicts != null) {
                text += "\n" + game.translator["xftl_hotkeys_conflicts_tooltip"]
                for (other in conflicts) {
                    if (key == other)
                        continue

                    text += "\n-" + game.translator[other.name]
                }
            }

            return text
        }
    }

    companion object {
        private const val KEY_BOX_WIDTH = 120
        private const val KEY_BOX_HEIGHT = 22
        private const val PADDING = 15
        private const val SCROLL_BAR_WIDTH = 4
        private const val SCROLL_BAR_GAP = 10
    }

    /**
     * Groups can either be on the left or right side, or mixed between them.
     */
    private enum class Layout {
        LEFT,
        RIGHT,
        BOTH;
    }
}

package xyz.znix.xftl.hangar

import xyz.znix.xftl.f
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

interface EditorMenu {
    fun keyReleased(key: Int, c: Char) {}
    fun mouseMoved(x: Int, y: Int) {}
    fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {}
    fun mouseWheelMoved(change: Int) {}
    fun draw(g: Graphics)
}

class PopupMenu(val editor: ShipEditor, val pos: IPoint, val entries: List<Entry>) : EditorMenu {
    private val root = SingleMenu(pos, -1, entries)

    private val menus = ArrayList<SingleMenu>()

    init {
        menus += root
    }

    override fun draw(g: Graphics) {
        for (menu in menus) {
            menu.draw(g)
        }
    }

    override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {
        for (menu in menus) {
            val selectedIdx = menu.getItemIndex(x, y)
            val entry = selectedIdx?.let { menu.entries[it] }

            if (entry != null) {
                if (entry.onClick != null) {
                    entry.onClick.invoke()
                    editor.closeMenu(this)
                }
                return
            }
        }

        editor.closeMenu(this)
    }

    override fun mouseMoved(x: Int, y: Int) {
        for (i in 0 until menus.size) {
            val menu = menus[i]
            val hovering = menu.getItemIndex(x, y)

            val isLastMenu = i == menus.size - 1

            menu.hovered = hovering != null
            if (hovering != null) {
                menu.selected = hovering

                // Close the submenus, if applicable
                if (!isLastMenu && hovering != menus[i + 1].parentIndex) {
                    while (menus.size > i + 1) {
                        menus.removeAt(menus.size - 1)
                    }
                    return
                }

                // Open a new submenu, if applicable
                val entry = menu.entries[hovering]
                if (isLastMenu && entry.children != null) {
                    val newPos = ConstPoint(menu.pos.x + menu.width, menu.getItemY(hovering))
                    menus += SingleMenu(newPos, hovering, entry.children)
                }
            }
        }
    }

    companion object {
        const val MARGIN = 5
        const val ENTRY_HEIGHT = 15
    }

    class Entry(
        val text: String,

        val children: List<Entry>? = null,

        /**
         * Run whenever this item is clicked on.
         */
        val onClick: (() -> Unit)?
    )

    /**
     * A single list of items, which due to child entries can be one of many in a popup menu.
     */
    private inner class SingleMenu(val pos: IPoint, val parentIndex: Int, val entries: List<Entry>) {
        val width = entries.map {
            var base = editor.font.getWidth(it.text)
            if (it.children != null) {
                base += 8
            }
            return@map base
        }.max() + 2 * MARGIN

        val height = ENTRY_HEIGHT * entries.size

        var selected: Int? = null
        var hovered: Boolean = false

        fun draw(g: Graphics) {
            g.colour = Colour.darkGray
            g.fillRect(pos.x.f, pos.y.f, width.f, height.f)
            g.colour = Colour.white
            g.drawRect(pos.x.f, pos.y.f, width.f, height.f)

            for ((index, entry) in entries.withIndex()) {
                val y = getItemY(index)

                // Items light up either when you're hovering over them, or their child menu is open.
                if (index == selected && (hovered || entry.children != null)) {
                    g.colour = Colour.gray
                    g.fillRect(pos.x + 1f, y + 1f, width - 1f, ENTRY_HEIGHT - 1f)
                }

                editor.font.drawString(
                    pos.x.f + MARGIN,
                    y + 11f, entry.text,
                    Colour.white
                )

                if (entry.children != null) {
                    g.colour = Colour.white

                    // Draw a triangle to indicate the sub-list
                    val leftX = pos.x + width - 7
                    val rightX = pos.x + width - 2
                    val middleY = y + ENTRY_HEIGHT / 2
                    g.drawLine(
                        leftX.f, y + 2f,
                        rightX.f, middleY.f
                    )
                    g.drawLine(
                        rightX.f, middleY.f,
                        leftX.f, y + ENTRY_HEIGHT - 2f
                    )
                }
            }
        }

        fun getItemY(index: Int): Int = pos.y + index * ENTRY_HEIGHT

        fun getItemIndex(x: Int, y: Int): Int? {
            // Update the index of the hovered menu item
            if (x !in pos.x..pos.x + width || y < pos.y) {
                return null
            }

            val index = (y - pos.y) / ENTRY_HEIGHT
            if (index >= entries.size) {
                return null
            }

            return index
        }
    }
}

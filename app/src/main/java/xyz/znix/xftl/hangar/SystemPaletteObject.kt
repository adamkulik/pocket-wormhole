package xyz.znix.xftl.hangar

import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.systems.Artillery

/**
 * This is a palette containing all the systems not present on the ship.
 */
class SystemPaletteObject(val editor: ShipEditor) : UIObject {
    override val selectPriority: Int get() = -1

    val systems = ArrayList<SystemObject>()

    val width = 180
    val height = 130

    var x: Int = 0

    init {
        updateSystems()
    }

    override fun draw(g: Graphics) {
        updateSystems()

        val y = editor.editorHeight - height - 10
        val title = "SYSTEMS"
        editor.state.windowRenderer.renderWithTitleTab(g, editor.titleTab, editor.titleFont, x, y, width, height, title)

        val maxColumns = 6

        // Split the systems up into rows
        val rows = ArrayList<ArrayList<SystemObject>>()
        for ((index, system) in systems.withIndex()) {
            val row = index / maxColumns
            if (row >= rows.size)
                rows.add(ArrayList())

            rows.last().add(system)
        }

        val itemWidth = 30

        // Draw centred rows
        for ((row, rowItems) in rows.withIndex()) {
            val startX = x + (width - rowItems.size * itemWidth) / 2

            for ((column, system) in rowItems.withIndex()) {
                val leftX = startX + column * itemWidth
                system.centreX = leftX + itemWidth / 2
                system.centreY = y + 47 + row * 30
            }
        }
    }

    override fun canSelectFrom(mouseX: Int, mouseY: Int): Boolean {
        return false
    }

    fun updateSystems() {
        val installedSystems = editor.ship.rooms
            .mapNotNull { it.system?.getBP(editor.state) }
            .toHashSet()

        // Always include artillery systems, since you can have multiple of those.
        installedSystems.removeIf { it.info == Artillery.INFO }

        val systemsInPalette = systems.map { it.systemType }.toSet()
        val nonInstalledSystems = editor.allSystems.toHashSet()
        nonInstalledSystems.removeAll(installedSystems)

        // Check if we're up-to-date.
        if (!systemsInPalette.any(installedSystems::contains) && systemsInPalette.containsAll(nonInstalledSystems)) {
            return
        }

        systems.clear()

        for (system in nonInstalledSystems) {
            val sys = EditableSystem(system.name, startingPower = system.startPower)
            systems += SystemObject(editor, null, 0, 0, sys)
        }

        // It doesn't really matter what order we use, just make sure it's
        // not changing due to the order coming from a set.
        systems.sortBy { it.systemType.name }
    }
}

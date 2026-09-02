package xyz.znix.xftl.devutil

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Achievement
import xyz.znix.xftl.game.ButtonImageSet
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * An auto-completion engine for the debug console.
 */
abstract class AutoCompleter(val console: DebugConsole) : DebugConsole.Overlay {
    abstract fun update(currentToken: String, positionInInput: Int)
    abstract fun applyAutoCompletion()
}

abstract class BasicCompletionEngine<T>(console: DebugConsole, val owner: ArgumentTypeProcessor?) :
    AutoCompleter(console) {

    abstract val items: List<T>

    // A little caching for the search
    private var lastInput: String? = null
    private val sortedEntries = ArrayList<T>()
    private var lastLeftClick = false
    private var positionInInput: Int = 0

    protected abstract fun getItemName(item: T): String
    protected open fun getCompletionString(item: T): String = getItemName(item)

    override val autoCompleteSuggestion: String? get() = bestSuggestion?.let { priorCommandText + getItemName(it) }
    protected val bestSuggestion: T? get() = sortedEntries.firstOrNull()
    protected val priorCommandText: String
        get() {
            if (console.input.length <= positionInInput)
                return ""
            return console.input.substring(0, positionInInput)
        }

    override fun render(gc: GameContainer, g: Graphics, height: Float) {
        val mouseX = gc.input.mouseX
        val mouseY = gc.input.mouseY

        val leftDown = gc.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)
        val clicking = leftDown && !lastLeftClick
        lastLeftClick = leftDown

        val x = 30
        val blockHeight = 15
        val width = gc.width - x - 40

        for ((i, item) in sortedEntries.withIndex()) {
            val y = height.toInt() + 10 + i * blockHeight

            if (y > gc.height)
                break

            val hovering = mouseX in x until x + width && mouseY in y until y + blockHeight

            val shade = if (hovering) 140 else 100
            g.colour = Colour(shade, shade, shade, 180)
            g.fillRect(x.f, y.f, width.f, blockHeight.f)

            console.font.drawString(x + 5f, y + 10f, getItemName(item), Colour.white)

            if (clicking && hovering) {
                itemSelected(item)
            }
        }
    }

    override fun update(currentToken: String, positionInInput: Int) {
        this.positionInInput = positionInInput

        // Only update when something changes
        if (currentToken == lastInput)
            return
        lastInput = currentToken

        sortedEntries.clear()
        sortedEntries.addAll(items)

        if (currentToken.isBlank()) {
            // If there's no search term, leave the items in their original order.
            itemUpdateFinished(sortedEntries)
            return
        }

        sortEntries(sortedEntries, currentToken)

        itemUpdateFinished(sortedEntries)
    }

    protected open fun sortEntries(entries: ArrayList<T>, currentToken: String) {
        // Display all the entries - sorting only orders them
        val searcher = DebugConsole.FuzzySearcher(currentToken)
        sortedEntries.sortByDescending { searcher.rank(getItemName(it)) }
    }

    protected open fun itemUpdateFinished(sortedEntries: List<T>) = Unit

    protected open fun itemSelected(item: T) {
        console.input = console.input.substring(0, positionInInput) + getCompletionString(item) + " "
    }

    override fun applyAutoCompletion() {
        val first = sortedEntries.firstOrNull() ?: return
        itemSelected(first)
    }
}

class CommandCompleter(console: DebugConsole) : BasicCompletionEngine<DebugConsole.Cmd>(console, null) {

    override val items: List<DebugConsole.Cmd> get() = console.commands

    override fun getItemName(item: DebugConsole.Cmd): String {
        return item.name
    }

    override fun sortEntries(entries: ArrayList<DebugConsole.Cmd>, currentToken: String) {
        // Use fuzzy-search as a backup
        super.sortEntries(entries, currentToken)

        // Pull all the prefix matches to the start
        val matches = entries.filter { it.name.startsWith(currentToken) }.toList()

        // Order the matching commands by name, so they're stable
        matches.sortedBy { it.name }

        // Move them to the front
        entries.removeAll(matches.toSet())
        entries.addAll(0, matches)
    }
}

class BlueprintCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?, val type: Class<out Blueprint>) :
    BasicCompletionEngine<Blueprint>(console, owner) {

    private val isWeapons = AbstractWeaponBlueprint::class.java.isAssignableFrom(type)
    private val isDrones = DroneBlueprint::class.java.isAssignableFrom(type)

    private var lastLeftClick = false
    private var buttons: List<CompletionButton> = emptyList()

    override val items = console.game.blueprintManager.blueprints.values
        .filterIsInstance(Blueprint::class.java)
        .filter { type.isAssignableFrom(it.javaClass) }

    override fun getItemName(item: Blueprint): String {
        return item.translateTitle(console.game)
    }

    override fun getCompletionString(item: Blueprint): String {
        return item.name
    }

    override fun itemUpdateFinished(sortedEntries: List<Blueprint>) {
        if (isWeapons) {
            val images = ButtonImageSet.select2(console.game, "img/storeUI/store_buy_weapons")
            buttons = sortedEntries.map { CompletionButton(it, images) }
        }
        if (isDrones) {
            val images = ButtonImageSet.select2(console.game, "img/storeUI/store_buy_drones")
            buttons = sortedEntries.map { CompletionButton(it, images) }
        }
    }

    override fun render(gc: GameContainer, g: Graphics, height: Float) {
        if (isWeapons || isDrones) {
            renderButtons(gc, g, height)
        } else {
            super.render(gc, g, height)
        }
    }

    fun renderButtons(gc: GameContainer, g: Graphics, height: Float) {
        val mouseX = gc.input.mouseX
        val mouseY = gc.input.mouseY

        val leftDown = gc.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)
        val clicking = leftDown && !lastLeftClick
        lastLeftClick = leftDown

        val baseX = 10
        var x = baseX
        var y = height.toInt() + 10

        for ((i, button) in buttons.withIndex()) {
            button.windowOffset = ConstPoint(x, y)
            button.update(mouseX, mouseY)
            button.draw(g)

            if (clicking) {
                button.mouseDown(Input.MOUSE_LEFT_BUTTON, mouseX, mouseY)
            }


            // Move along to the next position
            val img = button.image.normal
            x += img.width + 5

            // Wrap around
            if (x + img.width > gc.width) {
                x = baseX
                y += img.height + 5
            }

            if (y > gc.height) {
                break
            }
        }
    }

    // Use BlueprintButton's rendering for weapons/drones
    private inner class CompletionButton(override val blueprint: Blueprint, images: ButtonImageSet) :
        Buttons.BlueprintButton(ConstPoint.ZERO, console.game, images) {

        override fun click(button: Int) {
            itemSelected(blueprint)
        }
    }
}

/**
 * An auto-completer that shows matching blueprints, along with a selection
 * of additional items.
 *
 * The blueprints are always drawn in a list, as the extra values can't cleanly
 * be shown in drone/weapon button tiles.
 */
class BlueprintAndExtrasCompleter(
    console: DebugConsole,
    owner: ArgumentTypeProcessor?,
    val type: Class<out Blueprint>,
    extras: List<String>
) :
    BasicCompletionEngine<Pair<String, Blueprint?>>(console, owner) {

    override val items = console.game.blueprintManager.blueprints.values
        .filterIsInstance(Blueprint::class.java)
        .filter { type.isAssignableFrom(it.javaClass) }
        .map { Pair(it.name, it) } + extras.map { Pair(it, null) }

    override fun getItemName(item: Pair<String, Blueprint?>): String {
        return item.second?.translateTitle(console.game) ?: item.first
    }

    override fun getCompletionString(item: Pair<String, Blueprint?>): String {
        return item.first
    }
}

class EnemyShipSpecCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?) :
    BasicCompletionEngine<EnemyShipSpec>(console, owner) {

    override val items = console.game.eventManager.getShips().toList()

    override fun getItemName(item: EnemyShipSpec): String {
        return item.name
    }
}

class AchievementCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?) :
    BasicCompletionEngine<Achievement>(console, owner) {

    override val items = console.game.content.achievements.achievements.values.sortedBy { it.id }

    override fun getItemName(item: Achievement): String {
        return console.game.translator[item.name]
    }

    override fun getCompletionString(item: Achievement): String {
        return item.id
    }
}

class ShipFamilyCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?) :
    BasicCompletionEngine<LazyShipBlueprint>(console, owner) {

    override val items = console.game.blueprintManager.blueprints.values
        .filterIsInstance(LazyShipBlueprint::class.java)
        .sortedBy { it.name } // Stable ordering
        // Only use one ship per class
        .associateBy { console.game.content.shipFamilies.byShipId[it.name] }
        .filterKeys { it != null } // Exclude the ship representing a null family
        .values
        .toList()

    override fun getItemName(item: LazyShipBlueprint): String {
        return item.shipClass?.get(console.game.translator) ?: item.name
    }

    override fun getCompletionString(item: LazyShipBlueprint): String {
        return item.name
    }
}

class EnumCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?, type: Class<*>) :
    BasicCompletionEngine<Any>(console, owner) {

    override val items = type.enumConstants.toList()

    override fun getItemName(item: Any): String {
        check(item is Enum<*>)
        return item.name
    }
}

package xyz.znix.xftl.devutil

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Constants
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.ButtonImageSet
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.*
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import java.nio.file.Path
import java.util.*
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A development console, to quickly do stuff like load events or get scrap.
 */
class DebugConsole(var game: InGameState) {
    private val history = ArrayList<String>()
    private var historyCursor: Int = -1

    var continued: ContinuedCommand? = null
    var input: String = ""

    private var completion: AutoCompleter? = null
    private val parameterHints = ArrayList<String>()
    private val overlay: Overlay? get() = continued ?: completion

    private val lines = ArrayList<ILine>()

    val font = game.getFont("c&c")

    private var flashTimer: Float = 0f

    private var lineScroll: Float = 0f

    private val maxScroll: Int get() = max(0, lines.size - 15)

    // Used for event searches - put them here so they persiste between
    // searches.
    var eventSearchIds = true
    var eventSearchMessageText = true
    var eventSearchOptions = true

    val commands: List<Cmd> = listOf(
        DebugCommands(this)
        // TODO allow modded commands
    ).flatMap { it.commands }

    private val prompt: String get() = continued?.prompt ?: PROMPT

    private val currentLine: String
        get() = if (historyCursor == -1) input
        else history[historyCursor]

    fun render(gc: GameContainer, g: Graphics) {
        val height = gc.height / 2

        g.colour = Colour(127, 127, 127, 180)
        g.fillRect(0f, 0f, gc.width.f, height.f)

        var y = height - 6

        val fontHeight = 7
        val lineSpacing = fontHeight + 5 // Some letters are a bit outside the font height

        // Draw the prompt line
        val inputWithoutCursur = prompt + currentLine
        var inputLine = inputWithoutCursur
        if (flashTimer.rem(FLASH_TIME) > FLASH_TIME / 2) {
            inputLine += "_"
        }

        // Draw the lighter auto-completion suggestion, if applicable
        // This is drawn under the main input line, so the cursor draws on top.
        var hintPos = 20f + font.getWidth(inputWithoutCursur) + 1f
        val suggestion = overlay?.autoCompleteSuggestion
        if (suggestion != null && suggestion.startsWith(currentLine)) {
            val additional = suggestion.removePrefix(currentLine)
            font.drawString(hintPos, y.f, additional, Colour.lightGray)
            hintPos += font.getWidth(additional)
        }

        font.drawString(20f, y.f, inputLine, Colour.white)

        // Draw the remaining (eg parameter name) hints
        for (hint in parameterHints) {
            hintPos += 10 // Add a gap from the last text
            font.drawString(hintPos, y.f, hint, Colour.lightGray)
            hintPos += font.getWidth(hint)
        }

        y -= lineSpacing + 4

        // Draw all the history lines, which can be scrolled.
        // The scroll is only stored as a float to make the scrolling smoother.
        val offset = lineScroll.roundToInt()

        for (i in lines.size - 1 - offset downTo 0) {
            val line = lines[i]
            line.draw(20, y)

            y -= lineSpacing
            if (y < 0)
                break
        }

        // Draw a warning if we're scrolled, so the player knows they're not
        // at the latest message.
        if (offset != 0) {
            val lineY = height - 6 - fontHeight - 4
            g.colour = Colour.red
            g.drawLine(0f, lineY.f, gc.width.f, lineY.f)

            font.drawStringLeftAligned(gc.width - 5f, lineY - 2f, "$offset lines scrolled past", Colour.red)
        }

        // Cut down the history so it doesn't get too crazy.
        while (lines.size > 1000) {
            lines.removeAt(0)
        }

        // Make sure we're using a suitable auto-completion engine
        updateAutoCompleter()

        overlay?.render(gc, g, height.f)
    }

    fun update(@Suppress("UNUSED_PARAMETER") gc: GameContainer, dt: Float) {
        flashTimer += dt
    }

    fun keyPressed(key: Int, c: Char) {
        // Our GLFW-based game sends characters and keycodes separately,
        // so we have to block the backtick that comes once the console
        // is opened.
        if (c == '`' && key != Input.KEY_GRAVE)
            return

        // Check if the continuation UI wants to handle the keypress
        if (overlay?.keyPressed(key, c) == true)
            return

        when (key) {
            Input.KEY_ENTER -> {
                selectHistory()
                runCommand()
            }

            Input.KEY_UP -> {
                if (historyCursor == -1) {
                    historyCursor = history.size - 1
                } else {
                    historyCursor--
                    if (historyCursor < 0)
                        historyCursor = 0
                }
            }

            Input.KEY_DOWN -> {
                if (historyCursor != -1)
                    historyCursor++
                if (historyCursor >= history.size)
                    historyCursor = -1
            }

            Input.KEY_BACK -> {
                selectHistory()
                if (input != "") {
                    input = input.substring(0, input.length - 1)
                }
            }

            // On a desktop keyboard, delete is very close to enter so it's
            // easy to press it to clear the current line.
            Input.KEY_DELETE -> {
                historyCursor = -1
                input = ""
            }

            // Triggers autocompletion
            Input.KEY_TAB -> {
                (overlay as? AutoCompleter)?.applyAutoCompletion()
            }

            Input.KEY_GRAVE -> {
                // This key opens and closes the console, so ignore it to prevent
                // it ending up in commands.
            }

            else -> {
                // Ignore characters our font doesn't support, which
                // includes any odd ASCII characters that could be
                // somehow generated.
                if (!font.supportsCharacter(c))
                    return

                selectHistory()
                input += c
            }
        }
    }

    fun mousePressed(button: Int, x: Int, y: Int) {
        overlay?.mousePressed(button, x, y)
    }

    fun mouseReleased(button: Int, x: Int, y: Int) {
        overlay?.mouseReleased(button, x, y)
    }

    fun mouseDragged(oldX: Int, oldY: Int, newX: Int, newY: Int) {
        overlay?.mouseDragged(oldX, oldY, newX, newY)
    }

    fun mouseWheelMoved(amount: Int) {
        lineScroll += amount * 0.025f
        lineScroll = lineScroll.coerceIn(0f..maxScroll.f)
    }

    /**
     * If the user has scrolled back to a previous command, copy
     * that to the buffer.
     */
    private fun selectHistory() {
        if (historyCursor != -1) {
            input = history[historyCursor]
            historyCursor = -1
        }
    }

    /**
     * Print a line of output to the bottom of the console.
     */
    fun addLine(line: String) {
        lines += SimpleLine(line)
    }

    /**
     * This adds a bunch of lines to the output, each of which
     * is split into separate parts that are vertically aligned.
     *
     * This is how we can draw a cleanly aligned help menu with
     * a variable-width font.
     */
    fun addLineGrid(gridLines: List<List<String>>, offsetX: Int = 0) {
        if (gridLines.isEmpty())
            return

        // Transpose the input lines into columns
        val columns = ArrayList<ArrayList<String>>()

        for (i in 0 until gridLines[0].size) {
            columns.add(ArrayList())
        }

        for (line in gridLines) {
            require(line.size == columns.size)
            for ((index, part) in line.withIndex()) {
                columns[index].add(part)
            }
        }

        // Find how wide each column is
        val widths = columns.map { column -> column.map { font.getWidth(it) }.max() }

        // Build the positions
        val positions = ArrayList<Int>()
        val margin = 15
        positions += offsetX
        for (i in 1 until columns.size) {
            positions += positions[i - 1] + widths[i - 1] + margin
        }

        // Finally, write out all the lines.
        for (line in gridLines) {
            lines += GridLine(line, positions)
        }
    }

    private fun runCommand() {
        // Jump the scroll to the bottom when the player runs something.
        lineScroll = 0f

        // Clear out the input
        val input = this.input
        this.input = ""

        // Don't add blank lines or the same command multiple times in a row
        if (history.lastOrNull() != input && input.isNotBlank())
            history.add(input)

        addLine(prompt + input)

        // If we're part way through a command that takes multiple
        // lines of input, run that.
        continued?.let { cmd ->
            continued = null
            cmd.run(input.trim())
            return
        }

        // If there's an auto-completer active, dismiss it.
        // (it could crash if the entire line goes away)
        completion = null

        // Ignore empty commands
        if (input.isBlank())
            return

        // Build the arguments list, ignoring subsequent spaces
        val args = input.split(' ').filter { it.isNotEmpty() }
        val command = args[0]

        val cmd = commands.firstOrNull { it.name == command }
        if (cmd == null) {
            addLine("Unknown command '${command}', see the help command")
            return
        }

        val numArgs = args.size - 1 // Exclude the command itself

        if (cmd.isVarArg) {
            if (cmd.params.size > numArgs) {
                addLine("Command '${command}' takes at least ${cmd.params.size} arguments, but $numArgs were supplied.")
                return
            }
        } else if (cmd.params.size != numArgs) {
            addLine("Command '${command}' takes ${cmd.params.size} arguments, but $numArgs were supplied.")
            return
        }

        val parsedArgs = ArrayList<Any>(cmd.params.size)
        for (i in 0 until cmd.params.size) {
            val str = args[i + 1] // +1 to exclude the command name

            val result = cmd.params[i].type.process(str, this)
            if (result == null) {
                // Parsing failure, main message already printed
                addLine(" (error found in argument ${i + 1})")
                return
            }

            parsedArgs.add(result)
        }

        if (cmd.isVarArg) {
            val varArgList = args.subList(cmd.params.size + 1, args.size) // +1 to exclude command name
            parsedArgs.add(varArgList)
        }

        cmd.func(parsedArgs)
    }

    private fun updateAutoCompleter() {
        parameterHints.clear()

        // A continuation can delegate to its own auto-completer,
        // if it really needs to.
        if (continued != null) {
            completion = null
            return
        }

        // Don't auto-complete history
        if (historyCursor != -1) {
            completion = null
            return
        }

        // No auto-completion for an empty input
        if (input.isBlank()) {
            completion = null
            return
        }

        // Count the number of spaces, to find out our argument number
        // Note that idx=0 is the command name itself, so arguments need -1
        val partIdx = input.count { it == ' ' }
        val paramIdx = partIdx - 1

        val commandName = input.substringBefore(' ')
        val command = commands.firstOrNull { it.name == commandName }

        // Add hints for all the remaining parameters
        if (command != null) {
            for (i in paramIdx + 1 until command.params.size) {
                parameterHints.add(command.params[i].name)
            }
        }

        if (partIdx == 0) {
            if (completion !is CommandCompleter)
                completion = CommandCompleter(this)
            completion!!.update(input, 0)
            return
        }

        // Unknown command?
        if (command == null) {
            completion = null
            return
        }

        // If we're past the last argument in a non-vararg function, continue
        // completing the last argument. This lets you use spaces when
        // fuzzy-searching the last argument, which is a common case for stuff
        // like the 'weapon' command.
        if (paramIdx >= command.params.size && (command.isVarArg || command.params.isEmpty())) {
            completion = null
            return
        }
        val effectiveParamIdx = paramIdx.coerceAtMost(command.params.size - 1)

        val currentParam = command.params[effectiveParamIdx]

        completion = currentParam.type.getCompleter(this, completion)

        // Find the start position for this argument. We can't use lastIndexOf,
        // since that wouldn't work for the last space-containing effective arg.
        var startPos = 0
        for (i in 0..effectiveParamIdx) {
            startPos = input.indexOf(' ', startPos) + 1
        }

        val currentToken = input.substring(startPos)
        completion?.update(currentToken, startPos)
    }

    fun getWeapon(callback: (AbstractWeaponBlueprint) -> Unit) {
        continued = object : ContinuedCommand {
            // A little caching for the search
            var lastInput: String? = null
            val visibleWeapons = ArrayList<Buttons.BlueprintButton>()

            override val prompt: String get() = "WEAPON> "

            var lastLeftClick = false

            override fun run(line: String) {
                val weapon = game.blueprintManager.blueprints[line]
                if (weapon == null) {
                    addLine("No such blueprint '$line'")
                    return
                }
                if (weapon !is AbstractWeaponBlueprint) {
                    addLine("Blueprint '$line' is not a weapon - ${weapon.javaClass.name}")
                    return
                }
                callback(weapon)
            }

            override fun render(gc: GameContainer, g: Graphics, height: Float) {
                updateSearch()

                val mouseX = gc.input.mouseX
                val mouseY = gc.input.mouseY

                val leftDown = gc.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)
                val clicking = leftDown && !lastLeftClick
                lastLeftClick = leftDown

                for ((i, button) in visibleWeapons.withIndex()) {
                    val x = 10 + (button.image.normal.width + 5) * i
                    val y = height.toInt() + 10

                    if (x > gc.width)
                        break

                    button.windowOffset = ConstPoint(x, y)
                    button.update(mouseX, mouseY)
                    button.draw(g)

                    if (clicking) {
                        button.mouseDown(Input.MOUSE_LEFT_BUTTON, mouseX, mouseY)
                    }
                }
            }

            private fun updateSearch() {
                val line = currentLine

                if (lastInput == line)
                    return
                lastInput = line

                val searcher = FuzzySearcher(line)

                val names = game.blueprintManager.blueprints.keys.mapNotNull {
                    val score = searcher.rank(it)

                    return@mapNotNull if (score == 0) {
                        null
                    } else {
                        Pair(it, score)
                    }
                }.sortedByDescending { it.second }.map { it.first }

                val images = ButtonImageSet.select2(game, "img/storeUI/store_buy_weapons")

                // Use BlueprintButton's rendering
                class DummyButton(override val blueprint: Blueprint) :
                    Buttons.BlueprintButton(ConstPoint.ZERO, game, images) {
                    override fun click(button: Int) {
                        historyCursor = -1
                        input = blueprint.name
                        runCommand()
                    }
                }

                val weapons: List<DummyButton> = names.mapNotNull {
                    val bp = game.blueprintManager.blueprints[it]
                    val weapon = bp as? AbstractWeaponBlueprint ?: return@mapNotNull null
                    return@mapNotNull DummyButton(weapon)
                }

                visibleWeapons.clear()
                visibleWeapons.addAll(weapons)
            }
        }
    }

    fun getAugment(callback: (AugmentBlueprint) -> Unit) {
        val augmentSize = ConstPoint(235, 40)
        val margin = 5
        val augmentFont = game.getFont("JustinFont12Bold")

        val allAugments = game.blueprintManager.blueprints.values.mapNotNull { it as? AugmentBlueprint }

        class AugmentButton(val aug: Blueprint) : Button(game, ConstPoint.ZERO, augmentSize) {
            override fun click(button: Int) {
                historyCursor = -1
                input = aug.name
                runCommand()
            }

            override fun draw(g: Graphics) {
                // FIXME this is copied from ShipEquipmentPanel.

                // Draw the empty box
                g.colour = Constants.AUGMENT_EMPTY_OUTLINE
                g.fillRect(pos.x.f, pos.y.f, size.x.f, size.y.f)
                g.colour = Constants.AUGMENT_EMPTY_INSIDE
                g.fillRect(pos.x + 3f, pos.y + 3f, size.x - 6f, size.y - 6f)

                // Draw the semi-transparent augment on top of it

                // Draw the borders. Since the middle is semi-transparent, we can't
                // just fill in the whole thing twice to get our border easily.
                g.colour = when {
                    // dragPosition != null -> Constants.AUGMENT_BOX_OUTLINE
                    hovered -> Constants.AUGMENT_BOX_OUTLINE_HOVER
                    else -> Constants.AUGMENT_BOX_OUTLINE
                }
                // Left and right
                g.fillRect(pos.x + 0f, pos.y + 0f, 3f, size.y.f)
                g.fillRect(pos.x + size.x - 3f, pos.y + 0f, 3f, size.y.f)

                // Top and bottom
                g.fillRect(pos.x + 3f, pos.y + 0f, size.x - 6f, 3f)
                g.fillRect(pos.x + 3f, pos.y + size.y - 3f, size.x - 6f, 3f)

                // Fill in the background
                g.colour = Constants.AUGMENT_BOX_INSIDE
                g.fillRect(pos.x + 3f, pos.y + 3f, size.x - 6f, size.y - 6f)

                // Draw the name
                val name = aug.translateTitle(game)
                augmentFont.drawStringCentred(pos.x.f, pos.y.f + 27f, size.x.f, name, Constants.AUGMENT_NAME_TEXT)
            }
        }

        // FIXME this is partially copy-pasted from getWeapon
        continued = object : ContinuedCommand {
            // A little caching for the search
            var lastInput: String? = null
            val visibleAugments = ArrayList<AugmentButton>()

            override val prompt: String get() = "AUGMENT> "

            var lastLeftClick = false

            override fun run(line: String) {
                val bp = game.blueprintManager.blueprints[line]
                if (bp == null) {
                    addLine("No such blueprint '$line'")
                    return
                }
                if (bp !is AugmentBlueprint) {
                    addLine("Blueprint '$line' is not an augment - ${bp.javaClass.name}")
                    return
                }
                callback(bp)
            }

            override fun render(gc: GameContainer, g: Graphics, height: Float) {
                updateSearch()

                val mouseX = gc.input.mouseX
                val mouseY = gc.input.mouseY

                val leftDown = gc.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)
                val clicking = leftDown && !lastLeftClick
                lastLeftClick = leftDown

                var row = 0
                var column = 0

                val basePos = ConstPoint(10, height.toInt() + 10)
                val effectiveSize = augmentSize + ConstPoint(margin, margin)

                for (button in visibleAugments) {
                    var x = basePos.x + effectiveSize.x * column
                    var y = basePos.y + effectiveSize.y * row

                    column++

                    if (x + effectiveSize.x > gc.width) {
                        x = basePos.x
                        y += effectiveSize.y
                        column = 0
                        row++
                    }

                    if (y > gc.height) {
                        break
                    }

                    button.windowOffset = ConstPoint(x, y)
                    button.update(mouseX, mouseY)
                    button.draw(g)

                    if (clicking) {
                        button.mouseDown(Input.MOUSE_LEFT_BUTTON, mouseX, mouseY)
                    }
                }
            }

            private fun updateSearch() {
                val line = currentLine

                if (lastInput == line)
                    return
                lastInput = line

                visibleAugments.clear()

                // If no search is entered, show all the augments alphabetically
                if (line.isBlank()) {
                    visibleAugments += allAugments.sortedBy { it.translateTitle(game) }.map { AugmentButton(it) }
                    return
                }

                val searcher = FuzzySearcher(line)

                val blueprints = allAugments.mapNotNull {
                    val score = searcher.rank(it.translateTitle(game))

                    return@mapNotNull if (score == 0) {
                        null
                    } else {
                        Pair(it, score)
                    }
                }.sortedByDescending { it.second }.map { it.first }

                visibleAugments.addAll(blueprints.map { AugmentButton(it) })
            }
        }
    }

    fun getEvent(callback: (IEvent) -> Unit) {
        data class EventInfo(val event: IEvent, val text: String?)

        continued = object : ContinuedCommand {
            // A little caching for the search
            var lastInput: String? = null
            val visibleEvents = ArrayList<EventInfo>()

            override val prompt: String get() = "EVENT> "

            var lastLeftClick = false

            override fun run(line: String) {
                val names = game.eventManager.eventNames
                if (!names.contains(line)) {
                    addLine("No such event '$line'")
                    return
                }
                val event = game.eventManager[line]

                callback(event)
            }

            override fun render(gc: GameContainer, g: Graphics, height: Float) {
                updateSearch()

                val mouseX = gc.input.mouseX
                val mouseY = gc.input.mouseY

                val leftDown = gc.input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON)
                val clicking = leftDown && !lastLeftClick
                lastLeftClick = leftDown

                val x = 30
                val blockHeight = 15
                val width = gc.width - x - 40

                val idWidth = 125

                // Draw the search category options
                val optionHeight = blockHeight + 2
                var optionX = x
                fun drawOption(value: Boolean, text: String) {
                    val boxWidth = 5 + font.getWidth(text) + 5
                    g.colour = Colour(55, if (value) 180 else 55, 55, 180)

                    val y = height + 10f
                    g.fillRect(optionX.f, y, boxWidth.f, optionHeight.f)
                    font.drawString(optionX + 5f, y + 10f, text, Colour.white)

                    optionX += boxWidth
                }
                drawOption(eventSearchIds, "Search IDs (F1)")
                drawOption(eventSearchMessageText, "Search event text (F2)")
                drawOption(eventSearchOptions, "Search choice text (F3)")

                for ((i, event) in visibleEvents.withIndex()) {
                    val y = height.toInt() + 10 + optionHeight + i * blockHeight

                    if (y > gc.height)
                        break

                    val hovering = mouseX in x until x + width && mouseY in y until y + blockHeight

                    val shade = if (hovering) 140 else 100
                    g.colour = Colour(shade, shade, shade, 180)
                    g.fillRect(x.f, y.f, width.f, blockHeight.f)

                    font.drawStringTruncated(x + 5f, y + 10f, idWidth.f, event.event.debugId, Colour.white)

                    val descriptionX = x + idWidth + 10
                    val descriptionWidth = width - descriptionX - 5f
                    val text = event.text ?: "<event list>"
                    font.drawStringTruncated(
                        descriptionX.f, y + 10f, descriptionWidth,
                        text.replace("\n", " \\n "),
                        Colour.white
                    )

                    if (clicking && hovering) {
                        // Note the debug ID matches the event name for top-level events (we don't
                        // display nested events).
                        historyCursor = -1
                        input = event.event.debugId
                        runCommand()
                    }
                }
            }

            override fun keyPressed(key: Int, c: Char): Boolean {
                val handled = when (key) {
                    Input.KEY_F1 -> {
                        eventSearchIds = !eventSearchIds
                        true
                    }

                    Input.KEY_F2 -> {
                        eventSearchMessageText = !eventSearchMessageText
                        true
                    }

                    Input.KEY_F3 -> {
                        eventSearchOptions = !eventSearchOptions
                        true
                    }

                    else -> false
                }

                // Update the search entries
                if (handled)
                    lastInput = null

                return handled
            }

            private fun updateSearch() {
                val line = currentLine

                if (lastInput == line)
                    return
                lastInput = line

                val searcher = FuzzySearcher(line)

                val events = game.eventManager.eventNames.mapNotNull { name ->
                    val event = game.eventManager[name]

                    var score = 0

                    // Search the name, and consider it to be more important
                    // than the body text. This makes it easier to find a specific
                    // event.
                    if (eventSearchIds)
                        score += searcher.rank(name) * 10

                    // If this is an event list, we can't search it's text
                    if (event !is Event)
                        return@mapNotNull Pair(EventInfo(event, null), score)

                    // And the body of the event text - just search the first
                    // available one, it'd get a bit unmanageable otherwise.
                    val textBody = event.text?.let(::getTextBody) ?: return@mapNotNull null
                    if (eventSearchMessageText)
                        score += searcher.rank(textBody)

                    // Rank all the options, too.
                    if (eventSearchOptions) {
                        for (choice in event.choices) {
                            val text = getTextBody(choice.text) ?: continue
                            score += searcher.rank(text)
                        }
                    }

                    Pair(EventInfo(event, textBody), score)
                }.filter { it.second != 0 }.sortedByDescending { it.second }

                visibleEvents.clear()
                visibleEvents.addAll(events.map { it.first })
            }

            private fun getTextBody(text: IEventText): String? {
                if (text is EventText)
                    return text.localised

                require(text is TextList)

                for (item in text.items) {
                    getTextBody(item)?.let { return it }
                }
                return null
            }
        }
    }

    fun <T> pickFromList(prompt: String, items: List<Pair<String, T>>, callback: (T) -> Unit) {
        continued = object : ContinuedCommand {
            // A little caching for the search
            var lastInput: String? = null
            val sortedEntries = ArrayList<Pair<String, T>>()

            override val prompt: String = "$prompt> "

            var lastLeftClick = false

            override fun run(line: String) {
                val item = items.firstOrNull { it.first == line }
                if (item == null) {
                    addLine("No such option '$line'")
                    return
                }
                callback(item.second)
            }

            override fun render(gc: GameContainer, g: Graphics, height: Float) {
                updateSearch()

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

                    font.drawString(x + 5f, y + 10f, item.first, Colour.white)

                    if (clicking && hovering) {
                        historyCursor = -1
                        input = item.first
                        runCommand()
                    }
                }
            }

            private fun updateSearch() {
                val line = currentLine

                if (lastInput == line)
                    return
                lastInput = line

                sortedEntries.clear()
                sortedEntries.addAll(items)

                if (line.isBlank()) {
                    // If there's no search term, leave the items in their original order.
                    return
                }

                // Display all the entries - sorting only orders them
                val searcher = FuzzySearcher(line)
                sortedEntries.sortByDescending { searcher.rank(it.first) }
            }
        }
    }

    fun onFailedSaveRestore() {
        addLine("Continuous save/restore - exception during serialisation!")
        addLine("Details are in the console, the game has been paused.")
    }

    data class Cmd(
        val name: String,
        val params: List<CmdParameter>,
        val isVarArg: Boolean,
        val func: (List<Any>) -> Unit,
        val helpText: String
    )

    data class CmdParameter(
        val type: ArgumentTypeProcessor,
        val name: String
    )

    class FuzzySearcher(query: String) {
        // Split up the words
        private val query = query.toLowerCase(Locale.UK)
        private val inputParts = query.split(" ", "_").filter { it.isNotBlank() }

        fun rank(target: String): Int {
            val lower = target.toLowerCase(Locale.UK)

            // Only match words once, as otherwise a word that's repeated a lot could
            // cause one entry to very frequently appear at the top of the list.
            val parts = lower
                .replace("_", " ")
                .split(" ")
                .toSet()

            var score = 0

            for (part in parts) {
                for (ip in inputParts) {
                    if (part == ip) {
                        score += 20 + ip.length * 3
                    } else if (part.contains(ip)) {
                        // Add a per-word weight
                        score += 10 + ip.length
                    }
                }
            }

            // Add a bonus if the search matches literally (though case-insensitively).
            if (target.contains(query)) {
                score += 4 * query.length
            }

            return score
        }
    }

    interface ContinuedCommand : Overlay {
        val prompt: String
        fun run(line: String)
    }

    interface Overlay {
        val autoCompleteSuggestion: String? get() = null

        fun render(gc: GameContainer, g: Graphics, height: Float) {}
        fun keyPressed(key: Int, c: Char): Boolean = false
        fun mousePressed(button: Int, x: Int, y: Int) = Unit
        fun mouseReleased(button: Int, x: Int, y: Int) = Unit
        fun mouseDragged(oldX: Int, oldY: Int, newX: Int, newY: Int) = Unit
    }

    // Represents a line of console output
    private interface ILine {
        fun draw(x: Int, y: Int)
    }

    private inner class SimpleLine(val line: String) : ILine {
        override fun draw(x: Int, y: Int) {
            font.drawString(x.f, y.f, line, Colour.white)
        }
    }

    private inner class GridLine(val parts: List<String>, val positions: List<Int>) : ILine {
        override fun draw(x: Int, y: Int) {
            for ((index, part) in parts.withIndex()) {
                val pos = positions[index]
                font.drawString(x.f + pos, y.f, part, Colour.white)
            }
        }
    }

    companion object {
        private const val PROMPT = "> "
        private const val FLASH_TIME = 0.65f

        // TODO move this to somewhere in appdata (or platform equivalent) when we pick
        //  somewhere to store the regular savegames.
        @JvmField
        val DEBUG_SAVE_DIR = Path.of("debug-saves")
    }
}

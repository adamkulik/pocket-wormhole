package xyz.znix.xftl.hangar

import xyz.znix.xftl.sys.XftlResources

import org.jdom2.JDOMException
import org.jdom2.input.SAXBuilder
import xyz.znix.xftl.*
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.game.ShipFamily
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.WindowRenderer
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.PlatformSpecific
import xyz.znix.xftl.sys.ResourceContext
import xyz.znix.xftl.ui.*
import java.io.IOException

class SelectShipState(private val datafile: Datafile, private val main: MainGame) : MainGame.GameState() {
    private val resourceContext = ResourceContext()

    val mousePos = Point(0, 0)
    val shipOffset = Point(0, 0)

    // Where the hangar centres each ship's hull art (measured against
    // vanilla 1.6.14, issue #109 - see the render code that uses it).
    private val HANGAR_SHIP_CENTRE_X = 630
    private val HANGAR_SHIP_CENTRE_Y = 210

    private val shipSelectorPos = ConstPoint(20, 50)

    // This is the size of the game window, not the full screen - but 'window'
    // also refers to in-game windows.
    val screenSize = Point(0, 0)

    val blueprints: BlueprintManager = BlueprintManager(datafile, true)
    val translator: Translator = Translator(datafile, "en")
    val animations: Animations = Animations(datafile)

    val roomImageMeta: RoomImageMeta = RoomImageMeta.loadFromResource()

    val windowRenderer: WindowRenderer

    val shipFamilies: ShipFamily.FamilyTable
    val ships: List<LazyShipBlueprint>

    private val current: EditableShip get() = editor.ship

    /**
     * Vanilla's ADVANCED EDITION ON/OFF toggle, saved in the profile. With
     * it off, the AE-only ships (the Lanius and every type C layout) can't
     * be picked, and the run is played without any AE content.
     */
    var advancedEdition: Boolean
        get() = main.profile.advancedEdition
        set(value) {
            main.profile.advancedEdition = value

            // Move off an AE-only ship, and refresh the A/B/C buttons.
            if (!isShipSelectable(current.baseBlueprint))
                selectShip(firstSelectableShip())
            else
                selectShip(currentBlueprint)
        }

    /**
     * The player ships that exist without Advanced Edition content - the
     * ones defined outside the dlc blueprint files.
     */
    private val nonAeShips: Set<String> by lazy {
        BlueprintManager(datafile, false).blueprints.keys
            .filter { it.startsWith("PLAYER_SHIP_") }
            .toSet()
    }

    private val currentBlueprint: ShipBlueprint get() = blueprints.getShip(current.baseBlueprint)

    var isShipEdited = false
        private set

    private val images = HashMap<String, Image>()
    private val fonts = HashMap<String, SILFontLoader>()

    private val editFileControls: EditFileControls

    private lateinit var editor: ShipEditor

    private val startGameButton: StartGameButton
    private val advancedBox: AdvancedEditionBox
    private val mainMenuButton: MainMenuButton?

    private val uiProvider: UIProvider = ShipSelectUIProvider()
    private val shipSelector: WidgetContainer

    private val shipList: ShipList
    private var shipListVisible = false

    /**
     * Vanilla's HIDE ROOMS / SHOW ROOMS toggle: with the rooms hidden only
     * the ship's hull (and weapons) are drawn.
     */
    private var roomsHidden = false

    val font = getFont("c&c")
    val fontHL2 = getFont("HL2")

    val missingImage = getImgOrNull("img/nullResource.png") ?: error("Missing nullResource.png")

    /** The hangar backdrop; vanilla art that is exactly the game's size, so
     *  it can be drawn 1:1 behind the ship-selection UI. */
    private val hangarBackground = getImg("img/customizeUI/custom_background.png")

    init {
        startGameButton = StartGameButton(this)
        advancedBox = AdvancedEditionBox(this)
        editFileControls = EditFileControls(this)
        mainMenuButton = if (PlatformSpecific.INSTANCE.isTouchUi)
            MainMenuButton(this, 200 + editFileControls.editBtnWidth + 10, 10)
        else
            null

        val background = getImg("img/window_base.png")
        val outline = getImg("img/window_outline.png")
        val mask = getImg("img/window_mask.png")
        windowRenderer = WindowRenderer(background, outline, mask)

        // TODO load this via the usual asset system, so mods can override it
        // TODO de-duplicate with InGameState
        val builder = com.pocketwormhole.android.SafeXML.builder()
        try {
            val doc = builder.build(XftlResources.open("assets/data/xftl_ships.xml"))
            shipFamilies = ShipFamily.FamilyTable(doc)
        } catch (e: IOException) {
            throw RuntimeException("Failed to load ship data", e);
        } catch (e: JDOMException) {
            throw RuntimeException("Failed to parse ship data", e);
        }
        val familyNames = shipFamilies.families.map { it.ships[0] }

        ships = blueprints.blueprints.values
            .asSequence()
            .filterIsInstance(LazyShipBlueprint::class.java)
            .filter { it.name.startsWith("PLAYER_SHIP_") }
            .sortedBy { it.name }
            .sortedBy { ship -> familyNames.indexOfFirst { ship.name.startsWith(it) } }
            .toList()

        shipSelector = SpecDeserialiser(uiProvider).load("ship_list_abc").mainWidget

        shipSelector.addButtonListener("random") { selectShip(randomUnlockedShip()) }

        shipSelector.addButtonListener("type_a") { selectShipVariant(0) }
        shipSelector.addButtonListener("type_b") { selectShipVariant(1) }
        shipSelector.addButtonListener("type_c") { selectShipVariant(2) }

        shipSelector.addButtonListener("list") { shipListVisible = true }

        shipSelector.addButtonListener("hiderooms") { toggleRoomsHidden() }

        // The arrows either side of LIST step through the ships, like vanilla.
        (shipSelector.byId["prev"] as ImageButton).clickListener = { stepShip(-1) }
        (shipSelector.byId["next"] as ImageButton).clickListener = { stepShip(1) }

        shipList = ShipList(this) {
            shipListVisible = false
            if (it != null) {
                selectShip(it.real)
            }
        }

        selectShip(firstSelectableShip())
    }

    private fun firstSelectableShip(): ShipBlueprint {
        return (ships.firstOrNull { isShipSelectable(it.name) } ?: ships.first()).real
    }

    override fun shutdown() {
        resourceContext.freeAll()
    }

    override fun render(container: GameContainer, g: Graphics) {
        g.clear(Colour.darkGray)

        // The hangar art (1280x720, matching the game area exactly).
        hangarBackground.draw(0f, 0f)

        screenSize.x = container.width
        screenSize.y = container.height

        g.pushTransform()
        g.translate(shipSelectorPos.x.f, shipSelectorPos.y.f)
        shipSelector.draw(g)
        g.popTransform()

        startGameButton.draw(g)
        advancedBox.draw(g)

        editFileControls.draw(g)
        mainMenuButton?.draw(g)

        g.pushTransform()
        // Centre each ship's hull art at a fixed hangar point, measured
        // against vanilla 1.6.14 (issue #109): the art's centre sits at
        // ~(630,210), all ships fitting (306,12)-(953,407). Anchoring the
        // hull image's top-left (the old code) misplaces ships relative
        // to each other, because hullbox offsets vary hugely between
        // layouts (Kestrel -59,-103; Zoltan -216,-92; Lanius -153,-164).
        // The hull image is centred on the art, so centring the IMAGE
        // centres the ship.
        val hullImg = currentBlueprint.hullImage.firstNotNullOf { getImgOrNull(it) }
        shipOffset.x = Math.round(HANGAR_SHIP_CENTRE_X - currentBlueprint.hullOffset.x - hullImg.width / 2f)
        shipOffset.y = Math.round(HANGAR_SHIP_CENTRE_Y - currentBlueprint.hullOffset.y - hullImg.height / 2f)
        g.translate(shipOffset.x.f, shipOffset.y.f)
        // The editor needs the rooms to edit them.
        if (roomsHidden && !isShipEdited)
            current.drawHull(g, this)
        else
            current.draw(g, this, false)
        editor.editorWidth = container.width - shipOffset.x
        editor.editorHeight = container.height - shipOffset.y
        editor.hideRooms = roomsHidden && !isShipEdited
        editor.draw(g)
        g.popTransform()

        if (shipListVisible) {
            shipList.draw(container, g)
        }
    }

    override fun update(container: GameContainer, delta: Float) {
        val input = container.input
        mousePos.x = input.mouseX
        mousePos.y = input.mouseY

        shipSelector.updateMouse(mousePos.x - shipSelectorPos.x, mousePos.y - shipSelectorPos.y)
    }

    override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {
        if (shipListVisible) {
            shipList.mouseClicked(button)
            return
        }

        // Block editor interactions if we're only inspecting a ship
        if (isShipEdited) {
            editor.mouseClicked(button, x - shipOffset.x, y - shipOffset.y, clickCount)
        }

        startGameButton.mouseClicked(button)
        advancedBox.mouseClicked(button)

        editFileControls.mouseClicked(button)
        mainMenuButton?.mouseClicked(button)

        shipSelector.mouseClicked(button)
    }

    override fun mousePressed(button: Int, x: Int, y: Int) {
        if (shipListVisible)
            return

        if (isShipEdited) {
            editor.mousePressed(button, x - shipOffset.x, y - shipOffset.y)
        }
    }

    override fun mouseReleased(button: Int, x: Int, y: Int) {
        if (shipListVisible)
            return

        if (isShipEdited) {
            editor.mouseReleased(button, x - shipOffset.x, y - shipOffset.y)
        }
    }

    override fun mouseDragged(oldX: Int, oldY: Int, newX: Int, newY: Int) {
        if (shipListVisible)
            return

        if (isShipEdited) {
            editor.mouseDragged(oldX - shipOffset.x, oldY - shipOffset.y, newX - shipOffset.x, newY - shipOffset.y)
        }
    }

    override fun mouseMoved(oldX: Int, oldY: Int, newX: Int, newY: Int) {
        if (shipListVisible)
            return

        if (isShipEdited) {
            editor.mouseMoved(newX - shipOffset.x, newY - shipOffset.y)
        }
    }

    override fun mouseWheelMoved(change: Int) {
        if (shipListVisible) {
            shipList.mouseWheelMoved(change)
            return
        }

        if (isShipEdited) {
            editor.mouseWheelMoved(change)
        }
    }

    override fun keyReleased(key: Int, c: Char) {
        if (shipListVisible) {
            if (key == Input.KEY_ESCAPE)
                shipListVisible = false
            return
        }

        // Like vanilla, Esc (Back on Android) leaves the hangar - unless
        // the ship editor is open, which uses it to close its menus.
        if (key == Input.KEY_ESCAPE && !isShipEdited) {
            backToMainMenu()
            return
        }

        editor.keyReleased(key, c)
    }

    fun backToMainMenu() {
        main.switchToMainMenu()
    }

    fun getImg(path: String): Image {
        getImgOrNull(path)?.let { return it }

        println("[WARN] Invalid path for image: '$path'")
        images[path] = missingImage // Don't repeat the warning for each getImg call
        return missingImage
    }

    fun getImgOrNull(path: String): Image? {
        images[path]?.let { return it }

        val file = datafile.getOrNull(path) ?: return null
        val img = datafile.readImage(resourceContext, file)
        images[path] = img
        return img
    }

    fun getFont(name: String): SILFontLoader {
        // Always return a copy of the font, so our instance doesn't get broken
        // when a consumer sets their instance's scale property or anything
        // like that.
        fonts[name]?.let { return SILFontLoader(it) }

        val font = SILFontLoader(resourceContext, datafile, datafile["fonts/$name.font"])
        fonts[name] = font
        return SILFontLoader(font)
    }

    fun startGame(selected: Difficulty) {
        val editedShip = if (isShipEdited) editor.ship else null

        // A ship edited in the hangar can use any system, AE ones included,
        // so it always gets the AE content to find their blueprints.
        main.startNewGame(currentBlueprint.name, selected, editedShip, advancedEdition || editedShip != null)
    }

    fun startEditingShip() {
        isShipEdited = true
    }

    fun stopEditingShip() {
        isShipEdited = false

        // Re-create the editor to throw away the changes
        editor = ShipEditor(this, EditableShip.fromBlueprint(currentBlueprint))
    }

    private fun selectShip(blueprint: ShipBlueprint) {
        editor = ShipEditor(this, EditableShip.fromBlueprint(blueprint))

        // Update the A/B/C variant buttons
        val family = shipFamilies.byShipId[blueprint.name]
        val typeId = family?.ships?.indexOf(blueprint.name) ?: 0

        fun updateTypeButton(id: String, idx: Int) {
            val button = shipSelector.byId[id] as UIKitButton

            // A family may only list variants that exist as blueprints - e.g.
            // there's no Crystal or Lanius type C (GitHub issue #108).
            val variantExists = family?.ships?.getOrNull(idx)?.let { shipId ->
                ships.any { it.name == shipId } && isShipSelectable(shipId)
            } == true

            if (idx == typeId) {
                // Disable to prevent clicking, but still show as selected.
                button.forceSelected = true
                button.disabled = true
            } else if (family == null || !variantExists || !isFamilyUnlocked(family)) {
                button.forceSelected = false
                button.disabled = true
            } else {
                button.forceSelected = false
                button.disabled = false
            }
        }

        updateTypeButton("type_a", 0)
        updateTypeButton("type_b", 1)
        updateTypeButton("type_c", 2)
    }

    private fun toggleRoomsHidden() {
        roomsHidden = !roomsHidden

        val label = shipSelector.byId["hiderooms_label"] as Label
        label.text = when (roomsHidden) {
            true -> translator.translations["ship_showrooms_button"] ?: "SHOW ROOMS"
            false -> translator["ship_hiderooms_button"]
        }
        shipSelector.updateLayout()
    }

    /**
     * Switch between the A/B/C variants of the current ship.
     */
    private fun selectShipVariant(index: Int) {
        val family = shipFamilies.byShipId[current.baseBlueprint] ?: return
        if (!isFamilyUnlocked(family)) return

        val shipId = family.ships.getOrNull(index) ?: return
        if (!isShipSelectable(shipId)) return

        val blueprint = ships.firstOrNull { it.name == shipId } ?: return
        selectShip(blueprint.real)
    }

    /**
     * Whether the player has unlocked the ship family of the given
     * blueprint. Ships not belonging to a family (modded-in layouts) are
     * always available.
     */
    fun isShipUnlocked(shipName: String): Boolean {
        val family = shipFamilies.byShipId[shipName] ?: return true
        return isFamilyUnlocked(family)
    }

    /**
     * Whether a ship can be picked: unlocked, and available with the
     * current Advanced Edition setting.
     */
    fun isShipSelectable(shipName: String): Boolean {
        if (!advancedEdition && shipName !in nonAeShips)
            return false
        return isShipUnlocked(shipName)
    }

    fun isFamilyUnlocked(family: ShipFamily): Boolean {
        return family.isUnlocked(main.profile)
    }

    /**
     * Select the next ([direction] = 1) or previous (-1) ship, in the order of
     * the ship list: each family's A variant, then any ships not in a family.
     * Ships that aren't unlocked, or that the current Advanced Edition setting
     * can't use, are skipped, like vanilla. Wraps around at either end.
     *
     * The current layout (A/B/C) carries over to the ship that gets picked,
     * when it has that variant - vanilla's ShipBuilder::CycleShipNext /
     * CycleShipPrevious keep the layout index while cycling ships.
     */
    private fun stepShip(direction: Int) {
        // Switching ships re-creates the ship editor, so the arrows are
        // ignored while a ship is being edited in the hangar.
        if (isShipEdited)
            return

        val nonFamilyShips = ships.filter { !shipFamilies.byShipId.containsKey(it.name) }
        val aVariants = shipFamilies.families.mapNotNull { family -> ships.firstOrNull { it.name == family.ships[0] } }
        val rows = aVariants + nonFamilyShips
        if (rows.isEmpty())
            return

        // The current ship's row - the family's A variant row, for a B/C ship -
        // plus the layout index to carry over.
        val currentName = current.baseBlueprint
        val currentFamily = shipFamilies.byShipId[currentName]
        val layoutIndex = currentFamily?.ships?.indexOf(currentName) ?: 0
        val currentIndex = rows.indexOfFirst { it.name == currentName || it.name == currentFamily?.ships?.getOrNull(0) }

        // If the current ship isn't a row of the list, the first step lands on
        // the first row going forward, the last row going back.
        var index = if (currentIndex == -1) -1 else currentIndex

        var steps = 0
        while (steps < rows.size) {
            steps++
            index = (index + direction).mod(rows.size)

            val target = shipForRow(rows[index], layoutIndex) ?: continue

            if (target.name != currentName)
                selectShip(target)
            return
        }
    }

    /**
     * The blueprint of the ship list [row]'s [layoutIndex] variant (A/B/C), or
     * null when that variant doesn't exist or can't be picked with the current
     * unlock / Advanced Edition state.
     */
    private fun shipForRow(row: LazyShipBlueprint, layoutIndex: Int): ShipBlueprint? {
        val family = shipFamilies.byShipId[row.name]
        val shipId = family?.ships?.getOrNull(layoutIndex) ?: row.name
        if (!isShipSelectable(shipId))
            return null

        return ships.firstOrNull { it.name == shipId }?.real
    }

    /**
     * Pick a random ship for the "random" button, among the ones the
     * player has actually unlocked.
     */
    private fun randomUnlockedShip(): ShipBlueprint {
        val unlocked = ships.filter { isShipSelectable(it.name) }
        return (unlocked.ifEmpty { ships }).random().real
    }
    private inner class ShipSelectUIProvider : UIProvider {
        override fun getFont(name: String): SILFontLoader {
            return this@SelectShipState.getFont(name)
        }

        override fun getImg(path: String): Image {
            return this@SelectShipState.getImg(path)
        }

        override fun translate(key: String): String? {
            return translator.translations[key]
        }

        override fun getDebugOutlineColour(widget: Widget): Colour? {
            return null
        }

        override fun getWindowRenderer(): WindowRenderer {
            return windowRenderer
        }
    }
}

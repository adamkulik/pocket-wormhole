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
    private val currentBlueprint: ShipBlueprint get() = blueprints.getShip(current.baseBlueprint)

    var isShipEdited = false
        private set

    private val images = HashMap<String, Image>()
    private val fonts = HashMap<String, SILFontLoader>()

    private val editFileControls: EditFileControls

    private lateinit var editor: ShipEditor

    private val startGameButton: StartGameButton

    private val uiProvider: UIProvider = ShipSelectUIProvider()
    private val shipSelector: WidgetContainer

    private val shipList: ShipList
    private var shipListVisible = false

    val font = getFont("c&c")
    val fontHL2 = getFont("HL2")

    val missingImage = getImgOrNull("img/nullResource.png") ?: error("Missing nullResource.png")

    /** The hangar backdrop; vanilla art that is exactly the game's size, so
     *  it can be drawn 1:1 behind the ship-selection UI. */
    private val hangarBackground = getImg("img/customizeUI/custom_background.png")

    init {
        startGameButton = StartGameButton(this)
        editFileControls = EditFileControls(this)

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

        shipSelector.addButtonListener("random") { selectShip(ships.random().real) }

        shipSelector.addButtonListener("type_a") { selectShipVariant(0) }
        shipSelector.addButtonListener("type_b") { selectShipVariant(1) }
        shipSelector.addButtonListener("type_c") { selectShipVariant(2) }

        shipSelector.addButtonListener("list") { shipListVisible = true }

        // TODO hide/show rooms button
        // TODO the arrow navigation buttons

        shipList = ShipList(this) {
            shipListVisible = false
            if (it != null) {
                selectShip(it.real)
            }
        }

        selectShip(ships.first { it.name == shipFamilies.families.first().ships[0] }.real)
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

        editFileControls.draw(g)

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
        current.draw(g, this, false)
        editor.editorWidth = container.width - shipOffset.x
        editor.editorHeight = container.height - shipOffset.y
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

        editFileControls.mouseClicked(button)

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

        editor.keyReleased(key, c)
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
        main.startNewGame(currentBlueprint.name, selected, editedShip)
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
                ships.any { it.name == shipId }
            } == true

            if (idx == typeId) {
                // Disable to prevent clicking, but still show as selected.
                button.forceSelected = true
                button.disabled = true
            } else if (family == null || !variantExists) {
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

    /**
     * Switch between the A/B/C variants of the current ship.
     */
    private fun selectShipVariant(index: Int) {
        val family = shipFamilies.byShipId[current.baseBlueprint] ?: return
        val shipId = family.ships.getOrNull(index) ?: return

        val blueprint = ships.firstOrNull { it.name == shipId } ?: return
        selectShip(blueprint.real)
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

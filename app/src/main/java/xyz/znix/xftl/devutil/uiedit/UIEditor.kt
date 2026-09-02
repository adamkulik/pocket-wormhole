package xyz.znix.xftl.devutil.uiedit

import xyz.znix.xftl.*
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.*
import xyz.znix.xftl.sys.BasicGame
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.sys.ResourceContext
import xyz.znix.xftl.ui.SpecDeserialiser
import xyz.znix.xftl.ui.UIProvider
import xyz.znix.xftl.ui.Widget
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A simple UI 'editor'.
 *
 * This doesn't actually let you edit anything, but it does show you what a UI
 * would look like in-game, and it can be reloaded by pressing F3, and supports
 * zooming and highlighting elements to get everything lined up quickly.
 */
class UIEditor(val df: Datafile, val filename: String) : BasicGame("XFTL UI Editor"), UIProvider {
    private val resourceContext = ResourceContext()

    private val fonts = HashMap<String, SILFontLoader>()
    private val images = HashMap<String, Image>()

    private lateinit var utilFont: SILFontLoader

    private lateinit var windowRenderer: WindowRenderer

    private val translator = Translator(df, "en")

    private var ui: SpecDeserialiser.LoadedUI? = null
    private var loadError: String = ""

    private val mousePos = Point(0, 0)

    // The level of zoom controlled by the mouse, this is a log scale
    private var zoomLevel: Float = 0f
        set(value) {
            field = value
            updateZoomScale()
        }

    private val filePath = Path.of("src/main/resources/assets/ui/$filename.xml")
    private val fileWatcher: WatchService = filePath.fileSystem.newWatchService()
    private var autoReload: Boolean = false
    private var reloaderThread: Thread? = null
    private var hasChangeOccurred: Boolean = false

    // How much things on the screen are scaled by
    private var zoomScale: Float = 0f

    private var panOffsetX: Float = 0f
    private var panOffsetY: Float = 0f

    private var highlighted: Widget? = null
    private var showOutlines: Boolean = true

    override fun init(container: GameContainer) {
        updateZoomScale()

        utilFont = getFont("c&c")

        val background = getImg("img/window_base.png")
        val outline = getImg("img/window_outline.png")
        val mask = getImg("img/window_mask.png")
        windowRenderer = WindowRenderer(background, outline, mask)

        setupFileWatcher()

        reload()
    }

    override fun shutdown() {
        resourceContext.freeAll()
    }

    override fun update(container: GameContainer, delta: Float) {
        val input = container.input

        if (input.isKeyPressed(Input.KEY_F1)) {
            reload()
        }
        if (input.isKeyPressed(Input.KEY_F2)) {
            showOutlines = !showOutlines
        }
        if (input.isKeyPressed(Input.KEY_F3)) {
            zoomLevel = 0f
            panOffsetX = 0f
            panOffsetY = 0f
        }
        if (input.isKeyPressed(Input.KEY_F4)) {
            autoReload = !autoReload
        }

        if (reloaderThread?.isAlive != true) {
            // Stop the user from thinking auto-reload is working when it's not
            autoReload = false
        }
        if (hasChangeOccurred && autoReload) {
            reload()
        }
        hasChangeOccurred = false

        mousePos.set(input.mouseX, input.mouseY)
    }

    override fun render(container: GameContainer, g: Graphics) {
        ShaderProgramme.SHADER_SCREEN_SIZE.set(container.width, container.height)

        g.clear(Colour.lightGray)

        // Reset the transform from last frame, in case there was a transform
        // call that wasn't inside a pushTransform block.
        g.loadIdentityMatrix()

        g.pushTransform()
        g.translate(PREVIEW_POS_X.f, PREVIEW_POS_Y.f)
        g.scale(zoomScale, zoomScale)
        g.translate(-panOffsetX, -panOffsetY)
        renderUI(g)
        g.popTransform()

        g.pushTransform()
        g.translate(10f, 10f)
        drawTree(g)
        g.popTransform()

        // Check there aren't any mismatched pushTransform/popTransform calls.
        g.checkNoPushedTransforms()
    }

    private fun renderUI(g: Graphics) {
        val ui = ui ?: return

        ui.mainWidget.draw(g)

        // If a widget is highlighted, draw the bounding box for that
        // after everything else, so it shows up on top.
        val hl = highlighted ?: return

        g.colour = Colour.blue
        g.drawRect(hl.position.x.f, hl.position.y.f, hl.size.x - 1f, hl.size.y - 1f)
    }

    private fun drawTree(g: Graphics) {
        val ui = ui
        if (ui == null) {
            var y = utilFont.baselineToTop
            utilFont.drawString(0f, y.f, "Error loading UI XML:", Colour.black)
            y += utilFont.lineSpacing
            utilFont.drawString(0f, y.f, loadError, Colour.black)
            return
        }

        val height = utilFont.baselineToTop

        var y = 0

        utilFont.drawString(0f, y.f + height, "Scale (F3 to reset): %.2fx".format(zoomScale), Colour.black)
        y += utilFont.lineSpacing

        utilFont.drawString(0f, y.f + height, "Auto-reload (F4 to toggle): $autoReload", Colour.black)
        y += utilFont.lineSpacing

        val mouseY = mousePos.y - Graphics.getTextureTransformMatrix().m12.roundToInt()

        highlighted = null

        fun recurse(widget: Widget, depth: Int) {
            val text = widget.javaClass.simpleName
            val x = depth * 10
            val width = utilFont.getWidth(text)

            val margin = 2
            val hover = (mousePos.x - x) in 0..width && (mouseY - y) in -margin..height + margin

            if (hover) {
                g.colour = Colour.blue
                highlighted = widget
                g.fillRect(x - 2, y - 2, width + 4, height + 4)
            } else {
                // g.colour = Colour(200, 200, 200, 150)
            }
            utilFont.drawString(x.f, y.f + height, text, Colour.black)

            y += utilFont.lineSpacing

            for (child in widget.children) {
                recurse(child, depth + 1)
            }
        }

        recurse(ui.mainWidget.root, 0)
    }

    private fun reload() {
        loadError = ""
        ui = null
        try {
            doReload()
        } catch (ex: Exception) {
            ex.printStackTrace()
            loadError = ex.localizedMessage
        }
    }

    private fun doReload() {
        ui = Files.newInputStream(filePath).use { SpecDeserialiser(this).load(it) }
    }

    private fun setupFileWatcher() {
        // We can only watch directories, not individual files
        filePath.parent.register(
            fileWatcher,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_CREATE
        )

        reloaderThread = Thread {
            while (true) {
                val key = fileWatcher.take()

                for (event in key.pollEvents()) {
                    val path = event.context() as Path
                    if (!filePath.endsWith(path))
                        continue

                    hasChangeOccurred = true
                }

                val nowValid = key.reset()
                if (!nowValid) {
                    break
                }
            }
            autoReload = false
        }
        reloaderThread!!.name = "auto-reloader"
        reloaderThread!!.isDaemon = true
        reloaderThread!!.start()
    }

    override fun getFont(name: String): SILFontLoader {
        // Always return a copy of the font, so our instance doesn't get broken
        // when a consumer sets their instance's scale property or anything
        // like that.
        fonts[name]?.let { return SILFontLoader(it) }

        val font = SILFontLoader(resourceContext, df, df["fonts/$name.font"])
        fonts[name] = font
        return SILFontLoader(font)
    }

    override fun getImg(path: String): Image {
        images[path]?.let { return it }

        val image = df.readImage(resourceContext, df[path])
        images[path] = image
        return image
    }

    override fun translate(key: String): String? {
        return translator.translations[key]
    }

    override fun getWindowRenderer(): WindowRenderer {
        return windowRenderer
    }

    override fun getDebugOutlineColour(widget: Widget): Colour? {
        // We'll draw a blue highlight on top later for selected widgets
        return if (showOutlines) Colour.yellow else null
    }

    override fun mouseWheelMoved(change: Int) {
        // Keep the pixel the mouse is over the same, to zoom in on what's being hovered.
        val mouseX = mousePos.x - PREVIEW_POS_X
        val mouseY = mousePos.y - PREVIEW_POS_Y
        val pixelX = mouseX / zoomScale + panOffsetX
        val pixelY = mouseY / zoomScale + panOffsetY

        // Updates zoomScale
        zoomLevel += change

        panOffsetX = pixelX - mouseX / zoomScale
        panOffsetY = pixelY - mouseY / zoomScale
    }

    override fun mouseDragged(oldX: Int, oldY: Int, newX: Int, newY: Int) {
        val deltaX = newX - oldX
        val deltaY = newY - oldY

        panOffsetX -= deltaX / zoomScale
        panOffsetY -= deltaY / zoomScale
    }

    private fun updateZoomScale() {
        zoomScale = (1.2f).pow(zoomLevel / 50)
    }

    companion object {
        private const val PREVIEW_POS_X = 200
        private const val PREVIEW_POS_Y = 10

        @JvmStatic
        fun main(args: Array<String>) {
            val filename = args[0]

            val vanilla = VanillaDatafile.createWithDefaultPath()
            val df = Datafile(vanilla, emptyList()) // No mods
            Utils.startSlick(UIEditor(df, filename))
        }
    }
}

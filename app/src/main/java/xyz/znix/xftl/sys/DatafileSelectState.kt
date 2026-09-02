package xyz.znix.xftl.sys

import xyz.znix.xftl.sys.XftlResources

import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.Translator
import xyz.znix.xftl.f
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.replaceArg
import java.nio.file.Files
import java.nio.file.Path

class DatafileSelectState(private val game: MainGame) : MainGame.GameState() {
    private val context = ResourceContext()

    private lateinit var font: SILFontLoader
    private lateinit var translator: Translator

    private lateinit var baseOptions: List<Path>
    private val options = ArrayList<Path>()

    private val optionX = 60
    private var optionBaseY = 0
    private var optionWidth = 0
    private val optionHeight = 25

    private var hoveredOption: Int? = null

    private lateinit var descriptionLines: List<String>

    private var scanThread: Thread? = null
    private var timeToProcessScan: Float = 0f

    @Volatile
    private var scanResult: Path? = null

    @Volatile
    private var hasScanResult = false

    override fun init(container: GameContainer) {
        // Use a badly-converted copy of the Roboto font, since we can't access
        // FTL fonts until we select ftl.dat.
        // Note the font is 1px too high above the baseline, but that doesn't matter.
        val fontData = XftlResources.open("baked/roboto.font")!!.readBytes()
        font = SILFontLoader(context, null, fontData)

        translator = Translator("assets/lang/en.xml")

        baseOptions = FTLFinder.findInstallations()
        options.addAll(baseOptions)

        val description = translator["xftl_select_datafile_desc"]
            .replaceArg(PlatformSpecific.INSTANCE.ftlDatPathFile.toString())
        descriptionLines = font.wrapString(description, 500)
    }

    override fun update(container: GameContainer, delta: Float) {
        // If we're currently scanning, don't keep running the timer - wait
        // three seconds between scans, so if a scan takes longer than that
        // we shouldn't start another one in parallel.
        if (scanThread?.isAlive == true) {
            return
        }

        if (hasScanResult) {
            hasScanResult = false

            options.clear()
            options.addAll(baseOptions)
            scanResult?.let { options.add(it) }
        }

        // Every 3 seconds, check if FTL is running - and if so, grab its path
        // to ftl.dat.
        timeToProcessScan -= delta
        if (timeToProcessScan > 0)
            return
        timeToProcessScan = 3f

        // Scanning can take some time, put it on its own thread.
        scanThread = Thread {
            scanResult = FTLFinder.findRunningInstance()
            hasScanResult = true
        }
        scanThread!!.name = "FTL Instance Scanner"
        scanThread!!.isDaemon = true
        scanThread!!.start()

        println("Scanning for FTL processes, to find their ftl.dat path")
    }

    override fun render(container: GameContainer, g: Graphics) {
        g.clear(Colour.lightGray)
        val input = container.input

        val title = translator["xftl_select_datafile_title"]
        val notFound = translator["xftl_select_datafile_not_found"]

        var y = 30
        font.drawString(30f, y.f, title, Colour.black)
        y += 50

        for (line in descriptionLines) {
            font.drawString(70f, y.f, line, Colour.black)
            y += 20
        }

        y += 30

        g.colour = Colour.black
        g.drawLine(50, y, 530, y)
        y += 40

        if (options.isEmpty()) {
            font.drawString(70f, y.f, notFound, Colour.black)
            return
        }

        hoveredOption = null
        optionBaseY = y
        optionWidth = container.width - optionX - 50
        for ((index, opt) in options.withIndex()) {
            val optY = getOptionY(index)

            val hover = input.mouseX in optionX..optionX + optionWidth
                    && input.mouseY in optY..optY + optionHeight
            if (hover) {
                hoveredOption = index
            }

            g.colour = when (hover) {
                true -> Colour.white
                false -> Colour.lightGray
            }
            g.fillRect(optionX, optY, optionWidth, optionHeight)
            g.colour = Colour.black
            g.drawRect(optionX, optY, optionWidth - 1, optionHeight - 1)

            font.drawString(optionX + 5f, optY + 18f, opt.toString(), Colour.black)
        }
    }

    override fun mouseClicked(button: Int, x: Int, y: Int, clickCount: Int) {
        if (button != Input.MOUSE_LEFT_BUTTON)
            return
        if (clickCount != 1)
            return

        val hovered = hoveredOption ?: return
        val selected = options[hovered].toString()

        Files.write(PlatformSpecific.INSTANCE.ftlDatPathFile, selected.toByteArray())

        game.switchToShipSelect()
    }

    private fun getOptionY(index: Int): Int {
        return optionBaseY + 30 * index
    }

    override fun shutdown() {
        context.freeAll()
    }
}

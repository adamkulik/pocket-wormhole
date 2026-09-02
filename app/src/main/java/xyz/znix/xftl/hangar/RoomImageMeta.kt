package xyz.znix.xftl.hangar

import xyz.znix.xftl.sys.XftlResources

import org.jdom2.input.SAXBuilder
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.systems.SystemBlueprint
import java.io.InputStream
import java.util.regex.Pattern

class RoomImageMeta private constructor(input: InputStream) {
    val roomImages: List<RoomImage>

    init {
        @Suppress("VulnerableCodeUsages") // we set expandEntities
        val builder = com.pocketwormhole.android.SafeXML.builder()
        builder.expandEntities = false
        val doc = builder.build(input)

        roomImages = ArrayList()

        for (elem in doc.rootElement.getChildren("image")) {
            val path = elem.getAttributeValue("path")
            val width = elem.getAttributeValue("width").toInt()
            val height = elem.getAttributeValue("height").toInt()

            // Ignore the cloaking system's glow images
            if (path.endsWith("_glow.png"))
                continue

            val matcher = SYSTEM_NAME_PATTERN.matcher(path)
            require(matcher.matches())
            val systemName = matcher.group("name")

            val doorways = ArrayList<Doorway>()
            for (doorElem in elem.getChildren("doorway")) {
                val x = doorElem.getAttributeValue("x").toInt()
                val y = doorElem.getAttributeValue("y").toInt()
                val isVertical = doorElem.getAttributeValue("vertical")!!.toBoolean()
                doorways += Doorway(ConstPoint(x, y), isVertical)
            }

            var computerPoint: ConstPoint? = null
            var computerDirection: Direction? = null
            elem.getChild("computer")?.let { computerElem ->
                computerPoint = ConstPoint(
                    computerElem.getAttributeValue("x").toInt(),
                    computerElem.getAttributeValue("y").toInt()
                )
                computerDirection = Direction.valueOf(computerElem.getAttributeValue("dir"))
            }

            roomImages += RoomImage(
                path, ConstPoint(width, height), systemName, doorways,
                computerPoint, computerDirection
            )
        }
    }

    companion object {
        private val SYSTEM_NAME_PATTERN = Pattern.compile(".*room_(?<name>[a-z]+)(?:_\\d+)?\\.png")

        // This is a static function to make it harder to accidentally
        // create a new instance.
        fun loadFromResource(): RoomImageMeta {
            val path = "baked/editor-door-data.xml"
            return XftlResources.open(path)!!.use { RoomImageMeta(it) }
        }
    }

    class RoomImage(
        val path: String, val size: ConstPoint, val systemType: String, val doorways: List<Doorway>,
        val computerPoint: ConstPoint?, val computerDirection: Direction?
    ) {
        fun matchesSystem(system: SystemBlueprint): Boolean {
            // Match by the type and not the blueprint name, since for any custom
            // system blueprints they should still use the base system images.
            return system.type == systemType
        }
    }

    class Doorway(val pos: ConstPoint, val isVertical: Boolean)
}

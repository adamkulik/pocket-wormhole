package xyz.znix.xftl.game

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.ai.FriendlyCrewAI
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.Rectangle
import java.util.*

class ShipBlueprint(private val elem: Element, df: Datafile) : Blueprint(elem) {
    val layout: String = elem.getAttributeValue("layout")
    val img: String = elem.getAttributeValue("img")

    // TODO do this properly, to avoid potentially breaking mods
    val isFlagship: Boolean = name.startsWith("BOSS_")

    val isPlayerShip: Boolean = name.startsWith("PLAYER_SHIP_")

    /**
     * The localisation key of this type of ship, shown on the right-hand
     * side of the target panel for enemies.
     */
    val shipClass: GameText? = elem.getGameTextChild("class")

    /**
     * The localisation key of the ship, defining the in-hangar title
     * for player ships (eg 'The Kestrel').
     */
    val shipTitle: GameText? = elem.getGameTextChild("name")

    val weaponSlots: Int? = elem.getChildTextTrim("weaponSlots")?.toInt()
    val droneSlots: Int? = elem.getChildTextTrim("droneSlots")?.toInt()

    val isAutoScout = elem.getChild("crewCount")?.getAttributeValue("amount")?.trim() == "0"

    val maxHealth = elem.getChild("health").getAttributeValue("amount").toInt()

    val startingReactorPower: Int = elem.getChild("maxPower").getAttributeValue("amount").toInt()

    val boardingStrategy: FriendlyCrewAI.BoardingStrategy =
        when (val name = elem.getChildTextTrim("boardingAI")) {
            "invasion" -> FriendlyCrewAI.BoardingStrategy.INVASION
            "sabotage" -> FriendlyCrewAI.BoardingStrategy.SABOTAGE
            null -> FriendlyCrewAI.BoardingStrategy.NONE
            else -> error("Invalid boardingAI tag '$name'")
        }

    // These are the paths to the relevant images, to keep this class lightweight.
    // For hullImage, there's multiple possible image paths - try them
    // and use the first one that exists.
    val hullImage: List<String>
    val floorImage: String?
    val cloakImage: String?

    val shieldImage: String

    // Note that hullOffset doesn't match up with Ship.hullOffset, since it's one
    // has the room offset (times ROOM_SIZE) added to it.
    val hullOffset: ConstPoint
    val floorOffset: ConstPoint
    val cloakOffset: ConstPoint

    val gibs: List<ShipGib>
    val hardpoints: List<ParsedHardpoint>
    val systems: List<ParsedSystem>

    val initialWeapons: List<String>
    val initialDrones: List<String>
    val initialAugments: List<String>

    /**
     * A list of crew specifications, which can each spawn
     * a variable number of crew.
     */
    val initialCrew: List<InitialCrewSpec>

    val initialMissiles: Int
    val initialDroneParts: Int

    // Layout-related stuff
    val rooms: List<ParsedRoom> = ArrayList()
    val doors: List<ParsedDoor> = ArrayList()
    lateinit var shieldEllipse: Rectangle
        private set
    lateinit var roomOffset: ConstPoint
        private set

    init {
        val layoutName = elem.getAttributeValue("layout")
        val layoutElem = df.parseXML(df["data/$layoutName.xml"]).rootElement

        hullImage = listOf(
            "img/ship/${img}_base.png",
            "img/ships_glow/${img}_base.png"
        )
        hullOffset = Utils.parsePosElem(layoutElem.getChild("img"))

        // Note that the stage-2 and stage-3 boss layouts don't have
        // an offsets tag, but everything else does.
        val offsets: Element? = layoutElem.getChild("offsets")

        val floorImageName = elem.getChildTextTrim("floorImage") ?: img
        floorImage = validatePath(df, "img/ship/${floorImageName}_floor.png")
        floorOffset = offsets?.getChild("floor")?.let { Utils.parsePosElem(it) } ?: ConstPoint.ZERO

        // Load the cloak image - if one is set by name use that, otherwise
        // guess based on the ship name (this is required on the Kestrel, for example).
        val customCloakName = elem.getChildTextTrim("cloakImage")
        cloakImage = validatePath(df, "img/ship/${customCloakName ?: img}_cloak.png")
        cloakOffset = offsets?.getChild("cloak")?.let { Utils.parsePosElem(it) } ?: ConstPoint.ZERO

        val overrideShieldImage = elem.getChildTextTrim("shieldImage")
        shieldImage = when (isPlayerShip) {
            true -> "img/ship/${overrideShieldImage ?: img}_shields1.png"
            false -> "img/ship/enemy_shields.png"
        }

        // Load the room/door layout, as it's required for loading the systems.
        parseLayoutTxt(df.readString(df["data/$layout.txt"]))

        gibs = layoutElem.getChild("explosion").children.map { ShipGib(this, it) }

        // Load the hardpoints
        hardpoints = ArrayList()

        for (node in layoutElem.getChild("weaponMounts").children) {
            // In rebel_long.xml the hardpoint direction is missing for some testing stuff.
            // Artillery (used in the boss and federation cruiser) has slide set to 'no'
            val dirName = node.getAttributeValue("slide")?.toUpperCase(Locale.UK) ?: continue

            // To get this into the proper ship space, we have to use the ship's
            // hullOffset, which includes the room offset. This is a bit ugly, but it'll do.
            val pos = Utils.parsePosElem(node) + hullOffset + roomOffset * Constants.ROOM_SIZE

            val dir = if (dirName == "NO") null else dirName.let(Direction::valueOf)
            hardpoints += ParsedHardpoint(
                pos,
                node.getAttributeValue("rotate")!!.toBoolean(),
                node.getAttributeValue("mirror")!!.toBoolean(),
                node.getAttributeValue("gib").toInt(),
                dir
            )
        }

        // Parse the systems
        systems = ArrayList()
        for ((index, systemElem) in elem.getChild("systemList").children.withIndex()) {
            val room = rooms[systemElem.getAttributeValue("room").toInt()]

            val slotElem: Element? = systemElem.getChild("slot")
            val slotDirection: Direction? = slotElem?.getChildTextTrim("direction")
                ?.let { Direction.valueOf(it.toUpperCase(Locale.UK)) }
            val slotNumber: Int? = slotElem?.getChildTextTrim("number")?.toInt()

            systems += ParsedSystem(room, slotNumber, slotDirection, index, systemElem, isPlayerShip)
        }

        // Load the starting equipment

        // Load all the weapon blueprints
        val weaponsElem: Element? = elem.getChild("weaponList")
        if (weaponsElem != null) {
            initialWeapons = weaponsElem.children.map { it.getAttributeValue("name") }
        } else {
            initialWeapons = emptyList()
        }
        initialMissiles = weaponsElem?.getAttributeValue("missiles")?.toInt() ?: 0

        // Load all the drone blueprints
        val dronesElem: Element? = elem.getChild("droneList")
        if (dronesElem != null) {
            initialDrones = dronesElem.children.map { it.getAttributeValue("name") }
        } else {
            initialDrones = emptyList()
        }
        initialDroneParts = dronesElem?.getAttributeValue("drones")?.toInt() ?: 0

        // Load any augments
        initialAugments = elem.getChildren("aug").map { it.getAttributeValue("name") }
        require(initialAugments.size <= Ship.MAX_AUGMENTS) { "Ship $name has too many augments: $initialAugments" }

        // Load the initial crew
        initialCrew = elem.getChildren("crewCount").map {
            val amount = it.requireAttributeValueInt("amount")
            val max = it.getAttributeValue("max")?.toInt()
            val race = it.getAttributeValue("class") ?: "human"
            InitialCrewSpec(amount, race, max)
        }
    }

    private fun parseLayoutTxt(layout: String) {
        // Use this trick so we can mutate the arrays here, but nowhere else (without a cast).
        require(rooms is ArrayList)
        require(doors is ArrayList)

        val l = layout.replace("\r\n", "\n").split('\n')
        var i = 0

        var foundOffsetX = 0
        var foundOffsetY = 0

        var foundVertical = 0
        var foundHorizontal = 0

        fun roomByIdOrNull(id: Int): ParsedRoom? = if (id == -1) null else rooms[id]

        while (i < l.size) {
            val line = l[i++]
            when (line) {
                "X_OFFSET" -> foundOffsetX = l[i++].toInt()
                "Y_OFFSET" -> foundOffsetY = l[i++].toInt()
                "HORIZONTAL" -> foundHorizontal = l[i++].toInt()
                "VERTICAL" -> foundVertical = l[i++].toInt()
                "ELLIPSE" -> {
                    val size = ConstPoint(l[i++].toInt(), l[i++].toInt())
                    val pos = ConstPoint(l[i++].toInt(), l[i++].toInt())
                    shieldEllipse = Rectangle(pos, size)
                }

                "ROOM" -> {
                    val id = l[i++].toInt()
                    check(id == rooms.size)
                    val pos = ConstPoint(l[i++].toInt(), l[i++].toInt())
                    val size = ConstPoint(l[i++].toInt(), l[i++].toInt())
                    rooms += ParsedRoom(id, pos, size)
                    check(rooms[id].id == id)
                }

                "DOOR" -> {
                    val x = l[i++].toInt()
                    val y = l[i++].toInt()
                    val left = roomByIdOrNull(l[i++].toInt())
                    val right = roomByIdOrNull(l[i++].toInt())
                    val vertical = l[i++].toInt() == 1
                    doors += ParsedDoor(ConstPoint(x, y), left, right, vertical)
                }

                "" -> {
                }

                else -> error("Unknown line '$line'")
            }
        }

        // TODO figure out what these are for
        // The run block suppresses the unused variable warning
        run { foundVertical + foundHorizontal }

        roomOffset = ConstPoint(foundOffsetX, foundOffsetY)

        require(this::shieldEllipse.isInitialized) { "Shield ellipse not specified!" }
    }

    // Return the given path if valid, else null.
    private fun validatePath(df: Datafile, path: String): String? {
        if (df.getOrNull(path) == null)
            return null
        return path
    }

    // TODO remove this, after sorting out how the weaponList tags are
    //  loaded for both ShipGenerator and for player ships.
    @Deprecated("Load element attributes in ShipBlueprint directly")
    fun loadElem(): Element {
        return elem
    }

    class ParsedRoom(val id: Int, val pos: IPoint, val size: IPoint)
    class ParsedDoor(val pos: ConstPoint, val left: ParsedRoom?, val right: ParsedRoom?, val isVertical: Boolean)

    class ParsedHardpoint(
        val position: IPoint,
        val rotate: Boolean,
        val mirror: Boolean,
        val gib: Int,
        val slide: Direction?
    )

    class InitialCrewSpec(val amount: Int, val race: String, val max: Int?)

    class ParsedSystem(
        val room: ParsedRoom,

        override val slotNumber: Int?,
        override val slotDirection: Direction?,
        override val systemIndex: Int,

        elem: Element,
        useDefaultImage: Boolean
    ) : ISystemConfiguration {
        // The system name comes from the name of the element declaring it
        override val systemName: String = elem.name

        override val startingPower = elem.getAttributeValue("power").toInt()

        // Note that if not specified, the system is included by default. This
        // is commonly found with enemy ships.
        override val availableByDefault = elem.getAttributeValue("start")?.toBoolean() != false

        override val aiMaxPower: Int? = elem.getAttributeValue("max")?.toInt()

        override val interiorImage: String?

        override val weapon: String? = elem.getAttributeValue("weapon")

        init {
            var imagePath = elem.getAttributeValue("img")?.let { "img/ship/interior/$it.png" }

            // Use the default image, eg for Engi A's pilot and weapons.
            if (imagePath == null && useDefaultImage && systemName != "clonebay" && systemName != "teleporter") {
                imagePath = "img/ship/interior/room_$systemName.png"
            }

            interiorImage = imagePath
        }
    }
}

package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.VisualRandom
import xyz.znix.xftl.environment.*
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.StoreData
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.savegame.ISerialReferencable
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.random.Random

/**
 * Represents a single beacon on the star map.
 */
class Beacon(
    /**
     * The beacon's position on the sector map
     */
    val pos: ConstPoint,

    /**
     * The event at this beacon. Even empty beacons have events, which set stuff like
     * the background and flavour text.
     */
    var event: Event,

    /**
     * True if this is the exit beacon to progress to the next sector.
     */
    val isExit: Boolean,

    /**
     * True if this is the location of the rebel base, in the last stand.
     */
    val isBase: Boolean,

    /**
     * The seed used for this beacon to generate the seeds for everything
     * else (environment, store, etc).
     */
    private val masterSeed: Int,
) : ISerialReferencable {

    /**
     * This beacon's position on the 6x4 grid that all the sectors fit within.
     */
    val gridPos: ConstPoint = pos.divideTruncate(Sector.CELL_SIZE).const

    /**
     * Without long-range scanners, what should this beacon be displayed as?
     *
     * This stores the state of the beacon as the player left it, so they can see if they've previously visited
     * a beacon and if so whether they jumped out of a fight.
     */
    val state: State
        get() = when {
            isOvertaken -> State.OVERTAKEN
            !visited -> State.UNVISITED
            ship != null -> State.VISITED_DANGER
            else -> State.VISITED_CLEAR
        }

    val environmentType: EnvironmentType
        get() {
            val type = event.environment ?: EnvironmentType.NORMAL
            return when {
                !isOvertaken -> type

                // If this beacon has been overtaken, clear any special events,
                // though preserving the nebula-ness of this beacon.
                type == EnvironmentType.NEBULA || type == EnvironmentType.ION_STORM -> EnvironmentType.NEBULA
                else -> EnvironmentType.NORMAL
            }
        }

    /**
     * The sector this beacon resides within.
     *
     * It can only be set once, via [bindSector], and will throw an exception if used before it is set.
     */
    lateinit var sector: Sector
        private set

    /**
     * The beacons connected this beacon on the star map, to which the player can jump from here.
     */
    lateinit var neighbours: List<Beacon>
        private set

    val environmentSeed: Int
    val storeSeed: Int
    val eventSeed: Int

    /**
     * The ship remaining here. Either the player jumped off while fighting it, or it was/became
     * neutral from an event. Currently only the former is supported, when coming back the ship
     * will be gone.
     */
    var ship: Ship? = null
        set(value) {
            field = value

            // Bosses aren't saved at a beacon, since if you jump away and back again they're re-created.
            require(value?.boss == null) { "Cannot save the flagship at a beacon!" }
        }

    /**
     * Has this beacon been previously visited?
     */
    var visited: Boolean = false

    /**
     * Is a store available at this beacon? This isn't the same as event.isStore, as events
     * where a store becomes available as a result of player actions (eg a quest to save
     * a store from the rebels, or saving a merchant that was under attack by pirates) can
     * reveal a store.
     */
    var hasStore: Boolean = event.isStore

    private var internalStore: StoreData? = null

    /**
     * True if the event at this beacon was created by an event.
     *
     * This is used to display the quest label in the map screen,
     * if this beacon is unvisited.
     */
    var hasQuest: Boolean = false

    /**
     * For all the status effects applied at this beacon, this states what
     * the final power limit is for each system by name.
     *
     * Enemy ship effects are left in place and never cleared, while the player
     * can jump between beacons and these need to be preserved.
     */
    val powerLimitEffects = HashMap<String, Int>()

    /**
     * This is a random value between 0-1 that's used to offset
     * the timings of the 'this beacon will be overtaken' flash
     * animation on the beacon map, to ensure all the beacons
     * aren't flashing in sync.
     */
    val overtakeFlashAnimationOffset: Float = VisualRandom.nextFloat()

    var isOvertaken: Boolean = false
        set(value) {
            field = value
            if (value) {
                hasStore = false
                hasQuest = false
            }
        }

    private var actualEnvironment: AbstractEnvironment? = null

    init {
        val rand = Random(masterSeed)
        environmentSeed = rand.nextInt()
        storeSeed = rand.nextInt()
        eventSeed = rand.nextInt()
    }

    fun getStore(game: InGameState): StoreData? {
        if (!hasStore)
            return null
        if (internalStore != null)
            return internalStore

        internalStore = StoreData()
        internalStore!!.generateRandomContents(game, Random(storeSeed))
        return internalStore
    }

    fun bindSector(sector: Sector, neighbours: List<Beacon>) {
        synchronized(this) {
            check(!this::sector.isInitialized) { "Sector already set!" }
            this.sector = sector
            this.neighbours = neighbours
        }
    }

    fun getEnvironment(game: InGameState): AbstractEnvironment {
        actualEnvironment?.let { return it }

        val env = environmentType.create(game, this)
        actualEnvironment = env
        return env
    }

    /**
     * Remove the current environment associated with the beacon, so it'll be
     * re-created next time it's used.
     *
     * This should be called after changing the beacon's event, so it reflects
     * the new event's environment.
     */
    fun clearEnvironment() {
        actualEnvironment = null
    }

    /**
     * FOR USE WITH THE DEBUG CONSOLE ONLY!
     */
    fun debugSetEnvironment(environment: AbstractEnvironment) {
        actualEnvironment = environment
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addObjectId(elem, refs, this)

        SaveUtil.addAttrInt(elem, "x", pos.x)
        SaveUtil.addAttrInt(elem, "y", pos.y)

        SaveUtil.addAttrBool(elem, "visited", visited)
        SaveUtil.addTagBoolIfTrue(elem, "isExit", isExit)
        SaveUtil.addTagBoolIfTrue(elem, "isBase", isBase)
        SaveUtil.addTagBoolIfTrue(elem, "hasStore", hasStore)
        SaveUtil.addTagBoolIfTrue(elem, "hasQuest", hasQuest)

        // Save the event by name - events are immutable and are
        // loaded from the game's XML, so we only need to uniquely
        // identify them, not store all their data.
        SaveUtil.addAttr(elem, "eventId", event.deserialisationId)

        // Save the master seed, so we can regenerate all the other seeds
        // without storing them separately.
        SaveUtil.addAttrInt(elem, "seed", masterSeed)

        // This both marks the set environment, and whether the environment
        // object needs to be deserialised.
        if (actualEnvironment != null) {
            SaveUtil.addAttr(elem, "env", actualEnvironment!!.type.name)
            actualEnvironment!!.saveToXML(elem)
        }

        if (internalStore != null) {
            val storeElem = Element("storeData")
            internalStore!!.saveToXML(storeElem)
            elem.addContent(storeElem)
        }

        // Save the power limits
        for ((system, limit) in powerLimitEffects) {
            val powerLimit = Element("powerLimit")
            powerLimit.setAttribute("system", system)
            powerLimit.setAttribute("limit", limit.toString())
            elem.addContent(powerLimit)
        }

        // If there's a ship at this beacon, save it.
        // This is how the ship the player is fighting is loaded.
        if (ship != null) {
            val shipElem = Element("enemyShip")
            ship!!.saveToXML(shipElem, refs)
            elem.addContent(shipElem)
        }

        // Note we don't save the neighbours - the sector does that using a more
        // compact encoding than what we can easily use.
    }

    companion object Deserialiser {
        fun loadFromXML(elem: Element, refs: RefLoader, game: InGameState): Beacon {
            // To create our beacon, we need to load a few things first.
            // Everything else goes in mutable variables, so we can set them later.
            val eventId = SaveUtil.getAttr(elem, "eventId")
            val seed = SaveUtil.getAttrInt(elem, "seed")
            val event = game.eventManager.getByDeserialisationId(eventId)
            val isExit = SaveUtil.getOptionalTagBool(elem, "isExit") ?: false
            val isBase = SaveUtil.getOptionalTagBool(elem, "isBase") ?: false

            val pos = ConstPoint(
                SaveUtil.getAttrInt(elem, "x"),
                SaveUtil.getAttrInt(elem, "y")
            )

            // This gets us all the information we need to create and start
            // populating our beacon.
            val beacon = Beacon(pos, event, isExit, isBase, seed)
            SaveUtil.registerObjectId(elem, refs, beacon)

            beacon.visited = SaveUtil.getAttrBool(elem, "visited")
            beacon.hasStore = SaveUtil.getOptionalTagBool(elem, "hasStore") ?: false
            beacon.hasQuest = SaveUtil.getOptionalTagBool(elem, "hasQuest") ?: false

            // Load environment-specific data, if we've visited this beacon.
            val envName = elem.getAttributeValue("env")
            val envType = envName?.let { EnvironmentType.valueOf(it) }
            if (envType != null) {
                beacon.actualEnvironment = envType.create(game, beacon)
                beacon.actualEnvironment!!.loadFromXML(elem)
            }

            val storeElem = elem.getChild("storeData")
            if (storeElem != null) {
                beacon.internalStore = StoreData()
                beacon.internalStore!!.loadFromXML(game, storeElem)
            }

            // Deserialise the enemy ship, if present
            val shipElem = elem.getChild("enemyShip")
            beacon.ship = shipElem?.let { game.deserialiseSingleShip(it, refs, null) }

            for (powerLimit in elem.getChildren("powerLimit")) {
                val system = powerLimit.getAttributeValue("system")
                val limit = powerLimit.getAttributeValue("limit").toInt()
                beacon.powerLimitEffects[system] = limit
            }

            return beacon
        }
    }

    enum class State {
        UNVISITED,
        VISITED_CLEAR,
        VISITED_DANGER,

        /**
         * Overtaken by the rebel fleet.
         */
        OVERTAKEN,
    }

    enum class EnvironmentType(
        val isDangerous: Boolean,
        val create: (InGameState, Beacon) -> AbstractEnvironment
    ) {
        NORMAL(false, ::NormalEnvironment),
        ASTEROID(true, ::AsteroidEnvironment),
        SUN(true, ::SunEnvironment),
        PULSAR(true, ::PulsarEnvironment),
        NEBULA(false, { game, beacon -> NebulaEnvironment(game, beacon, false) }),
        ION_STORM(false, { game, beacon -> NebulaEnvironment(game, beacon, true) });

        // TODO how should we represent PDS/ABSes, given they can be targed at the player or enemy (or both?)

        val isNebula: Boolean get() = this == NEBULA || this == ION_STORM
    }
}

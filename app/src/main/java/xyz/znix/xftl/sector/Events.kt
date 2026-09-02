package xyz.znix.xftl.sector

import org.jdom2.Element
import xyz.znix.xftl.GameText
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.*
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.requireAttributeValue
import kotlin.math.absoluteValue
import kotlin.random.Random

// Many of the comments in this file came from ftlwiki.com (now unfortunately
// offline, but accessable via the wayback machine):
// https://ftlwiki.com/wiki/Events_file_structure

/**
 * Represents something that can be used as an event. This is either an Event or
 * an EventList.
 *
 * When this is to be used, [resolve] is called which provides a concrete event.
 */
interface IEvent {
    fun resolve(): Event

    val debugId: String
}

class Event(
    val text: IEventText?, val choices: List<Choice>, elem: Element, override val debugId: String,

    /**
     * A unique ID that can be used to index all the game's events,
     * and load this exact event.
     *
     * This has to handle any event, even unnamed ones that can
     * only be accessed via choices, as it's how the game remembers
     * what event the player had open when they closed the game.
     */
    val deserialisationId: String,

    imageFinder: (String) -> ImageList, loadText: (Element) -> IEventText
) : IEvent {
    val isDistressBeacon: Boolean = elem.getChild("distressBeacon") != null
    val isStore: Boolean = elem.getChild("store") != null

    val itemsModifySteal: Boolean
    val itemsModify: Map<Resource, IntRange>
    val addedCrew: List<AddCrew>
    val removedCrew: List<RemoveCrew>
    val autoRewards: Pair<RewardType, RewardTier>?
    val blueprintRewards: List<String>
    val hullDamage: List<EventHullDamage>
    val systemUpgrades: List<EventSystemUpgrade>

    val modifyPursuit: Int = elem.getChild("modifyPursuit")?.getAttributeValue("amount")?.toInt() ?: 0
    val unlockShip: String? = elem.getChild("unlockShip")?.getAttributeValue("id")

    val boarderRace: String?
    val boarderCount: IntRange

    val statuses: List<EventStatus>
    val removedStuff: List<String>

    /**
     * If this event triggers a quest, this is its event name.
     */
    val questName: String? = elem.getChild("quest")?.getAttributeValue("event")

    val revealMap: Boolean = elem.getChild("reveal_map") != null
    val isLastStandRepair: Boolean = elem.getChild("repair") != null

    val fleetBackground: FleetBackground? = elem.getChildTextTrim("fleet")
        ?.let { name -> FleetBackground.values().first { it.xmlName == name } }

    init {
        // Initialise these in the constructor so we can mutate them,
        // which isn't otherwise possible as they're declared as List.
        addedCrew = ArrayList()
        removedCrew = ArrayList()

        val modElem = elem.getChild("item_modify")
        if (modElem != null) {
            itemsModifySteal = modElem.getAttributeValue("steal")?.toBoolean() ?: false
            val items = HashMap<Resource, IntRange>()
            itemsModify = items

            for (child in modElem.children) {
                check(child.name == "item")
                val type = Resource.byName(child.getAttributeValue("type"))
                val min = child.getAttributeValue("min").toInt()
                val max = child.getAttributeValue("max").toInt()
                items[type] = min..max
            }
        } else {
            itemsModifySteal = false
            itemsModify = emptyMap()
        }

        val crewElem = elem.getChild("crewMember")
        if (crewElem != null) {
            val count = crewElem.getAttributeValue("amount").toInt()

            val race = crewElem.getAttributeValue("class")

            // Although it's not used by vanilla, a literal name
            // can be inserted as the element's text.
            // This is used by Insurrection+, you can check the
            // DISTRESS_STATION_FIRE_RESCUE event to test it.
            val nameId = crewElem.getAttributeValue("id")
            val nameLiteral = crewElem.textTrim
            val name: GameText? = when {
                nameId != null -> GameText.localised(nameId)
                nameLiteral.isNotEmpty() -> GameText.literal(nameLiteral)
                else -> null
            }

            val skills = HashMap<Skill, Float>()
            crewElem.getAttributeValue("all_skills")?.toInt()?.let { level ->
                for (skill in Skill.values()) {
                    // Use 0.5 times so that 1 is fully green, and 2 is fully yellow.
                    skills[skill] = 0.5f * level
                }
            }
            for (skill in Skill.values()) {
                crewElem.getAttributeValue(skill.xmlName)?.toInt()?.let { level ->
                    skills[skill] = 0.5f * level
                }
            }

            for (i in 0 until count) {
                addedCrew.add(AddCrew(race, name, skills))
            }

            // Special-case the crew-removing event used in STATION_SICK
            if (count < 0) {
                for (i in 0 until -count) {
                    // Using an empty clone message hides the dialogue window
                    val emptyText = EventText("")

                    removedCrew.add(RemoveCrew(false, emptyText, null, race == "traitor", this))
                }
            }
        }

        val killCrew = elem.getChild("removeCrew")
        if (killCrew != null) {
            val race = killCrew.getChildText("class")
            val clone = killCrew.getChildText("clone")!!.toBoolean()
            val cloneText = loadText(killCrew.getChild("text"))
            removedCrew.add(RemoveCrew(clone, cloneText, race, false, this))
        }

        val boardersElem = elem.getChild("boarders")
        if (boardersElem != null) {
            // TODO what's the default race?
            boarderRace = boardersElem.getAttributeValue("class") ?: "random"
            val min = boardersElem.getAttributeValue("min").toInt()
            val max = boardersElem.getAttributeValue("max").toInt()
            boarderCount = min..max
        } else {
            boarderRace = null
            boarderCount = 0..0
        }

        val auto = elem.getChild("autoReward")
        autoRewards = if (auto != null) {
            val type = RewardType.valueOf(auto.textTrim.toUpperCase())
            val tier = RewardTier.fromName(auto.getAttributeValue("level"))
            Pair(type, tier)
        } else {
            null
        }

        blueprintRewards = ArrayList()
        for (e in listOf("weapon", "drone", "augment").flatMap(elem::getChildren)) {
            val name = e.requireAttributeValue("name")
            check(e.children.size == 0)
            blueprintRewards += if (name == "RANDOM") "xftl_rand_${e.name.toLowerCase()}" else name
        }
        blueprintRewards.trimToSize()

        hullDamage = ArrayList()
        for (damageElem in elem.getChildren("damage")) {
            val amount = damageElem.getAttributeValue("amount")!!.toInt()
            val system: String? = damageElem.getAttributeValue("system")

            val effect: String? = damageElem.getAttributeValue("effect")
            val isFire = effect == "fire" || effect == "all"
            val isBreach = effect == "breach" || effect == "all"

            hullDamage.add(EventHullDamage(amount, system, isFire, isBreach))
        }

        systemUpgrades = ArrayList()
        for (upgradeElem in elem.getChildren("upgrade")) {
            val amount = upgradeElem.getAttributeValue("amount")!!.toInt()
            val system = upgradeElem.getAttributeValue("system")!!
            systemUpgrades.add(EventSystemUpgrade(amount, system))
        }

        statuses = ArrayList()
        statusLoop@ for (statusElem in elem.getChildren("status")) {
            val op = when (val type = statusElem.getAttributeValue("type")) {
                "limit" -> EventStatus.Operation.LIMIT
                "clear" -> EventStatus.Operation.CLEAR
                "divide" -> EventStatus.Operation.DIVIDE
                "loss" -> EventStatus.Operation.LOSS
                else -> {
                    println("Warning: unimplemented status type '$type' in event '$debugId'")
                    continue@statusLoop
                }
            }

            val target = when (val tgt = statusElem.getAttributeValue("target")) {
                "player" -> EventStatus.Target.PLAYER
                "enemy" -> EventStatus.Target.ENEMY
                else -> {
                    println("Warning: unimplemented target '$tgt' in event '$debugId'")
                    continue@statusLoop
                }
            }

            val system = statusElem.getAttributeValue("system")

            // In the case of type=clear, no amount is needed.
            val amount = statusElem.getAttributeValue("amount")?.toInt() ?: 0

            statuses.add(EventStatus(system, op, target, amount))
        }

        removedStuff = ArrayList()
        for (e in elem.getChildren("remove")) {
            removedStuff += e.requireAttributeValue("name")
        }
    }

    val loadShipName: String?
    val loadShipHostile: Boolean?

    init {
        val ship = elem.getChild("ship")
        if (ship != null) {
            check(ship.children.isEmpty()) { "Inline ship specs not yet supported" }
            loadShipName = ship.getAttributeValue("load")
            loadShipHostile = ship.getAttributeValue("hostile")?.toBoolean()
        } else {
            loadShipName = null
            loadShipHostile = null
        }
    }

    val backImg: ImageList?
    val planetImg: ImageList?

    init {
        val img = elem.getChild("img")
        backImg = img?.getAttributeValue("back")?.let(imageFinder)
        planetImg = img?.getAttributeValue("planet")?.let(imageFinder)
    }

    val environment: Beacon.EnvironmentType?

    init {
        environment = when (val env = elem.getChild("environment")?.requireAttributeValue("type")) {
            "asteroid" -> Beacon.EnvironmentType.ASTEROID
            "nebula" -> Beacon.EnvironmentType.NEBULA
            "pulsar" -> Beacon.EnvironmentType.PULSAR
            "storm" -> Beacon.EnvironmentType.ION_STORM
            "sun" -> Beacon.EnvironmentType.SUN
            "PDS" -> null // TODO implement PDS/ASBs
            null -> null
            else -> error("Unknown environment $env")
        }
    }

    override fun resolve() = this

    /**
     * Evaluate all the random values in this event's rewards, and pack them into a ResourceSet
     */
    fun resolveResources(game: InGameState, rand: Random): ResourceSet {
        // The plain resources (fuel, missiles, etc)
        val resourcesGained = ResourceSet(itemsModify.mapValues { it.value.random(rand) })

        // Add all the blueprints
        resourcesGained.items += blueprintRewards.map { name ->
            when (name) {
                "xftl_rand_weapon" -> game.lootPool.getWeapon(rand)
                "xftl_rand_drone" -> game.lootPool.getDrone(rand)
                "xftl_rand_augment" -> game.lootPool.getAugment(rand)
                else -> game.blueprintManager[name].resolve()
            }
        }

        // Pick names for the new crewmembers
        for (crew in addedCrew) {
            val race = if (crew.race != null) {
                game.blueprintManager[crew.race] as CrewBlueprint
            } else {
                game.lootPool.getRandom(rand) { it is CrewBlueprint } as CrewBlueprint
            }

            val info = if (crew.name != null) {
                // Specifically named by the event (eg Slocknog)
                val name = game.translator[crew.name]
                LivingCrewInfo.generateWithName(race, game, name)
            } else {
                LivingCrewInfo.generateRandom(race, game)
            }

            info.skills.putAll(crew.skills)

            resourcesGained.crew.add(info)
        }

        // Select crewmembers to kill
        for (info in removedCrew) {
            val crew: LivingCrew

            val allCrew = game.player.crew
                .filterIsInstance(LivingCrew::class.java)
                .filter { it.ownerShip == game.player }

            if (allCrew.isEmpty()) {
                println("Warning: Cannot remove crewmember via event, no crew left.")
                continue
            }

            if (info.race == null) {
                crew = allCrew.random()
            } else {
                val ofRace = allCrew.filter { it.blueprint.name == info.race }
                if (ofRace.isEmpty()) {
                    println("Warning: Cannot remove crewmember of race '${info.race}', no crew of that race.")
                    continue
                }
                crew = ofRace.random()
            }

            resourcesGained.lostCrew.add(RemoveCrewEval(crew, info))
        }

        // Spawn boarders
        if (boarderRace != null) {
            val count = boarderCount.random()
            for (i in 0 until count) {
                val race = game.lootPool.getCrewOrRandom(rand, boarderRace)
                resourcesGained.intruders.add(LivingCrewInfo.generateRandom(race, game))
            }
        }

        // For hull damage, we don't need to resolve it - the rooms and systems
        // that are damaged aren't displayed, we only have to evaluate that
        // when the damage is actually applied.
        resourcesGained.damage += hullDamage

        // System upgrades aren't randomised, so we can just copy them over as-is.
        resourcesGained.upgrades += systemUpgrades

        resourcesGained.modifyPursuit = modifyPursuit

        // Add the standard type/tier rewards - these are the standard results and most commonly used
        // eg destroying a ship usually gives STANDARD/MEDIUM rewards.
        if (autoRewards != null) {
            val sector = game.currentBeacon.sector.sectorNumber + 1
            val rewards = LootDropGenerator.generateRewards(game, rand, autoRewards.second, autoRewards.first, sector)
            resourcesGained += rewards
        }

        return resourcesGained
    }

    init {
        // Check for and print out warnings for unknown elements
        for (child in elem.children) {
            if (child.name in KNOWN_TAGS)
                continue

            println("Warning: Unknown element '${child.name}' in event '$debugId'")
        }
    }

    companion object {
        private val KNOWN_TAGS = setOf(
            "distressBeacon", "store", "item_modify",
            "crewMember", "removeCrew", "boarders",
            "autoReward", "weapon", "drone", "augment",
            "ship", "img", "environment", "damage",
            "upgrade", "modifyPursuit", "status",
            "quest", "reveal_map", "remove", "fleet",
            "unlockShip", "repair",

            // Used by the code loading the event
            "text", "choice"
        )
    }
}

class EventList(val name: String, events: List<Lazy<IEvent>>) : IEvent {
    val events by lazy { events.map { it.value } }

    override fun resolve() = events.random().resolve()

    override val debugId: String get() = name
}

class AddCrew(val race: String?, val name: GameText?, val skills: Map<Skill, Float>)

class RemoveCrew(
    /**
     * If true, this crew member will be saved by a clone bay.
     */
    val clone: Boolean,

    /**
     * If the ship has a clone bay, this is the message that either
     * says your crew has been recovered or explains why they haven't.
     *
     * Note: this is empty for the CRYSTAL_HELP_DIG events, we
     * shouldn't display a popup for those.
     */
    val cloneText: IEventText,

    /**
     * If this event removes a crewmember of a specific race, for
     * example the Engi Virus, this specifies it.
     */
    val race: String?,

    /**
     * If true, this crew member is left on the ship and converted
     * to an enemy, which the infectious space station event uses.
     *
     * This is actually set by <crewMember> not <removeCrew>.
     */
    val turnHostile: Boolean,

    /**
     * The event this object belongs to.
     *
     * This is used to uniquely identify a RemoveCrew object during deserialisation.
     */
    val event: Event
)

class EventHullDamage(
    val amount: Int,

    /**
     * The system this damage should be inflicted to.
     *
     * Null means hull damage only, "random" means a random system, and
     * "room" means a random room (regardless of whether it has a system
     * in it or not).
     */
    val system: String?,

    val effectFire: Boolean,
    val effectBreach: Boolean
)

class EventSystemUpgrade(val amount: Int, val system: String)

/**
 * Defines some status effect (or removal thereof) for a ship at the current beacon.
 *
 * This is what's used to limit systems during events, for example
 * the slugs that hack your medbay or oxygen.
 */
class EventStatus(
    /**
     * The name of the system to affect.
     */
    val system: String,

    /**
     * The type of effect to place on the system.
     */
    val op: Operation,

    /**
     * Who is this status applying to?
     */
    val target: Target,

    val amount: Int
) {
    enum class Operation {
        CLEAR, // Remove any prior status effect
        DIVIDE, // Divide the system power by a set amount
        LIMIT, // Limit the system power to at most a set amount
        LOSS, // Subtract the given amount from the system power
    }

    enum class Target { PLAYER, ENEMY }
}

class Choice(val text: IEventText, lazyEvent: Lazy<IEvent>, elem: Element, val deserialisationId: String) {
    /**
     * Event to be triggered when this choice is taken. This element is required. One of:
     * A complete event, as detailed above (which can again contain choices).
     * Empty with just the load attribute given: loads the event with the given id.
     * Completely empty (<element />), if you want nothing to happen at all.
     */
    val event by lazyEvent

    /**
     * true/false, false if omitted. When set to false or omitted, it causes rewards (fuel, drone parts, scrap, etc)
     * in the choice's event (specified by <item_modify> or <autoReward>) to appear next to the choice's text.
     * See req for behavior of hidden when used alongside req.
     */
    val hidden: Boolean = elem.getAttributeValue("hidden")?.toBoolean() ?: false

    /**
     * The name of any race, weapon, drone, augmentation or system/subsystem.
     *
     * When hidden is set to true, the choice will be visible, able to be selected, and will be a blue choice
     *   (unless blue is set to false), if and only if the player has whatever is specified by the req, otherwise
     *   it will not be shown (hidden).
     * When hidden is omitted or set to false and the player does not have the listed req, the player will be able
     *   to see the choice but will be unable to select it (it will be grayed out).
     */
    val req: String? = elem.getAttributeValue("req")

    /**
     * The minimum level of a system required by [req].
     */
    val minLevel: Int? = elem.getAttributeValue("lvl")?.toInt()

    /**
     * The maximum level of a system required by [req].
     *
     * Note this is usually used with (or replaced by) [maxGroup].
     */
    val maxLevel: Int? = elem.getAttributeValue("max_lvl")?.toInt()

    /**
     * This defines a group of choices, only one of which can be displayed.
     *
     * If multiple choices with the same max_group attribute are available, all
     * except the last one (in the order defined in the XML) will be hidden.
     *
     * This behaviour doesn't seem to be documented anywhere, and is largely
     * guessed from the XML.
     */
    val maxGroup: Int? = elem.getAttributeValue("max_group")?.toInt()

    /**
     * true/false, true if omitted. Determines whether the choice will appear as (literally) a blue choice.
     * Only has meaning when used alongside req, as only req can make the choice blue in the first place.
     */
    val blue: Boolean = elem.getAttributeValue("blue")?.toBoolean() ?: (req != null)
}

interface IEventText {
    fun resolve(rand: Random): String
}

class TextList(val name: String, val items: List<IEventText>) : IEventText {
    override fun resolve(rand: Random): String = items.random(rand).resolve(rand)
}

class EventText(val localised: String) : IEventText {
    override fun resolve(rand: Random): String = localised

    // TODO handle the 'planet' and 'back' image names
}

class ImageList(val name: String, val images: List<EnvironmentImage>) {
    // This makes sense in the context of Beacon.getEnvironmentImages
    fun getRandom(seed: Int): EnvironmentImage? {
        if (this == NONE)
            return null

        if (images.isEmpty())
            return null

        return images[seed.absoluteValue % images.size]
    }

    companion object {
        // The special image list 'NONE' can be specified to remove an inherited image
        val NONE = ImageList("NONE", emptyList())
    }
}

/**
 * Represents an image specified in an <imageList>, used for a background or planet.
 */
class EnvironmentImage(val path: String) {
    fun getImg(game: InGameState): Image {
        return game.getImg(path)
    }

    // TODO do the width and height matter?
}

enum class FleetBackground(val xmlName: String) {
    REBEL("rebel"),
    FEDERATION("fed"),
    BOTH("battle")
}

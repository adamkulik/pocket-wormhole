package xyz.znix.xftl.game

import org.jdom2.Element
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.ItemBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.sector.EventHullDamage
import xyz.znix.xftl.sector.EventSystemUpgrade
import xyz.znix.xftl.sector.RemoveCrew
import java.util.*

enum class Resource {
    FUEL,
    MISSILES,
    DRONES,
    SCRAP;

    private var image: Image? = null

    fun getIcon(game: InGameState): Image {
        image?.let { return it }

        val img = game.getImg("img/ui_icons/icon_${name.toLowerCase(Locale.UK)}.png")
        image = img
        return img
    }

    fun getBlueprint(game: InGameState): ItemBlueprint? {
        if (this == SCRAP)
            return null

        return game.blueprintManager.itemBlueprints.getValue(name.toLowerCase(Locale.UK))
    }

    companion object {
        fun byName(name: String): Resource = when (name.toLowerCase(Locale.UK)) {
            "fuel" -> FUEL
            "missiles" -> MISSILES
            "drones" -> DRONES
            "scrap" -> SCRAP

            // ... and then 'missile' is used once by ROCK_STARSHIP_MINE
            "missile" -> MISSILES

            else -> throw IllegalArgumentException("Invalid resource name $name")
        }
    }
}

class ResourceSet() : Map<Resource, Int> {
    var fuel: Int = 0
    var missiles: Int = 0
    var droneParts: Int = 0
    var scrap: Int = 0
    val items = ArrayList<Blueprint>()
    val crew = ArrayList<LivingCrewInfo>()
    val lostCrew = ArrayList<RemoveCrewEval>()
    val intruders = ArrayList<LivingCrewInfo>()
    val damage = ArrayList<EventHullDamage>()
    val upgrades = ArrayList<EventSystemUpgrade>()

    // Should this really be here? It's convenient for the dialogue stuff.
    var modifyPursuit: Int = 0

    val hasAnything: Boolean
        get() = isNotEmpty() || items.isNotEmpty() || crew.isNotEmpty() || lostCrew.isNotEmpty()
                || intruders.isNotEmpty() || damage.isNotEmpty() || upgrades.isNotEmpty() || modifyPursuit != 0

    constructor(basicResources: Map<Resource, Int>) : this() {
        for ((k, v) in basicResources) {
            this[k] = v
        }
    }

    override val entries: Set<Map.Entry<Resource, Int>>
        get() = keys.map { res ->
            object : Map.Entry<Resource, Int> {
                override val key: Resource get() = res
                override val value: Int get() = this@ResourceSet[res]!!
            }
        }.toSet()

    override val keys: Set<Resource>
        get() {
            val hs = HashSet<Resource>(size)
            if (fuel != 0) hs.add(Resource.FUEL)
            if (missiles != 0) hs.add(Resource.MISSILES)
            if (droneParts != 0) hs.add(Resource.DRONES)
            if (scrap != 0) hs.add(Resource.SCRAP)
            return hs
        }

    override val size: Int
        get() {
            var count = 0
            if (fuel != 0) count++
            if (missiles != 0) count++
            if (droneParts != 0) count++
            if (scrap != 0) count++
            return count
        }

    override fun containsKey(key: Resource): Boolean = this[key] != null

    override operator fun get(key: Resource): Int? {
        val value = when (key) {
            Resource.FUEL -> fuel
            Resource.MISSILES -> missiles
            Resource.DRONES -> droneParts
            Resource.SCRAP -> scrap
        }

        return if (value == 0) null else value
    }

    operator fun set(key: Resource, value: Int) {
        when (key) {
            Resource.FUEL -> fuel = value
            Resource.MISSILES -> missiles = value
            Resource.DRONES -> droneParts = value
            Resource.SCRAP -> scrap = value
        }
    }

    fun remove(key: Resource) = set(key, 0)

    override fun isEmpty(): Boolean = size == 0

    // Neither of these make sense for this class
    override val values: Collection<Int> get() = error("Not supported, not very useful")
    override fun containsValue(value: Int): Boolean = error("Unimplemented")

    operator fun plusAssign(other: ResourceSet) {
        this.fuel += other.fuel
        this.scrap += other.scrap
        this.droneParts += other.droneParts
        this.missiles += other.missiles
        this.items += other.items
        this.crew += other.crew
        this.lostCrew += other.lostCrew
        this.intruders += other.intruders
        this.damage += other.damage
        this.modifyPursuit += other.modifyPursuit
    }

    fun clear() {
        fuel = 0
        missiles = 0
        droneParts = 0
        scrap = 0
        items.clear()
        crew.clear()
        lostCrew.clear()
        intruders.clear()
        damage.clear()
        upgrades.clear()
        modifyPursuit = 0
    }

    fun saveToXML(elem: Element, refs: ObjectRefs) {
        for ((name, count) in entries) {
            elem.setAttribute(name.name, count.toString())
        }

        for (item in items) {
            val itemElem = Element("item")
            itemElem.setAttribute("name", item.name)
            elem.addContent(itemElem)
        }

        for (crewMember in crew) {
            val crewElem = Element("addCrew")
            crewElem.setAttribute("race", crewMember.race.name)
            crewElem.setAttribute("humanName", crewMember.name)
            elem.addContent(crewElem)
        }

        for (crewMember in intruders) {
            val crewElem = Element("intruder")
            crewElem.setAttribute("race", crewMember.race.name)
            crewElem.setAttribute("humanName", crewMember.name)
            elem.addContent(crewElem)
        }

        for (removeCrew in lostCrew) {
            val crewElem = Element("removeCrew")
            crewElem.setAttribute("rid", refs[removeCrew.crew])

            // Store the index of the RemoveCrewEval object inside it's event
            val event = removeCrew.info.event
            val index = event.removedCrew.indexOf(removeCrew.info)
            require(index != -1) { "Can't find correct RemoveCrew info on event ${event.deserialisationId}." }
            crewElem.setAttribute("event", event.deserialisationId)
            crewElem.setAttribute("index", index.toString())

            elem.addContent(crewElem)
        }

        for (hullDamage in damage) {
            val damageElem = Element("hullDamage")
            damageElem.setAttribute("amount", hullDamage.amount.toString())
            hullDamage.system?.let { damageElem.setAttribute("system", it) }
            damageElem.setAttribute("fire", hullDamage.effectFire.toString())
            damageElem.setAttribute("breach", hullDamage.effectBreach.toString())
            elem.addContent(damageElem)
        }

        for (upgrade in upgrades) {
            val upgradeElem = Element("upgrade")
            upgradeElem.setAttribute("amount", upgrade.amount.toString())
            upgradeElem.setAttribute("system", upgrade.system)
            elem.addContent(upgradeElem)
        }
    }

    /**
     * Deserialise a saved ResourceSet.
     */
    constructor(elem: Element, refs: RefLoader, game: InGameState) : this() {
        for (res in Resource.values()) {
            val count = elem.getAttributeValue(res.name)?.toInt() ?: 0
            this[res] = count
        }

        for (itemElem in elem.getChildren("item")) {
            val name = itemElem.getAttributeValue("name")
            items += game.blueprintManager[name] as Blueprint
        }

        for (crewElem in elem.getChildren("addCrew")) {
            val race = crewElem.getAttributeValue("race")
            val name = crewElem.getAttributeValue("humanName")
            val blueprint = game.blueprintManager[race] as CrewBlueprint
            crew += LivingCrewInfo.generateWithName(blueprint, game, name)
        }

        for (crewElem in elem.getChildren("intruder")) {
            val race = crewElem.getAttributeValue("race")
            val name = crewElem.getAttributeValue("humanName")
            val blueprint = game.blueprintManager[race] as CrewBlueprint
            intruders += LivingCrewInfo.generateWithName(blueprint, game, name)
        }

        for (crewElem in elem.getChildren("removeCrew")) {
            val objectId = crewElem.getAttributeValue("rid")

            // Find the RemoveCrewEval object by its index into it's event
            val eventId = crewElem.getAttributeValue("event")
            val index = crewElem.getAttributeValue("index")!!.toInt()

            val event = game.eventManager.getByDeserialisationId(eventId)
            val info = event.removedCrew[index]

            refs.asyncResolve(LivingCrew::class.java, objectId) {
                lostCrew += RemoveCrewEval(it!!, info)
            }
        }

        for (damageElem in elem.getChildren("hullDamage")) {
            val amount = damageElem.getAttributeValue("amount")!!.toInt()
            val system: String? = damageElem.getAttributeValue("system")
            val fire = damageElem.getAttributeValue("fire")!!.toBoolean()
            val breach = damageElem.getAttributeValue("breach")!!.toBoolean()
            damage += EventHullDamage(amount, system, fire, breach)
        }

        for (upgradeElem in elem.getChildren("upgrade")) {
            val amount: Int = upgradeElem.getAttributeValue("amount")!!.toInt()
            val system: String = upgradeElem.getAttributeValue("system")!!
            upgrades += EventSystemUpgrade(amount, system)
        }
    }

    companion object {
        fun of(type: Resource, count: Int) = ResourceSet().apply { this[type] = count }
    }
}

class RemoveCrewEval(val crew: LivingCrew, val info: RemoveCrew)

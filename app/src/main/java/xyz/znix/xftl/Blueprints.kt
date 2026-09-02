package xyz.znix.xftl

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.augments.AugEngiMedbots
import xyz.znix.xftl.augments.AugPreigniter
import xyz.znix.xftl.augments.AugZoltanShield
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.*
import kotlin.random.Random

class BlueprintManager {
    val blueprints: Map<String, IBlueprint>
    val itemBlueprints: Map<String, ItemBlueprint>

    private constructor(enableAE: Boolean, loader: (BlueprintManager) -> Unit) {
        blueprints = HashMap()
        itemBlueprints = HashMap()

        // Load all the blueprints
        loader(this)

        // If AE is enabled, rename all the OVERRIDE blueprints to
        // remove that prefix - thus they'll be used instead of the
        // original ones.
        if (enableAE) {
            // Use toList to duplicate the entries list, since we'll
            // be mutating the main blueprints map.
            for ((name, bp) in blueprints.entries.toList()) {
                if (!name.startsWith(AE_PREFIX))
                    continue
                blueprints[name.removePrefix(AE_PREFIX)] = bp
            }
        }

        // Remove the now-redundant override entries - this is probably
        // unnecessary, but it'll ensure we don't end up accidentally
        // using something from AE in non-AE mode.
        val toRemove = blueprints.keys.filter { it.startsWith(AE_PREFIX) }
        for (name in toRemove) {
            blueprints.remove(name)
        }

        // Remove any blueprints from lists that don't actually exist
        for (bp in blueprints.values) {
            if (bp !is BlueprintList) continue
            bp.cleanup()
        }
    }

    constructor(df: Datafile, enableAE: Boolean) : this(enableAE, {
        it.loadFile(df, "blueprints.xml")
        it.loadFile(df, "autoBlueprints.xml")
        it.loadFile(df, "bosses.xml")
        if (enableAE) {
            it.loadFile(df, "dlcBlueprints.xml")
            it.loadFile(df, "dlcBlueprintsOverwrite.xml")
            it.loadFile(df, "dlcPirateBlueprints.xml")
        }
    })

    // Required to bind sounds to the weapons
    fun finishLoading(content: InGameState.GameContent) {
        for (bp in blueprints.values) {
            if (bp !is Blueprint)
                continue

            bp.finishSetup(content)
        }
    }

    operator fun get(name: String): IBlueprint = blueprints[name] ?: error("Unknown blueprint $name")

    fun getOrNull(name: String): IBlueprint? = blueprints[name]

    fun getShip(name: String): ShipBlueprint {
        val blueprint = this[name] as LazyShipBlueprint
        return blueprint.real
    }


    private fun loadFile(df: Datafile, name: String) {
        val file = df["data/$name"]
        val parseXML = df.parseXML(file)
        loadDocument(df, parseXML.rootElement)
    }

    private fun loadDocument(df: Datafile, rootElement: Element) {
        val mutableBlueprints = blueprints as HashMap<String, IBlueprint>
        for (elem in rootElement.children) {
            // The name 'drones' conflicts with the drones system, and it's the only such name
            // conflict. Since we only need them for stores, put them in a separate list.
            if (elem.name == "itemBlueprint") {
                val item = ItemBlueprint(elem)
                (itemBlueprints as HashMap)[item.name] = item
                continue
            }

            val bp = when (elem.name) {
                "blueprintList" -> buildList(elem)
                "weaponBlueprint" -> buildWeaponBlueprint(elem)
                "droneBlueprint" -> buildDroneBlueprint(elem)
                "systemBlueprint" -> buildSystemBlueprint(elem)
                "crewBlueprint" -> buildCrewBlueprint(elem)
                "augBlueprint" -> buildAugmentBlueprint(elem)

                // We don't load the whole ShipBlueprint now for performance
                // reasons, as it loads another XML file.
                "shipBlueprint" -> LazyShipBlueprint(elem, df)

                // Ignore unknown blueprints
                else -> null
            } ?: continue

            val bpName = elem.requireAttributeValue("name")
            if (mutableBlueprints.containsKey(bpName)) {
                println("Warning: duplicate blueprint name '$bpName'")
            }
            mutableBlueprints[bpName] = bp
        }
    }

    private fun buildList(elem: Element): IBlueprint {
        val items = ArrayList<String>()

        for (node in elem.children) {
            check(node.name == "name")
            items += node.textTrim
        }

        return BlueprintList(items, this)
    }

    private fun buildWeaponBlueprint(elem: Element): IBlueprint {
        val type = elem.getChildTextTrim("type")

        return when (type) {
            "LASER" -> LaserBlueprint(elem)
            "MISSILES" -> MissileBlueprint(elem)
            "BEAM" -> BeamBlueprint(elem)
            "BOMB" -> BombBlueprint(elem)
            "BURST" -> FlakBlueprint(elem)
            else -> UknWeaponBlueprint(elem)
        }
    }

    private fun buildDroneBlueprint(elem: Element): IBlueprint {
        return DroneBlueprint(elem)
    }

    private fun buildAugmentBlueprint(elem: Element): IBlueprint {
        return when (val name = elem.getAttributeValue("name")) {
            AugEngiMedbots.NAME -> AugEngiMedbots(elem)
            AugPreigniter.NAME -> AugPreigniter(elem)
            AugZoltanShield.NAME -> AugZoltanShield(elem)

            // Augments that use the default AugmentBlueprint and are implemented
            // as part of another system should be explicitly handled here.
            AugmentBlueprint.AUTOMATED_RELOADERS -> AugmentBlueprint(elem)
            AugmentBlueprint.LONG_RANGE_SCANNERS -> AugmentBlueprint(elem)
            AugmentBlueprint.RECONSTRUCTIVE_TELEPORT -> AugmentBlueprint(elem)
            AugmentBlueprint.OXYGEN_MASKS -> AugmentBlueprint(elem)
            AugmentBlueprint.BACKUP_DNA -> AugmentBlueprint(elem)
            AugmentBlueprint.BATTERY_CHARGER -> AugmentBlueprint(elem)
            AugmentBlueprint.SHIELD_CHARGE_BOOSTER -> AugmentBlueprint(elem)
            AugmentBlueprint.STEALTH_WEAPONS -> AugmentBlueprint(elem)

            else -> {
                println("WARNING: Adding unknown augment '$name'")
                AugmentBlueprint(elem)
            }
        }
    }

    private fun buildSystemBlueprint(elem: Element): IBlueprint {
        return SystemBlueprint(elem)
    }

    private fun buildCrewBlueprint(elem: Element): IBlueprint {
        return CrewBlueprint(elem)
    }

    companion object {
        const val AE_PREFIX = "OVERRIDE_"

        /**
         * Create a version of [BlueprintManager] for use in automated tests,
         * loading a custom set of blueprint XML files.
         */
        fun createForTests(df: Datafile, files: List<Document>): BlueprintManager {
            return BlueprintManager(true) {
                for (doc in files) {
                    it.loadDocument(df, doc.rootElement)
                }
            }
        }
    }
}

interface IBlueprint {
    fun resolve(random: Random = Random): Blueprint
    fun list(): List<Blueprint>
}

class BlueprintList(private val blueprints: ArrayList<String>, private val manager: BlueprintManager) : IBlueprint {
    override fun resolve(random: Random): Blueprint = manager[blueprints.random(random)].resolve(random)
    override fun list(): List<Blueprint> = blueprints.flatMap { manager[it].list() }

    fun cleanup() {
        val toRemove = blueprints.filter { !manager.blueprints.containsKey(it) }

        for (item in toRemove) {
            println("Warning: Removing non-existant blueprint $item from blueprint list!")
            blueprints.remove(item)
        }
    }
}

open class Blueprint(elem: Element) : IBlueprint {
    val name = elem.requireAttributeValue("name")
    val rarity: Int? = elem.getChild("rarity")?.textTrim?.toInt()

    val title = elem.getGameTextChild("title")
    val short = elem.getGameTextChild("short")
    val desc = elem.getGameTextChild("desc")

    // Note the tip is always localised
    val tip = elem.getChildTextTrim("tip")?.let { GameText.localised(it) }

    /**
     * Get this item's purchase price (the price you can buy it at, not the
     * price you sell it at) in scrap. Returns null if the item doesn't
     * have a price.
     */
    open val cost: Int? = null

    fun translateTitle(game: InGameState): String = title?.let { game.translator[it] } ?: "MISSING TITLE: $name"
    fun translateShort(game: InGameState): String = short?.let { game.translator[it] } ?: "MISSING SHORT: $name"

    override fun resolve(random: Random): Blueprint = this
    override fun list(): List<Blueprint> = listOf(this)

    /**
     * Finish setting up this blueprint, loading any other
     * required resources (eg, sounds).
     */
    open fun finishSetup(content: InGameState.GameContent) {
    }
}

/**
 * This is for fuel/drones/missiles, and serves to make their descriptions available.
 */
class ItemBlueprint(elem: Element) : Blueprint(elem) {
    override val cost: Int = elem.getChildTextTrim("cost")!!.toInt()
}

/**
 * This is a stub that's created instead of a real ship blueprint,
 * which is significantly slower to load.
 *
 * Those can and should only be loaded when they're required.
 */
class LazyShipBlueprint(
    private val elem: Element, private val df: Datafile
) : Blueprint(elem) {

    val real: ShipBlueprint by lazy { ShipBlueprint(elem, df) }

    // Load a few bits and pieces that are used in places where
    // we don't need to load the full ship.
    val layout: String = elem.getAttributeValue("layout")
    val img: String = elem.getAttributeValue("img")
    val shipClass: GameText? = elem.getGameTextChild("class")
    val shipTitle: GameText? = elem.getGameTextChild("name")
}

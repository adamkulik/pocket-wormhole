package xyz.znix.xftl.game

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.sector.SectorType
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.min
import kotlin.random.Random

class LootPool(private val bpManager: BlueprintManager, sector: SectorType?) {
    val pool: List<Blueprint>

    init {
        pool = ArrayList()

        val overrides = sector?.rarityOverrides ?: emptyMap()

        for (bp in bpManager.blueprints.values) {
            if (bp !is Blueprint) continue

            // First check if a BP's rarity has been overridden by the sector. If not, use
            // it's standard rarity. If that's not defined then it's inaccessible.
            val rarity = overrides[bp.name] ?: bp.rarity ?: continue

            // See https://subsetgames.com/forum/viewtopic.php?t=33471
            check(rarity in 0..5) { "Invalid rarity $rarity for blueprint ${bp.name}" }
            if (rarity == 0) continue
            val entries = 6 - rarity

            for (i in 1..entries) {
                pool += bp
            }
        }
    }

    fun getWeapon(rand: Random) = getRandom(rand) { it is AbstractWeaponBlueprint } ?: error("No available weapons!")

    fun getDrone(rand: Random) = getRandom(rand) { it is DroneBlueprint } ?: error("No available drones!")

    fun getAugment(rand: Random) = getRandom(rand) { it is AugmentBlueprint } ?: error("No available augments!")

    fun getCrewOrRandom(rand: Random, race: String): CrewBlueprint {
        if (race != "random") {
            return bpManager[race].resolve(rand) as CrewBlueprint
        }

        return getRandom(rand) { it is CrewBlueprint } as CrewBlueprint? ?: error("No available crew!")
    }

    fun getRandom(rand: Random, filter: (Blueprint) -> Boolean): Blueprint? {
        val candidates = pool.asSequence().filter(filter).toList()
        if (candidates.isEmpty()) return null
        return candidates.random(rand)
    }

    fun <T> getManyRandom(type: Class<T>, rand: Random, limit: Int): List<T> {
        val matching = pool.filterIsInstance(type).shuffled(rand)
        return matching.subList(0, min(matching.size, limit))
    }
}

package xyz.znix.xftl.game

import xyz.znix.xftl.rollChance
import kotlin.random.Random

object LootDropGenerator {
    fun generateResource(rand: Random, resource: Resource, tier: RewardTier, sector: Int): ResourceSet {
        val count = when (resource) {
            Resource.FUEL -> generateFuel(rand, tier)
            Resource.MISSILES -> generateMissiles(rand, tier)
            Resource.DRONES -> generateDrones(rand, tier)
            Resource.SCRAP -> generateScrap(rand, tier, sector)
        }
        return ResourceSet.of(resource, count)
    }

    fun generateFuel(rand: Random, tier: RewardTier): Int = FUEL_DROPS[tier.resolve(rand)].random(rand)
    fun generateMissiles(rand: Random, tier: RewardTier): Int = MISSILE_DROPS[tier.resolve(rand)].random(rand)
    fun generateDrones(rand: Random, tier: RewardTier): Int = DRONE_DROPS[tier.resolve(rand)].random(rand)

    /**
     * Generate a scrap reward for a given tier. Note that [sector] starts at zero for the first sector.
     */
    fun generateScrap(rand: Random, tier: RewardTier, sector: Int): Int {
        // Same reward system as FTL
        // Note: it seems that when calculating the scrap rewards, add one sector for easy mode and -1 for hard
        val range = SCRAP_DROP_MULTS[tier.resolve(rand)]
        val multiplier = range.start + rand.nextFloat() * (range.endInclusive - range.start)
        val base = 15 + sector * 6
        return (base * multiplier).toInt()
    }

    fun generateRewards(game: InGameState, rand: Random, tier: RewardTier, type: RewardType, sector: Int): ResourceSet {
        val resources = ResourceSet()
        var remainingType = type

        val fmd = listOf(Resource.FUEL, Resource.MISSILES, Resource.DRONES).shuffled(rand)

        if (remainingType == RewardType.STANDARD) {
            resources.scrap += generateScrap(rand, tier, sector)
            resources += generateResource(rand, fmd[0], RewardTier.LOW, sector)
            resources += generateResource(rand, fmd[1], RewardTier.LOW, sector)
            if (rand.rollChance(3)) remainingType = RewardType.ITEM
        }

        if (remainingType == RewardType.STUFF) {
            resources.scrap += generateScrap(rand, RewardTier.LOW, sector)
            resources += generateResource(rand, fmd[0], tier, sector)
            resources += generateResource(rand, fmd[1], tier, sector)
            if (rand.rollChance(6)) remainingType = RewardType.ITEM
        }

        if (listOf(RewardType.FUEL, RewardType.DRONEPARTS, RewardType.MISSILES).contains(remainingType)) {
            remainingType = RewardType.valueOf(remainingType.name + "_ONLY")
            resources.scrap += generateScrap(rand, tier, sector)
        }

        RewardType.ONLY_RESOURCE_MAPPINGS[remainingType]?.let { resource ->
            resources += generateResource(rand, resource, tier, sector)
        }

        if (remainingType == RewardType.ITEM) {
            remainingType = RewardType.ITEMS.random(rand)
        }

        if (RewardType.ITEMS.contains(remainingType)) {
            resources.scrap += generateScrap(rand, tier, sector)
            resources.items += when (remainingType) {
                RewardType.WEAPON -> game.lootPool.getWeapon(rand)
                RewardType.DRONE -> game.lootPool.getDrone(rand)
                RewardType.AUGMENT -> game.lootPool.getAugment(rand)
                else -> error("Unknown autoReward type: $remainingType")
            }
        }

        return resources
    }

    // These drop tables were... acquired from the FTL binary
    val FUEL_DROPS = listOf(1..3, 2..4, 3..6)
    val MISSILE_DROPS = listOf(1..2, 2..4, 4..8)
    val DRONE_DROPS = listOf(1..1, 1..1, 1..2)
    val SCRAP_DROP_MULTS = listOf(0.5..0.7, 0.8..1.3, 1.3..1.55)
}

enum class RewardTier {
    LOW,
    MEDIUM,
    HIGH,
    RANDOM {
        override fun resolve(rand: Random): Int = (LOW.ordinal..HIGH.ordinal).random(rand)
    };

    open fun resolve(rand: Random): Int = ordinal

    companion object {
        fun fromName(name: String): RewardTier {
            val processed = name.toUpperCase()
            if (processed == "MED") return MEDIUM
            return valueOf(processed)
        }
    }
}

// See https://www.ftlwiki.com/wiki/Events_file_structure
// Note that many reward types give differenet levels for different items - eg,
// the STANDARD reward type gives the specified level in scrap and two resources
// at a low level.
// Thus each reward is specified with 'low', 'medium' or 'high' for a fixed
// reward, or 'spec' if it should use the tier specified by autoReward.
// Also note that 'FMD' stands for Fuel, Missile and Drone - the standard
// resources except scrap.
enum class RewardType {
    STANDARD, // spec scrap, two low FMD resources, 3% item chance
    STUFF, // low scrap, two spec FMD resources, 6% item chance

    // Spec their respective resource, spec scrap
    FUEL,
    MISSILES,
    DRONEPARTS,

    // Spec their respective resource
    FUEL_ONLY,
    MISSILES_ONLY,
    DRONEPARTS_ONLY,
    SCRAP_ONLY,

    // A random item (weapon, augment, drone schematic)
    ITEM,

    // An item of their respective types, spec scrap
    WEAPON,
    AUGMENT,
    DRONE; // (schematic)

    companion object {
        val ONLY_RESOURCE_MAPPINGS = mapOf(
            Pair(FUEL_ONLY, Resource.FUEL),
            Pair(MISSILES_ONLY, Resource.MISSILES),
            Pair(DRONEPARTS_ONLY, Resource.DRONES),
            Pair(SCRAP_ONLY, Resource.SCRAP)
        )

        val ITEMS = listOf(WEAPON, DRONE, AUGMENT)
    }
}

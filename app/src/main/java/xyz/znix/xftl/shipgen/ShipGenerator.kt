package xyz.znix.xftl.shipgen

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.sector.EventManager
import xyz.znix.xftl.sector.IEvent
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.LaserBlueprint
import xyz.znix.xftl.weapons.MissileBlueprint
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.min
import kotlin.random.Random

class ShipGenerator(val df: Datafile, val bp: BlueprintManager) {
    fun buildShip(sys: InGameState, spec: EnemyShipSpec, sector: Int, difficulty: Difficulty, seed: Int? = null): Ship {
        // Shift everything back a sector on easy.
        // This is thus -1 for the first sector on easy.
        val effectiveSector = sector + difficulty.sectorOffset

        // Use a specific random instance to generate this ship, and
        // print out it's seed. This will let us diagnose ships where
        // something went wrong with the generation.
        val effectiveSeed = seed ?: Random.nextInt()
        val rand = Random(effectiveSeed)

        if (seed == null) {
            val seedStr = seedToString(sector, difficulty, effectiveSeed)
            println("Generating ship from spec '${spec.name}' with seed $seedStr")
        }

        val shipBlueprint = (spec.autoBlueprint.resolve(rand) as LazyShipBlueprint).real
        val elem = shipBlueprint.loadElem()

        val ship = Ship(shipBlueprint, sys, null, spec, null)
        ship.loadDefaultContents()

        ship.escapeHealth = spec.escapeHealth?.pick(rand) ?: 0
        ship.surrenderHealth = spec.surrenderHealth?.pick(rand) ?: 0

        // If surrender and escape come to the same health, offset them by 1.
        if (ship.surrenderHealth == ship.escapeHealth) {
            ship.escapeHealth = ship.surrenderHealth - 1
        }

        for (crewSpec in ship.type.initialCrew) {
            // See the link to the guide below, calculated same as system power
            val min = crewSpec.amount
            var max = crewSpec.max

            if (max == null) {
                println("Missing max tag in crewCount tag for generated ship '${ship.name}'")
                max = min
            }

            val baseAmount = min + ((max - min) / 8f * effectiveSector).toInt()

            val crewOverride = spec.crewOverride?.specifiers ?: emptyList()

            if (crewOverride.isEmpty()) {
                for (i in 1..baseAmount) {
                    val crewBP = sys.lootPool.getCrewOrRandom(rand, crewSpec.race)
                    val crewInfo = LivingCrewInfo.generateRandom(crewBP, sys)
                    ship.addCrewMember(crewInfo, true)
                }
            } else {
                for (crew in crewOverride) {
                    val count = when {
                        crew.prop > 0 -> (crew.prop * baseAmount).toInt().coerceAtLeast(1)
                        else -> crew.amount
                    }

                    for (i in 1..count) {
                        val crewBP = sys.lootPool.getCrewOrRandom(rand, crew.race)
                        val crewInfo = LivingCrewInfo.generateRandom(crewBP, sys)
                        ship.addCrewMember(crewInfo, true)
                    }
                }
            }

            // This is used to send the right number of boarders
            // Note we count the crew we added, since for overrides we can
            // get some other number if the prop values don't sum to 1.
            ship.crewAI.initialCrewCount = ship.crew.size
        }

        // Not 100% sure about this, but it looks like we remove zoltan shields
        // on the first sector on easy.
        if (sector == 0 && difficulty == Difficulty.EASY) {
            ship.augments.remove(sys.blueprintManager["ENERGY_SHIELD"])
        }

        // If the ship included any weapons or drones, clear them out because
        // we'll pick them ourselves. This happens if a weapon/drone is set
        // explicitly in weaponList/droneList, rather than via a list.
        for (hp in ship.hardpoints) {
            hp.weapon = null
        }
        ship.drones?.let { drones ->
            for (i in 0 until drones.drones.size) {
                drones.drones[i] = null
            }
        }

        // Calculate the power levels for each system
        // There's a useful Reddit post by someone else who's also been
        // reverse-engineering FTL, which saves us the effort of a lot
        // of the reverse-engineering for shipgen:
        // https://old.reddit.com/r/ftlgame/comments/qu8kz7/details_on_enemy_ship_generation/

        // Pick the power for each category:
        var offensivePower = (effectiveSector + 1).coerceAtLeast(1)
        var defensivePower = (effectiveSector + 2).coerceAtLeast(1)
        var generalPower = ((effectiveSector + 1) / 2).coerceAtLeast(1)

        fun subtractPower(category: SystemCategory, power: Int) = when (category) {
            SystemCategory.OFFENSIVE -> offensivePower -= power
            SystemCategory.DEFENSIVE -> defensivePower -= power
            SystemCategory.GENERAL -> generalPower -= power
        }

        if (difficulty == Difficulty.HARD)
            generalPower++

        // Place the optional systems
        val optionalSystemChance = (sector * 10 + when (difficulty) {
            Difficulty.EASY -> 10
            Difficulty.NORMAL -> 20
            Difficulty.HARD -> 30
        }).coerceAtLeast(10)

        for (system in ship.systemSlots) {
            // Don't install one system by uninstalling another.
            // This also checks if this system is already installed.
            if (system.room.system != null)
                continue

            if (!rand.rollChance(optionalSystemChance))
                continue

            // Install the system
            system.room.setSystem(system)

            // Add a power cost for adding this system.
            // Don't check the current power here, it's allowed to go negative.
            val systemInstance = system.room.system!!
            val category = getSystemCategory(systemInstance)
            val basePowerUse = when {
                category == SystemCategory.OFFENSIVE && systemInstance !is Artillery -> 1
                difficulty == Difficulty.HARD -> 1
                else -> 2
            }
            subtractPower(category, basePowerUse)
        }

        // Make sure each system has at least one power
        // This is a fix for the auto-assault ship which has 0 minimum weapons power
        for (system in ship.systems) {
            if (system.energyLevels == 0) {
                system.energyLevels = 1
            }
        }

        // Find a soft cap for how much power each system can have
        // This can be exceeded by scripted weapons and drones
        val softCaps = HashMap<AbstractSystem, Int>()
        for (system in ship.systems) {
            softCaps[system] = pickSystemPowerLimit(effectiveSector, difficulty, system, rand)
        }

        // Spend the power upgrading the systems

        fun getSystem(category: SystemCategory?): AbstractSystem? {
            val suitable = ship.systems.filter {
                val systemCategory = getSystemCategory(it)
                if (category != null && category != systemCategory)
                    return@filter false

                return@filter it.energyLevels < softCaps.getValue(it)
            }

            if (suitable.isEmpty())
                return null
            return suitable.random(rand)
        }

        while (offensivePower > 0) {
            // Get the system, or stop if we fully upgrade all of them.
            val system = getSystem(SystemCategory.OFFENSIVE) ?: break

            system.energyLevels++
            offensivePower--

            // Note we track the reactor power separately to everything else.
            // It's not necessarily just the sum of all the system powers,
            // and in a plasma storm it would matter.
            ship.purchasedReactorPower++
        }

        while (defensivePower > 0) {
            val system = getSystem(SystemCategory.DEFENSIVE) ?: break

            system.energyLevels++
            defensivePower--
            ship.purchasedReactorPower++
        }

        // Add (or subtract, in the case of negative power) the leftover
        // power from the offensive and defensive pools, and use that
        // to upgrade everything else.
        generalPower += offensivePower
        generalPower += defensivePower

        while (generalPower > 0) {
            val system = getSystem(null) ?: break
            system.energyLevels++
            generalPower--

            if (system is MainSystem) {
                ship.purchasedReactorPower++
            }
        }

        // Select the scripted weapons/drones
        // Note there's no scripted drones in vanilla, but implement it
        // since mods likely use it.
        val weaponOverrides = spec.weaponOverride?.select(rand)?.map { it as AbstractWeaponBlueprint }
        val droneOverrides = spec.droneOverride?.select(rand)?.map { it as DroneBlueprint }

        @Suppress("DuplicatedCode")
        if (weaponOverrides != null) {
            // Check if we need to upgrade the weapon power to fit
            // the scripted weapons.
            val weapons = ship.weapons!!
            val maxWeaponPower = weapons.configuration.spec.aiMaxPower!!
            val scriptedWeaponPower = weaponOverrides.sumBy { it.power }.coerceAtMost(maxWeaponPower)
            val missingPower = scriptedWeaponPower - weapons.energyLevels
            if (missingPower > 0) {
                weapons.energyLevels += missingPower
                ship.purchasedReactorPower += missingPower
            }
            for (weapon in weaponOverrides) {
                ship.addBlueprint(weapon, false)
            }
        }

        @Suppress("DuplicatedCode")
        if (droneOverrides != null) {
            // Same goes for drones.
            val drones = ship.drones!!
            val maxDronePower = drones.configuration.spec.aiMaxPower!!
            val scriptedDronePower = droneOverrides.sumBy { it.power }.coerceAtMost(maxDronePower)
            val missingPower = scriptedDronePower - drones.energyLevels
            if (missingPower > 0) {
                drones.energyLevels += missingPower
                ship.purchasedReactorPower += missingPower
            }
            for (drone in droneOverrides) {
                ship.addBlueprint(drone, false)
            }
        }

        // Spawn the weapons in. Again, see the linked Reddit post near
        // the top of the function, as it explains what this is doing.
        val weaponList = parseWeaponsList(elem)
        var hasHullDamageWeapon = false
        var hasLaserLikeWeapon = false
        while (true) {
            // The under-construction flagship and zoltan peace envoy don't have any weapons.
            val weaponSystem = ship.weapons ?: break

            val usedPower = ship.hardpoints.sumBy { it.weapon?.type?.power ?: 0 }
            val remainingPower = weaponSystem.energyLevels - usedPower

            // Ran out of power?
            if (remainingPower <= 0)
                break

            // Filled all our hardpoints?
            val weaponSlots = ship.weaponSlots ?: ship.hardpoints.size
            if (ship.hardpoints.count { it.weapon != null } >= weaponSlots)
                break

            val suitable = weaponList.filter {
                if (it.power > remainingPower)
                    return@filter false

                // We can't use all the remaining power, except for
                // with a one-power weapon.
                if (it.power == remainingPower && it.power != 1)
                    return@filter false

                // Don't start with one-power weapons
                if (usedPower == 0 && remainingPower >= 3 && it.power < 2)
                    return@filter false

                // This weapon must use >25% of the remaining power
                if (it.power < remainingPower / 4f)
                    return@filter false

                // Pick weapons such that we can:
                // * Do hull damage
                // * Have a laser-style (laser or non-shield-piercing
                //   missile, which is to say a crystal weapon) weapon.

                val (doesHullDamage, isLaserStyle) = getWeaponFlags(it)

                var satisfiesFlags = false

                // If our weapon satisfies either of the unsatisfied
                // flags, allow it.
                if (!hasHullDamageWeapon && doesHullDamage) {
                    satisfiesFlags = true
                }
                if (!hasLaserLikeWeapon && isLaserStyle) {
                    satisfiesFlags = true
                }

                // Do we already have weapons that can do what's required?
                if (hasHullDamageWeapon && hasLaserLikeWeapon) {
                    satisfiesFlags = true
                }

                return@filter satisfiesFlags
            }

            // We should never find ourselves with no suitable weapons,
            // so print a warning if that occurs (along with hopefully
            // enough information to diagnose it).
            if (suitable.isEmpty()) {
                println("Warning: wasn't able to find any more suitable weapons for ship '${ship.name}'")
                println("  Current weapons: " + ship.hardpoints.mapNotNull { it.weapon })
                println("  Weapon system power: ${weaponSystem.energyLevels}")
                break
            }

            val weapon = suitable.random(rand)

            val (doesHullDamage, isLaserStyle) = getWeaponFlags(weapon)
            if (doesHullDamage)
                hasHullDamageWeapon = true
            if (isLaserStyle)
                hasLaserLikeWeapon = true

            require(ship.addBlueprint(weapon, false))

            // Check that it has indeed ended up in a hardpoint.
            require(ship.cargoBlueprints.all { it == null }) {
                "A weapon ended up in the cargo hold! ${ship.cargoBlueprints}"
            }
        }

        // Spawn the drones in. This works along similar lines to the weapons.
        val dronesList = parseDronesList(elem)
        while (true) {
            val drones = ship.drones ?: break

            val dronePower = drones.drones.sumBy { it?.type?.power ?: 0 }
            val remainingPower = drones.energyLevels - dronePower

            if (remainingPower <= 0)
                break

            // Filled all the drone slots?
            if (drones.drones.all { it != null })
                break

            fun findSuitable(fallback: Boolean): List<DroneBlueprint> = dronesList.filter {
                // Must have enough power left
                if (it.power > remainingPower)
                    return@filter false

                // Can't use all the power with a single drone
                if (drones.energyLevels >= 4 && it.power >= drones.energyLevels)
                    return@filter false

                // If we couldn't find any drones with the last two requirements,
                // try again without them.
                if (fallback)
                    return@filter true

                // Avoid duplicates
                if (drones.drones.any { info -> info?.type == it })
                    return@filter false

                // Require combat drones if we don't have proper offensive weapons
                if (!hasHullDamageWeapon || !hasLaserLikeWeapon) {
                    if (it.type != DroneBlueprint.DroneType.COMBAT)
                        return@filter false
                }

                return@filter true
            }

            val suitable = findSuitable(false)
            val drone: DroneBlueprint

            if (suitable.isNotEmpty()) {
                drone = suitable.random(rand)
            } else {
                // If we can't satisfy the last few requirements, try again without them
                val fallback = findSuitable(true)

                if (fallback.isEmpty()) {
                    println("Warning: wasn't able to find any more suitable drones for ship '${ship.name}'")
                    println("  Current drones: " + drones.drones.mapNotNull { it?.type?.name })
                    println("  Drone system power: ${drones.energyLevels}")
                    break
                }

                drone = fallback.random(rand)
            }

            require(ship.addBlueprint(drone, false))
            require(ship.cargoBlueprints.all { it == null }) {
                "A drone ended up in the cargo hold! ${ship.cargoBlueprints}"
            }
        }

        // Make sure we have enough drone parts
        val droneBlueprintCount = ship.drones?.drones?.count { it != null } ?: 0
        if (ship.dronesCount < droneBlueprintCount * 2) {
            ship.dronesCount = droneBlueprintCount * 2
        }

        if (ship.dronesCount == 0 && ship.hacking != null) {
            ship.dronesCount = 5
        }

        return ship
    }

    /**
     * Pick the upper limit for the amount of power given a system can start with.
     *
     * The [effectiveSector] is the sector number with -1 applied for easy (this
     * means that on the first sector of easy, the effective sector number is -1).
     */
    private fun pickSystemPowerLimit(
        effectiveSector: Int, difficulty: Difficulty, system: AbstractSystem, rand: Random
    ): Int {
        // See ShipGenerator::GenerateSystemMaxes
        // Note that the map the data comes from is a libstdc++ red-blue tree,
        // whose datatype is std::pair<int_const,_ShipBlueprint::SystemTemplate>.
        // It may help to define a type putting that after _Rb_tree_node_base.

        val startPower = system.energyLevels

        // In vanilla it's only the flagship and under-construction flagship
        // that lack a maximum power, and the former is not handled by the ship generator.
        // If this is missing, use the system's maximum power.
        val maxPower = system.configuration.spec.aiMaxPower ?: system.blueprint.maxPower

        // Lerp between the min and max power
        val numSectors = 8
        val powerRange = maxPower - startPower
        val baseLimit = startPower + (1f * effectiveSector / numSectors * powerRange).toInt()

        // Add the random offset
        val offsetMax = when {
            // First sector on easy is 0, then 1 for all subsequent sectors
            effectiveSector == -1 -> 0
            difficulty == Difficulty.EASY -> 1

            // Normal/hard is 1, rising to 2 on sector 3 (id=2 when zero-indexed)
            effectiveSector < 3 - 1 -> 2
            else -> 2
        }

        val offset = (0..offsetMax).random(rand)

        return min(baseLimit + offset, maxPower)
    }

    private fun getSystemCategory(system: AbstractSystem): SystemCategory {
        return when (system) {
            is Shields, is Engines, is Cloaking -> SystemCategory.DEFENSIVE
            is Weapons, is Drones, is Teleporter, is Artillery -> SystemCategory.OFFENSIVE
            else -> SystemCategory.GENERAL
        }
    }

    private fun parseWeaponsList(shipElem: Element): List<AbstractWeaponBlueprint> {
        val weaponBlueprints = ArrayList<AbstractWeaponBlueprint>()
        val weaponList = shipElem.getChild("weaponList") ?: return emptyList()

        weaponList.getAttributeValue("load")?.let { listName ->
            weaponBlueprints += bp[listName].list().map { it as AbstractWeaponBlueprint }
        }

        for (node in weaponList.children) {
            val name = node.getAttributeValue("name")
            weaponBlueprints += bp[name] as AbstractWeaponBlueprint
        }

        return weaponBlueprints
    }

    private fun parseDronesList(shipElem: Element): List<DroneBlueprint> {
        val droneBlueprints = ArrayList<DroneBlueprint>()
        val droneList = shipElem.getChild("droneList") ?: return emptyList()

        droneList.getAttributeValue("load")?.let { listName ->
            droneBlueprints += bp[listName].list().map { it as DroneBlueprint }
        }

        for (node in droneList.children) {
            val name = node.getAttributeValue("name")
            droneBlueprints += bp[name] as DroneBlueprint
        }

        return droneBlueprints
    }

    private fun getWeaponFlags(weapon: AbstractWeaponBlueprint): Pair<Boolean, Boolean> {
        val doesHullDamage = weapon.damage != 0
        val isLaserStyle = when (weapon) {
            is LaserBlueprint -> true
            is MissileBlueprint -> weapon.shieldPiercing <= 3
            else -> false
        }

        return Pair(doesHullDamage, isLaserStyle)
    }

    // The categories that power is allocated from
    private enum class SystemCategory {
        OFFENSIVE, DEFENSIVE, GENERAL;
    }

    companion object {
        fun seedToString(sector: Int, difficulty: Difficulty, seed: Int): String {
            // Base64-encode the seed to make it less painful to type in
            val bytes = ByteBuffer.allocate(6)
            bytes.put(sector.toByte())
            bytes.put(difficulty.ordinal.toByte())
            bytes.putInt(seed)
            return Base64.getEncoder().encodeToString(bytes.array()).trim('=')
        }
    }
}

class EnemyShipSpec(elem: Element, bp: BlueprintManager, ev: EventManager) {
    val name = elem.requireAttributeValue("name")

    val escape: IEvent? by loadEvent(elem, ev, name, "escape")
    val surrender: IEvent? by loadEvent(elem, ev, name, "surrender")
    val destroyed: IEvent? by loadEvent(elem, ev, name, "destroyed")
    val deadCrew: IEvent? by loadEvent(elem, ev, name, "deadCrew")
    val gotaway: IEvent? by loadEvent(elem, ev, name, "gotaway")

    // Two ships (TUTORIAL_PIRATE and IMPOSSIBLE_PIRATE) have 'blueprint' attributes instead. While it's
    // unlikely we'll care about them for a long time (if ever), it's nice to load all the ships without
    // having to carry around a list of exceptions.
    val autoBlueprint = bp[elem.getAttributeValue("blueprint") ?: elem.requireAttributeValue("auto_blueprint")]

    val escapeTimer: Float = elem.getChild("escape")?.getAttributeValue("timer")?.toFloat() ?: 15f
    val escapeHealth: RandomWithChance? = elem.getChild("escape")?.let(::RandomWithChance)
    val surrenderHealth: RandomWithChance? = elem.getChild("surrender")?.let(::RandomWithChance)

    val crewOverride: ShipCrewOverride? = elem.getChild("crew")?.let { ShipCrewOverride(it, bp) }
    val weaponOverride: ShipBlueprintOverride? = elem.getChild("weaponOverride")?.let { ShipBlueprintOverride(it, bp) }
    val droneOverride: ShipBlueprintOverride? = elem.getChild("droneOverride")?.let { ShipBlueprintOverride(it, bp) }

    companion object {
        private fun loadEvent(root: Element, ev: EventManager, name: String, evName: String): Lazy<IEvent?> {
            // TODO is there a default destroyed/deadCrew/gotaway event we should use if one hasn't been set?
            val elem = root.getChild(evName) ?: return lazyOf(null)
            return ev.loadEmbeddedEvent(elem, name)
        }
    }
}

class ShipCrewOverride(elem: Element, bp: BlueprintManager) {
    val specifiers: List<Spec> = elem.getChildren("crewMember").map { Spec(it) }

    class Spec(elem: Element) {
        // GHOST_SHIP doesn't set this, so we can't require it
        val race: String = elem.getAttributeValue("type") ?: "human"

        val prop: Float = elem.getAttributeValue("prop")?.toFloat() ?: 0f
        val amount: Int = elem.getAttributeValue("amount")?.toInt() ?: 0
    }
}

/**
 * For <weaponOverride> and <droneOverride> in <ship>.
 */
class ShipBlueprintOverride(elem: Element, bp: BlueprintManager) {
    val blueprints: List<IBlueprint>
    val count: Int

    init {
        blueprints = ArrayList()

        count = elem.getAttributeValue("count").toInt()
        for (nameElem in elem.children) {
            require(nameElem.name == "name")
            blueprints += bp[nameElem.textTrim]
        }
    }

    fun select(rand: Random): List<Blueprint> {
        // Pick the first (count) random blueprints.
        // While we try to pick distinct blueprints, if the same blueprint
        // is resolved twice through separate lists we won't bother to stop that.
        return blueprints.shuffled(rand).subList(0, count).map { it.resolve(rand) }
    }
}

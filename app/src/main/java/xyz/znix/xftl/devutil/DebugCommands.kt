package xyz.znix.xftl.devutil

import org.jdom2.Document
import org.jdom2.input.SAXBuilder
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.*
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Achievement
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.GameOverWindow
import xyz.znix.xftl.game.ShipFamily
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.shipgen.ShipGenerator
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.Damage
import xyz.znix.xftl.weapons.DroneBlueprint
import java.io.IOException
import java.lang.reflect.Parameter
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.stream.Collectors
import kotlin.math.roundToInt
import kotlin.random.Random


class DebugCommands(console: DebugConsole) : ConsoleCommandProvider(console) {
    @ConsoleCommand(name = "rich")
    @CmdHelp("Get a huge amount of scrap, fuel, drones, and missiles")
    private fun cmdRich() {
        addLine("Resources added.")

        ship.scrap = 5000
        ship.fuelCount = 99
        ship.missilesCount = 99
        ship.dronesCount = 99
    }

    @ConsoleCommand(name = "poor")
    @CmdHelp("Remove all our scrap, fuel, drones, and missiles")
    private fun cmdPoor() {
        addLine("Resources removed.")

        ship.scrap = 0
        ship.fuelCount = 0
        ship.missilesCount = 0
        ship.dronesCount = 0
    }

    @ConsoleCommand(name = "scrap")
    @CmdHelp("Gain or lose some amount of scrap")
    private fun cmdScrap(@ParName("amount") amount: Int) {
        game.player.scrap += amount
    }

    @ConsoleCommand(name = "drones")
    @CmdHelp("Gain or lose some amount of drone parts")
    private fun cmdDrones(@ParName("amount") amount: Int) {
        game.player.dronesCount += amount
    }

    @ConsoleCommand(name = "missiles")
    @CmdHelp("Gain or lose some amount of missiles")
    private fun cmdMissiles(@ParName("amount") amount: Int) {
        game.player.missilesCount += amount
    }

    @ConsoleCommand(name = "fuel")
    @CmdHelp("Gain or lose some amount of fuel")
    private fun cmdFuel(@ParName("amount") amount: Int) {
        game.player.fuelCount += amount
    }

    @ConsoleCommand(name = "weapon")
    @CmdHelp("Select a weapon, and add it to the ship's cargo area")
    private fun cmdWeapon(@ParName("name") weapon: AbstractWeaponBlueprint) {
        if (ship.addBlueprint(weapon, false)) {
            addLine("Added weapon ${weapon.translateTitle(game)} to ship inventory.")
        } else {
            addLine("No space in cargo hold, can't add weapon.")
        }
    }

    @ConsoleCommand(name = "drone")
    @CmdHelp("Select a drone, and add it to the ship's cargo area")
    private fun cmdDrone(@ParName("drone") drone: DroneBlueprint) {
        if (ship.addBlueprint(drone, false)) {
            addLine("Added drone ${drone.translateTitle(game)} to ship inventory.")
        } else {
            addLine("No space in cargo hold, can't add drone.")
        }
    }

    @ConsoleCommand(name = "aug")
    @CmdHelp("Select an augment, and add it to the ship's cargo area")
    private fun cmdAugment() {
        console.getAugment { augment ->
            if (ship.addBlueprint(augment, false)) {
                addLine("Added augment ${augment.translateTitle(game)} to ship inventory.")
            } else {
                addLine("No space in cargo hold, can't add augment.")
            }
        }
    }

    @ConsoleCommand(name = "store")
    @CmdHelp("Create a store at this beacon")
    private fun cmdStore() {
        game.currentBeacon.hasStore = true
        game.shipUI.updateButtons()

        addLine("A store is now available at this beacon.")
    }

    @ConsoleCommand(name = "event")
    @CmdHelp("Load an event at this beacon")
    private fun cmdEvent() {
        console.getEvent { event ->
            // Clear any previously-set beacon power limits, left over
            // from a previous event.
            game.currentBeacon.powerLimitEffects.clear()
            game.currentBeacon.event = event.resolve()
            game.currentBeacon.clearEnvironment()
            game.player.updateScriptedPowerLimits()

            game.shipUI.showEventDialogue(game.currentBeacon.event, Random.nextInt())
        }
    }

    @ConsoleCommand(name = "fix")
    @CmdHelp("Fix the ship's hull and all systems, clearing ion damage")
    private fun cmdFix(@CmdVarArg args: List<String>) {
        val targetShip: Ship

        if (args.isEmpty()) {
            targetShip = ship
        } else if (args.size == 1 && args[0].lowercase(Locale.UK) == "enemy") {
            targetShip = game.enemy ?: run {
                addLine("No enemy ship present.")
                return
            }
        } else {
            addLine("Invalid arguments for 'fix' - takes either one argument 'enemy' or no arguments (for the player)")
            return
        }

        for (system in targetShip.systems) {
            system.damagedEnergyLevels = 0
            system.ionTimer = 0f
            system.ionPowerLimit = null
            system.powerLimitChanged()
        }

        for (room in targetShip.rooms) {
            for (idx in room.fires.indices) {
                room.fires[idx] = null
                room.breaches[idx] = null
            }
        }

        targetShip.health = targetShip.maxHealth

        addLine("The ship has been repaired, all regular and ion damage was removed.")
    }

    @ConsoleCommand(name = "cld")
    @CmdHelp("CLear all Drones - destroys all currently-deployed drone instances")
    private fun cmdClearDrones() {
        fun clearFor(target: Ship) {
            val drones = target.drones
            if (drones != null) {
                for (info in drones.drones) {
                    info?.instance?.destroy()
                }
            }

            // Remove the visuals for drones that somehow got stuck.
            // This can happen if you spawn a new ship at the same
            // beacon while the old one had some drones deployed.
            target.externalDrones.clear()

            // Clone the crew list, since we'll be modifying it if
            // we find any drones.
            for (crew in ArrayList(target.crew)) {
                if (crew !is AbstractIndoorsDrone.Pawn)
                    continue

                crew.removeFromShip()
            }

            // Kill the hacking drone, since it's not technically a drone here
            target.hacking?.removeProbe()
        }

        clearFor(ship)
        game.enemy?.let { clearFor(it) }

        addLine("All drones have been cleared from all ships")
    }

    @ConsoleCommand(name = "crew")
    @CmdHelp("Spawn a new crewmember - one argument, the crew race")
    private fun cmdCrew(@ParName("race") race: CrewBlueprint) {
        cmdCrewImpl(race, ship)
    }

    @ConsoleCommand(name = "ecrew")
    @CmdHelp("Spawn a new crewmember on the enemy ship, same args as 'crew'")
    private fun cmdEnemyCrew(@ParName("race") race: CrewBlueprint) {
        val enemy = game.enemy
        if (enemy == null) {
            addLine("No enemy ship!")
            return
        }
        cmdCrewImpl(race, enemy)
    }

    private fun cmdCrewImpl(race: CrewBlueprint, targetShip: Ship, isIntruder: Boolean = false) {
        val info = LivingCrewInfo.generateRandom(race, game)
        targetShip.addCrewMember(info, false, isIntruder)
    }

    @ConsoleCommand(name = "boarder")
    @CmdHelp("Spawn a hostile intruder on the player ship, same args as 'crew'")
    private fun cmdBoarder(@ParName("race") race: CrewBlueprint) {
        cmdCrewImpl(race, ship, isIntruder = true)
    }

    @ConsoleCommand(name = "skills")
    @CmdHelp("Edit the crew's skills")
    private fun cmdSkills() {
        class SkillBox(val x: Int, val y: Int, val width: Int, val height: Int, val crew: LivingCrew, val skill: Skill)

        console.continued = object : DebugConsole.ContinuedCommand {
            val boxes = ArrayList<SkillBox>()
            var dragging: SkillBox? = null

            override val prompt: String get() = "ENTER TO EXIT> "

            override fun run(line: String) {
                // Do nothing, the skill customisation is graphical
            }

            override fun render(gc: GameContainer, g: Graphics, height: Float) {
                super.render(gc, g, height)

                boxes.clear()

                val x = 20
                var y = height.roundToInt() + 5

                val nameIconWidth = 90

                val boxHeight = 30
                val boxWidth = gc.width - x * 2

                val skillWidth = (boxWidth - nameIconWidth) / 6

                // Draw the info text
                g.colour = Colour(55, 55, 55, 180)
                g.fillRect(x.f, y.f, boxWidth.f, 20f)
                console.font.drawString(
                    x + 20f,
                    y + 15f,
                    "Drag to adjust skills, right-click to toggle level",
                    Colour.white
                )
                y += 25

                for (crew in ship.crew) {
                    if (crew !is LivingCrew)
                        continue

                    g.colour = Colour(55, 55, 55, 180)
                    g.fillRect(x.f, y.f, boxWidth.f, boxHeight.f)

                    crew.drawPortrait(x, y, false, 1f)

                    console.font.drawString(x + 30f, y + 20f, crew.info.name, Colour.white)

                    for ((skillId, skill) in Skill.entries.withIndex()) {
                        val skillX = x + nameIconWidth + skillWidth * skillId

                        val icon = game.getImg(skill.iconPath)
                        icon.draw(skillX, y)

                        // We'll re-use this as a slider
                        val barX = skillX + icon.width + 5
                        val barY = y + 10
                        val barWidth = skillWidth - icon.width - 10
                        val barHeight = 8

                        crew.info.drawSkillProgressBar(g, barX, barY, barWidth, barHeight, skill)

                        boxes += SkillBox(barX, y, barWidth, boxHeight, crew, skill)
                    }

                    y += boxHeight + 5
                }
            }

            override fun mouseReleased(button: Int, x: Int, y: Int) {
                dragging = null
            }

            override fun mousePressed(button: Int, x: Int, y: Int) {
                dragging = null
                val box = boxes.firstOrNull { x in it.x..it.x + it.width && y in it.y..it.y + it.height }

                // Right-click toggles between the upper/lower levels
                if (box != null && button == Input.MOUSE_RIGHT_BUTTON) {
                    val newLevel = when (box.crew.getSkillLevel(box.skill)) {
                        SkillLevel.BASE -> 0.5f
                        else -> 0f
                    }
                    box.crew.info.skills[box.skill] = newLevel
                }

                if (button == Input.MOUSE_LEFT_BUTTON) {
                    dragging = box

                    // Make a single click change the value
                    mouseDragged(x, y, x, y)
                }
            }

            override fun mouseDragged(oldX: Int, oldY: Int, newX: Int, newY: Int) {
                val box = dragging ?: return

                val dragProgress = ((newX - box.x) / box.width.f).coerceIn(0f..1f)

                // Dragging doesn't switch between the green/nothing and green/yellow modes
                val oldValue = box.crew.info.skills.getValue(box.skill)
                val newValue = when {
                    oldValue < 0.5f -> (dragProgress / 2f).coerceIn(0f..0.4999f)
                    else -> 0.5f + dragProgress / 2f
                }
                box.crew.info.skills[box.skill] = newValue
            }

            override fun keyPressed(key: Int, c: Char): Boolean {
                // Handle all input to block typing, except for enter to let the user close this.
                return key != Input.KEY_ENTER
            }
        }
    }

    @ConsoleCommand(name = "kill")
    @CmdHelp("Destroy the enemy ship")
    private fun cmdKill() {
        val enemy = game.enemy
        if (enemy == null) {
            addLine("No enemy ship")
            return
        }

        enemy.damage(enemy.rooms.random(), Damage.hullOnly(100))

        // In case nodmg is set, manually set the health to zero.
        enemy.health = 0

        addLine("Added 100 points of damage to the enemy ship")
    }

    @ConsoleCommand(name = "killcrew")
    @CmdHelp("Kill one all of your crewmembers")
    private fun cmdKillCrew() {
        val options = ArrayList<Pair<String, () -> Unit>>()

        val allCrew = ship.crew.mapNotNull { it as? LivingCrew }

        if (allCrew.isEmpty()) {
            addLine("No living crew on this ship.")
            return
        }

        options += Pair("Kill all player crew") {
            for (crew in allCrew) {
                if (crew.ownerShip != ship)
                    continue
                crew.health = 0f
            }
            addLine("Killed all player crew.")
            return@Pair
        }

        if (allCrew.any { it.ownerShip != ship }) {
            options += Pair("Kill all boarders") {
                for (crew in allCrew) {
                    if (crew.ownerShip == ship)
                        continue
                    crew.health = 0f
                }
                addLine("Killed all enemy boarders on player ship.")
                return@Pair
            }
        }

        options += allCrew.map {
            var name = "${it.blueprint.name} - ${it.info.name}"

            if (it.ownerShip != ship) {
                name += " (boarder)"
            }

            Pair(name) {
                it.health = 0f
                addLine("Killed crewmember ${it.info.name} (${it.blueprint.name})")
                return@Pair
            }
        }

        console.pickFromList("KILL CREW", options) { it() }
    }

    @ConsoleCommand(name = "sectors")
    @CmdHelp("Open the sector map, regardless of the current beacon")
    private fun cmdSectors() {
        game.shipUI.openSectorMap()
        addLine("Sector map window opened.")
    }

    @ConsoleCommand(name = "system")
    @CmdHelp("Unlock a system on the current ship, or 'list' or 'all'")
    private fun cmdSystem(
        @ParName("name") @ParType(SystemArgCompleter::class) systemName: String
    ) {

        if (systemName == "list") {
            addLine("Systems on the player ship:")

            val grid = ArrayList<List<String>>()
            for (room in ship.rooms) {
                val system = room.system
                if (system != null) {
                    grid += listOf(system.codename, "(purchased)")
                }

                for (slot in room.systemSlots) {
                    val installed = ship.systems.any { it.blueprint == slot.system }
                    if (!installed) {
                        grid += listOf(slot.system.type, "")
                    }
                }
            }

            console.addLineGrid(grid, 10)
            return
        }

        if (systemName == "all") {
            // Note: ignore the eight-system limit
            for (room in ship.rooms) {
                if (room.system != null)
                    continue

                // If there's multiple systems available (eg medbay and clonebay),
                // just pick the first one.
                val system = room.systemSlots.firstOrNull() ?: continue
                room.setSystem(system)
            }
            addLine("Unlocked all systems on the player ship.")
            return
        }

        val selectedSystem = ship.systemSlots.firstOrNull { it.system.name == systemName }

        if (selectedSystem == null) {
            addLine("No such system named '$systemName' on the current ship, try using 'system list'.")
            return
        }

        if (selectedSystem.isInstalled) {
            addLine("System $systemName is already purchased.")
            return
        }

        selectedSystem.room.setSystem(selectedSystem)

        addLine("Unlocked system $systemName")
    }

    private object SystemArgCompleter : ArgumentTypeProcessor {
        override fun validate(param: Parameter) {
            check(param.type == String::class.java)
        }

        override fun process(value: String, console: DebugConsole): Any {
            return value
        }

        override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter {
            if (previous is BlueprintAndExtrasCompleter && previous.owner == this)
                return previous

            return BlueprintAndExtrasCompleter(
                debugConsole, this, SystemBlueprint::class.java,
                listOf("list", "all")
            )
        }
    }

    @ConsoleCommand(name = "spawn-ship")
    @CmdHelp("Spawn an enemy ship directly from a seed")
    private fun cmdSpawnShip(
        @ParName("spec") spec: EnemyShipSpec,
        @ParName("difficulty/seed") seedB64: String
    ) {
        if (seedB64.getOrNull(1) == '.') {
            // sector.difficulty mode, with a random seed

            // Make the sector one-indexed
            val sector = seedB64[0].toString().toInt() - 1
            if (sector < 0) {
                addLine("In sector.difficulty mode, the sector must be one-indexed.")
                return
            }

            val difficultyStr = seedB64.substring(2)
            val difficulty = Difficulty.entries.firstOrNull { it.name.equals(difficultyStr, ignoreCase = true) }

            if (difficulty == null) {
                val difficultyList = Difficulty.entries.joinToString(", ") { it.name }
                addLine("Invalid difficulty name '$difficultyStr', should be one of: $difficultyList")
                return
            }

            val seed = Random.nextInt()

            game.debugSpawnShip(spec, difficulty, sector, seed)

            val seedStr = ShipGenerator.seedToString(sector, difficulty, seed)
            addLine("Spawning ship from spec '${spec.name}' with seed '$seedStr'")
            return
        }

        val seedBytes = try {
            Base64.getDecoder().decode(seedB64)
        } catch (ex: IllegalArgumentException) {
            addLine("Invalid base64 seed '$seedB64': ${ex.localizedMessage}")
            return
        }

        if (seedBytes.size != 6) {
            addLine("Non-six-byte seed: ${seedBytes.size}")
            return
        }

        val buf = ByteBuffer.wrap(seedBytes)
        val sector = buf.get().toInt()
        val difficulty = Difficulty.entries[buf.get().toInt()]
        val seed = buf.getInt()

        addLine("Spawning ship, and setting it as hostile.")
        game.debugSpawnShip(spec, difficulty, sector, seed)
    }

    @ConsoleCommand(name = "enemy-weapon")
    @CmdHelp("Add or remove (the the remove argument) an enemy weapon")
    private fun cmdEnemyWeapon(@CmdVarArg args: List<String>) {
        if (args.size > 1) {
            addLine("Too many arguments - see the 'help' subcommand.")
            return
        }

        val enemy = game.enemy
        if (enemy == null) {
            addLine("No enemy ship present.")
            return
        }

        if (args.isEmpty() || args[0] == "add") {
            // Pick a weapon and add it to the enemy cargo
            console.getWeapon { weapon ->
                for (i in 0 until (enemy.weaponSlots ?: enemy.hardpoints.size)) {
                    val hp = enemy.hardpoints[i]

                    if (hp.weapon != null)
                        continue

                    hp.weapon = weapon.buildInstance(enemy)
                    enemy.cargoUpdated()
                    addLine("Added weapon ${weapon.name} to enemy hardpoint $i.")
                    return@getWeapon
                }

                addLine("No free hardpoints on the enemy ship")
            }
        } else if (args[0] == "remove") {
            val weapons = enemy.hardpoints.mapNotNull { it.weapon }
            val namedWeapons = weapons.map { Pair(it.type.name, it) }
            console.pickFromList("TO REMOVE", namedWeapons) { toRemove ->
                for (hp in enemy.hardpoints) {
                    if (hp.weapon != toRemove)
                        continue

                    hp.weapon = null
                    enemy.cargoUpdated()
                    addLine("Removed weapon ${toRemove.type.name} from the enemy ship.")
                    return@pickFromList
                }

                addLine("The weapon has already disappeared!?")
            }
        } else if (args[0] == "clear") {
            // Remove all the enemy weapons
            for (hp in enemy.hardpoints) {
                hp.weapon = null
            }
            enemy.cargoUpdated()
            addLine("Removed all enemy weapons")
            return
        } else {
            addLine("Usage: enemy-weapon [add|remove|clear]")
            addLine("The add mode (default if no arguments are set) lets you select a weapon")
            addLine("to give the enemy ship.")
            addLine("The remove mode lets you remove enemy weapons via a list.")
            addLine("The clear mode removes all the enemy weapons.")
            return
        }
    }

    @ConsoleCommand(name = "upall")
    @CmdHelp("UPgrade ALL systems on the player ship to the maximum level")
    private fun cmdUpgradeAll() {
        for (system in ship.systems) {
            system.energyLevels = system.blueprint.maxPower
        }
        ship.purchasedReactorPower = ship.maxReactorPower
        addLine("Upgraded all systems to maximum level")
    }

    @ConsoleCommand(name = "downall")
    @CmdHelp("Downgrade all systems on the player ship to their starting level")
    private fun cmdDowngradeAll() {
        for (system in ship.systems) {
            system.energyLevels = system.blueprint.startPower
        }
        ship.purchasedReactorPower = 5
        addLine("Downgraded all systems to their starting level")
    }

    @ConsoleCommand(name = "set")
    @CmdHelp("Turn on or off debug flags")
    private fun cmdSet(
        @ParName("flag") @ParType(DebugFlagProcessor::class) arg: String
    ) {

        val flagManager = game.debugFlags

        if (arg == "help") {
            addLine("Use 'set <name>' to enable a debug flag, or 'set !<name>' to disable it.")
            addLine("Or use 'set all' or 'set !all' to turn everything on or off.")
            addLine("Use 'set vis' or 'set !vis' to turn all debug visuals on or off.")
            addLine("Valid names:")

            val grid = ArrayList<List<String>>()
            for (flag in flagManager.all) {
                grid += listOf(flag.shortName, flag.set.toString(), flag.fullName, flag.description)
            }

            console.addLineGrid(grid, 15)
            return
        }

        // If the name starts with '!', then it means to turn the effect off
        val status = arg.getOrNull(0) != '!'
        val name = arg.trimStart('!')

        when (name) {
            "all" -> {
                for (flag in flagManager.all) {
                    flag.set = status
                }
                addLine("Set all debug flags to $status")
                return
            }

            "vis" -> {
                for (flag in flagManager.all) {
                    if (!flag.isVisual)
                        continue
                    flag.set = status
                }
                addLine("Set all debug visuals to $status")
                return
            }
        }

        for (flag in flagManager.all) {
            if (flag.shortName != name)
                continue

            flag.set = status
            addLine("Set debug flag '$name' (${flag.fullName}) to $status")
            return
        }

        addLine("Unknown debug flag name '$name', see 'set help' for more information.")
    }

    private object DebugFlagProcessor : ArgumentTypeProcessor {
        override fun validate(param: Parameter) = check(param.type == String::class.java)
        override fun process(value: String, console: DebugConsole): Any = value

        override fun getCompleter(debugConsole: DebugConsole, previous: AutoCompleter?): AutoCompleter {
            if (previous is DebugFlagCompleter && previous.owner == this)
                return previous

            return DebugFlagCompleter(debugConsole, this)
        }
    }

    private class DebugFlagCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?) :
        BasicCompletionEngine<DebugFlagManager.DebugFlag>(console, owner) {

        private var isNegating = false

        override val items get() = console.game.debugFlags.all
        override fun getItemName(item: DebugFlagManager.DebugFlag): String = item.fullName
        override val autoCompleteSuggestion: String?
            get() = bestSuggestion?.let {
                priorCommandText + getCompletionString(it)
            }

        override fun getCompletionString(item: DebugFlagManager.DebugFlag): String {
            var result = item.shortName
            if (isNegating)
                result = "!$result"
            return result
        }

        override fun update(currentToken: String, positionInInput: Int) {
            // Strip out the ! at the start (used to disable a flag), so
            // it doesn't interfere with the search.
            val withoutNegate = currentToken.removePrefix("!")
            isNegating = currentToken.startsWith("!")

            super.update(withoutNegate, positionInInput)
        }

        override fun sortEntries(entries: ArrayList<DebugFlagManager.DebugFlag>, currentToken: String) {
            super.sortEntries(entries, currentToken)

            // Move options that match exactly to the top, if there's enough
            // characters to do so meaningfully.
            if (currentToken.length <= 2)
                return

            val exactMatches = entries.filter { it.shortName.startsWith(currentToken) }.toMutableList()
            exactMatches.sortBy { it.shortName }
            entries.removeAll(exactMatches.toSet())
            entries.addAll(0, exactMatches)
        }
    }

    @ConsoleCommand(name = "damage")
    @CmdHelp("Apply a given amount of damage to the player ship (or negative to heal)")
    private fun cmdDamage(@ParName("amount") amount: Int) {
        ship.health -= amount

        addLine("Applied $amount points of damage to the player ship")
    }

    @ConsoleCommand(name = "hurt")
    @CmdHelp("Damage all of the player's crewmembers by a given amount (negative heals)")
    private fun cmdHurt(@ParName("amount") amount: Int) {
        var count = 0

        for (crew in ship.crew.mapNotNull { it as? LivingCrew }) {
            if (crew.ownerShip != ship)
                continue

            // Route through the engine's real damage entry point rather
            // than writing health directly, so debug damage behaves like
            // gameplay damage (low-health warning, death guard, etc).
            crew.dealDamage(FireDamage(amount.f))
            count++
        }

        addLine("Applied $amount points of damage to $count crewmembers")
    }

    @ConsoleCommand(name = "force-hack")
    @CmdHelp("Forces the enemy to hack a given player system")
    private fun cmdForceHack(@ParName("system") blueprint: SystemBlueprint) {
        val enemy = game.enemy
        if (enemy == null) {
            addLine("No enemy ship.")
            return
        }

        val hacking = enemy.hacking
        if (hacking == null) {
            addLine("The enemy ship doesn't have a hacking system.")
            return
        }

        val system = ship.systems.firstOrNull { it.blueprint == blueprint }
        if (system == null) {
            addLine("No such player system: ${blueprint.name}")
            return
        }

        // Clear the current hacking probe, if it's already been fired.
        hacking.removeProbe()

        hacking.selectTarget(system.room!!)

        // Force the drone to launch, so the AI doesn't get a chance
        // to change the target, in case it updates before
        // the hacking system does.
        hacking.update(0f)

        addLine("Launched hacking probe at player system ${blueprint.name}")
    }

    @ConsoleCommand(name = "super-shield")
    @CmdHelp("Give the player (or enemy) a super-shield (see help sub-cmd)")
    private fun cmdSuperShield(@CmdVarArg args: List<String>) {
        var amount = 5
        var max = 5

        var target: Ship = ship

        // With no arguments, give the player a normal super-shield

        if (args.getOrNull(0) == "help") {
            addLine("Usage: super-shield [amount[/max]] [player|enemy]")
            addLine("The amount should be either a single number (the shield strength), or")
            addLine("two numbers in the form amount/max to set the max super-shield level.")
            addLine("The target ship can be optionally specified, but defaults to the player ship.")
            addLine("When run without arguments, it gives the player a regular (level-5) super-shield.")
            return
        }

        if (args.isNotEmpty()) {
            val parts = args[0].split("/")

            if (parts.size > 2) {
                addLine("Invalid super-shield amount '${args[0]}' - 'see 'super-shield help'.")
                return
            }

            amount = parts[0].toIntOrNull() ?: run {
                addLine("Invalid super-shield amount '${args[0]}' - see 'super-shield help'.")
                return
            }

            if (parts.size == 2) {
                max = parts[1].toIntOrNull() ?: run {
                    addLine("Invalid super-shield max amount '${args[0]}' - see 'super-shield help'.")
                    return
                }
            }
        }

        if (args.size >= 2) {
            target = when (args[1]) {
                "player" -> ship

                "enemy" -> game.enemy ?: run {
                    addLine("No enemy ship present.")
                    return
                }

                else -> {
                    addLine("Invalid target ship '${args[1]}' (should be 'player' or 'enemy') - see 'super-shield help'")
                    return
                }
            }
        }

        // Set the max super-shield first, since the
        // normal value is clamped to it.
        target.maxSuperShield = max
        target.superShield = amount

        addLine("Added $amount/$max super-shield to ship ${target.name}.")
    }

    @ConsoleCommand(name = "env")
    @CmdHelp("Change the environment at the current beacon")
    private fun cmdEnvironment() {
        val items = Beacon.EnvironmentType.entries.map { Pair(it.name, it) }

        console.pickFromList("Environment", items) { type ->
            val beacon = game.currentBeacon
            val environment = type.create(game, beacon)
            beacon.debugSetEnvironment(environment)
        }
    }

    @ConsoleCommand(name = "dump-save")
    @CmdHelp("Save the game to XML, and print it to standard output")
    private fun cmdDumpSave() {
        val doc = try {
            game.saveGameState()
        } catch (ex: Exception) {
            addLine("Exception saving game (more in stdout): $ex")
            ex.printStackTrace()
            return
        }

        val xmlOutput = XMLOutputter(Format.getPrettyFormat())
        val xmlString = xmlOutput.outputString(doc)

        println("Savegame dump:")
        println(xmlString.trim())

        addLine("Savegame dumped to standard output.")
    }

    @ConsoleCommand(name = "save-load")
    @CmdHelp("Save the game to XML, and load it back in.")
    private fun cmdSaveLoad() {
        val successful = game.mainGame.doSaveLoadGame()
        if (!successful) {
            addLine("Failed to reload game, more details are in the console.")
        }
    }

    @ConsoleCommand(name = "gc")
    @CmdHelp("Manually trigger Java's Garbage Collector.")
    private fun cmdGC() {
        System.gc()
        addLine("Finished Java GC operation.")
    }

    @ConsoleCommand(name = "save")
    @CmdHelp("Save the game to a file of a custom name.")
    private fun cmdSave(@ParName("filename") name: String) {
        // First, try serialising the game.
        val doc = try {
            game.saveGameState()
        } catch (ex: Exception) {
            addLine("Exception saving game (more in stdout): $ex")
            ex.printStackTrace()
            return
        }

        // Create the saves directory if it doesn't already exist
        if (!Files.exists(DebugConsole.DEBUG_SAVE_DIR)) {
            try {
                Files.createDirectory(DebugConsole.DEBUG_SAVE_DIR)
            } catch (ex: Exception) {
                ex.printStackTrace()
                addLine("Exception while creating debug save directory!")
                return
            }
        }

        val file = DebugConsole.DEBUG_SAVE_DIR.resolve(name + ".xml")

        fun doSave() {
            val xmlOutput = XMLOutputter(Format.getPrettyFormat())

            try {
                Files.newBufferedWriter(file, Charsets.UTF_8).use { writer ->
                    xmlOutput.output(doc, writer)
                }
            } catch (ex: Exception) {
                println("While writing save to $file")
                ex.printStackTrace()
                addLine("Exception while writing save!")
            }
        }

        // Now check if a file of the same name already exists.
        if (Files.exists(file)) {
            val options = listOf(
                Pair("Yes, overwrite the save", true),
                Pair("No, cancel and don't overwrite the save.", false)
            )
            console.pickFromList("A save with this name exists, overwrite it?", options) {
                if (it) {
                    addLine("Overwriting save '$name'")
                    doSave()
                } else {
                    addLine("Save cancelled.")
                }
            }
        } else {
            addLine("Writing save '$name'")
            doSave()
        }
    }

    @ConsoleCommand(name = "load")
    @CmdHelp("Load a game saved via the 'save' command.")
    private fun cmdLoad() {
        val files: List<Path> = try {
            Files.list(DebugConsole.DEBUG_SAVE_DIR).filter { it.fileName.toString().endsWith(".xml") }.collect(
                Collectors.toList()
            )
        } catch (ex: IOException) {
            ex.printStackTrace()
            addLine("Exception while listing debug saves, see the console for details.")
            addLine("(This may occur if you've never made a debug save, so the folder doesn't exist)")
            return
        }

        if (files.isEmpty()) {
            addLine("No debug saves available.")
            return
        }

        val items = files.map { Pair(it.fileName.toString().removeSuffix(".xml"), it) }

        console.pickFromList("Select save", items) { path ->
            val doc: Document = try {
                Files.newBufferedReader(path).use { reader ->
                    val builder = com.pocketwormhole.android.SafeXML.builder()
                    builder.expandEntities = false
                    builder.build(reader)
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                addLine("Exception while reading save, see the console for details.")
                return@pickFromList
            }

            try {
                game.mainGame.loadSavedGame(doc)
            } catch (ex: Exception) {
                ex.printStackTrace()
                addLine("Exception while loading save, see the console for details.")
                return@pickFromList
            }
        }
    }

    @ConsoleCommand(name = "gameover")
    @CmdHelp("End the game with the win/loose screen.")
    private fun cmdGameOver(@ParName("outcome") outcome: GameOverWindow.Outcome) {
        game.shipUI.showGameOverScreen(outcome)
    }

    @ConsoleCommand(name = "ach-set")
    @CmdHelp("Set the difficulty an achievement was achieved with")
    private fun cmdAchSet(
        @ParName("achievement") ach: Achievement,
        @ParName("difficulty") difficulty: Difficulty
    ) {
        // Get rid of the achievement first, since unlockAchievement won't
        // do anything if we try to decrease the difficulty an achievement
        // was unlocked with.
        game.mainGame.profile.deleteAchievement(ach)

        game.mainGame.profile.unlockAchievement(ach, difficulty)
    }

    @ConsoleCommand(name = "ach-del")
    @CmdHelp("Remove an achievement")
    private fun cmdAchSet(@ParName("achievement") ach: Achievement) {
        game.mainGame.profile.deleteAchievement(ach)
    }

    @ConsoleCommand(name = "ship-unlock")
    @CmdHelp("Set the difficulty a ship family's unlock quest was completed with")
    private fun cmdUnlockShip(
        @ParName("ship") family: ShipFamily,
        @ParName("difficulty") difficulty: Difficulty
    ) {
        if (family.unlockId == null) {
            addLine("Ship family doesn't have an unlock quest")
            return
        }

        // Same as achievements, remove the old higher-difficulty unlock
        game.mainGame.profile.deleteShipUnlock(family)
        game.mainGame.profile.unlockShip(family, difficulty)
    }

    @ConsoleCommand(name = "ship-lock")
    @CmdHelp("Un-completes a ship unlock quest")
    private fun cmdLockShip(@ParName("ship") family: ShipFamily) {
        if (family.unlockId == null) {
            addLine("Ship family doesn't have an unlock quest")
            return
        }

        // Same as achievements, remove the old higher-difficulty unlock
        game.mainGame.profile.deleteShipUnlock(family)
    }

    @ConsoleCommand(name = "reset-ftl")
    @CmdHelp("Reset the player's FTL charge timer")
    private fun cmdResetFTL() {
        ship.ftlChargeProgress = 0f
        addLine("Reset the player's FTL charge progress.")
    }

    @ConsoleCommand(name = "reload-console")
    @CmdHelp("Reload the console (useful with Java HotSwap)")
    private fun cmdReloadConsole() {
        // This is useful for development if you add a new console command
        // and don't want to restart the game - you can use HotSwap to add
        // your changes, but the command map is only created when the debug
        // console is first opened so new commands won't show up.
        //
        // Note the new instance is not created until you re-open the console,
        // so you can use it if that's in some way useful.
        //
        // I would recommend using DCEVM, which is a set of patches to OpenJDK
        // that greatly enhances what you can hot-reload (in particular, you
        // can add, modify and remove fields). The easiest way to use it
        // is to download the JetBrans Runtime (JBR) build of OpenJDK, and
        // use it with the '-XX:+AllowEnhancedClassRedefinition' VM flag.

        game.reloadDebugConsole()

        // Don't bother adding a line, it'll immediately be lost.
    }

    @ConsoleCommand(name = "reload-flags")
    @CmdHelp("Reload the debug flags (useful with Java HotSwap)")
    private fun cmdReloadFlags() {
        // Same idea and notes as the reload-console command. Read its comment.
        game.reloadDebugFlags()
        addLine("Reloaded debug flags")
    }

    @ConsoleCommand(name = "help")
    @CmdHelp("Show the available commands")
    private fun cmdHelp() {
        addLine("Available commands:")

        val grid = ArrayList<List<String>>()
        for (cmd in console.commands) {
            grid += listOf(cmd.name, cmd.helpText)
        }

        console.addLineGrid(grid, 15)
    }
}

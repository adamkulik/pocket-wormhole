package xyz.znix.xftl.game

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.CrewHuman
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.LivingCrewInfo.Companion.generateRandom
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.Event
import xyz.znix.xftl.sector.Sector
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random.Default.nextInt

class FlagshipBoss private constructor(val sector: Sector, val game: InGameState) : BossManager {
    override val serialisationType: String get() = SERIALISATION_TYPE

    override val useBossShipUI: Boolean get() = true
    override val beacon: Beacon get() = realBeacon
    override var nextBeacon: Beacon? = null
        private set
    override var jumping: Boolean = false
        private set

    // Can't make beacon lateinit since the parent is nullable
    private lateinit var realBeacon: Beacon

    private var runningAway: Boolean = false
    private var stage: Int = 1

    /**
     * The Power Surge (GitHub issue #15): stage 2's drone swarm and stage
     * 3's laser barrage/Zoltan-shield restore. Null on stage 1.
     */
    private var powerSurge: PowerSurge? = null

    /**
     * Vanilla's rebel_flagship_base_turns: how many turns the flagship has
     * held the federation base. The jump map shows "4 - baseTurns" turns
     * remaining, and at [BASE_TURNS_TO_DESTROY] the base is destroyed
     * (unless the player is fighting the flagship there).
     */
    override var baseTurns: Int = 0
        private set

    override val isBaseDestroyed: Boolean
        get() = baseTurns >= BASE_TURNS_TO_DESTROY

    /**
     * The flagship from the player's last visit, if they jumped away from the
     * fight mid-stage. Vanilla keeps the flagship's damage and remaining crew
     * between retreats - only a destroyed stage rebuilds it (GitHub issue
     * #86).
     */
    var stashedShip: Ship? = null
        private set

    private var mapIconRotation: Float = (0f..10f).random(VisualRandom)
    private val flagshipIcon = game.getImg("img/map/map_icon_boss.png")

    // These track which crewmembers have been killed
    // They're only updated when you beat a flagship stage
    private var missilesCrew: LivingCrewInfo?
    private var lasersCrew: LivingCrewInfo?
    private var ionCrew: LivingCrewInfo?
    private var beamCrew: LivingCrewInfo?
    private val mainShipCrew = ArrayList<LivingCrewInfo>()

    init {
        // Generate the starting crew
        val human = game.blueprintManager["human"] as CrewBlueprint

        missilesCrew = generateRandom(human, game)
        lasersCrew = generateRandom(human, game)
        ionCrew = generateRandom(human, game)
        beamCrew = generateRandom(human, game)

        // 11 crew in total, generate the non-weapon-room crew by subtracting
        // out the number we're handling with the artillery rooms
        for (i in 1..11 - 4) {
            mainShipCrew += generateRandom(human, game)
        }
    }

    constructor(sector: Sector, game: InGameState, start: Beacon) : this(sector, game) {
        realBeacon = start

        updateNextBeacon()
    }

    override fun createShip(): Ship {
        // Returning to a fight we jumped away from: bring back the same ship,
        // damage and surviving crew included. Vanilla replays no boss intro
        // in this case.
        stashedShip?.let {
            stashedShip = null

            // resetAfterJump cleared the stage-3 Zoltan shield - put it back.
            applyStageSuperShield(it)

            // The surge split is re-rolled when the player returns (FTL
            // wiki: the split stays fixed "unless you jump away and come
            // back").
            powerSurge = makePowerSurge(it)
            return it
        }

        // The ship name is in the format of BOSS_1_NORMAL_DLC
        var shipName = String.format("BOSS_%d_%s", stage, game.difficulty)
        if (game.content.enableAdvancedEdition) {
            shipName += "_DLC"
        }

        val blueprint: ShipBlueprint = game.blueprintManager.getShip(shipName)
        val flagship = Ship(blueprint, game, null, null, this)
        flagship.loadDefaultContents()
        applyStageSuperShield(flagship)

        // Spawn in the crew to the weapon rooms, assuming they're still alive
        // and the rooms still exist.
        addArtilleryCrew(flagship, MISSILE_WEAPON_NAME, missilesCrew)
        addArtilleryCrew(flagship, LASER_WEAPON_NAME, lasersCrew)
        addArtilleryCrew(flagship, BEAM_WEAPON_NAME, beamCrew)
        addArtilleryCrew(flagship, ION_WEAPON_NAME, ionCrew)

        for (info in mainShipCrew) {
            flagship.addCrewMember(info, true)
        }

        // Copied from ShipGenerator, required for stage 1
        if (flagship.dronesCount == 0 && flagship.hacking != null) {
            flagship.dronesCount = 5
        }

        val event: Event = game.eventManager["BOSS_TEXT_$stage"].resolve()
        game.shipUI.showEventDialogue(event, nextInt())

        powerSurge = makePowerSurge(flagship)
        return flagship
    }

    override fun update(dt: Float) {
        // Only tick while this flagship is the ship the player is fighting.
        val enemy = game.enemy ?: return
        if (enemy.boss !== this)
            return

        // A savegame loaded mid-fight has no PowerSurge instance yet
        // (createShip isn't called on load).
        if (powerSurge == null)
            powerSurge = makePowerSurge(enemy)

        powerSurge?.update(dt)
    }

    override fun drawWarnings(g: Graphics) {
        powerSurge?.renderWarning(g)
    }

    override fun drawMapIcon(g: Graphics, centre: IPoint) {
        // Graphics.rotate takes degrees, so this is degrees/second.
        val rotationSpeed = MAP_ICON_ROTATION_SPEED // Go around every 20 seconds
        mapIconRotation += game.renderingDeltaTime * rotationSpeed

        // These offsets are approximate.
        g.pushTransform()
        g.rotate(centre.x.f, centre.y.f, -mapIconRotation)
        flagshipIcon.draw(centre.x - 8, centre.y - 32)
        g.popTransform()
    }

    override fun advanceFleet() {
        if (!jumping) {
            // Jump every second turn, this turn wasn't a jump.
            // (except if the flagship doesn't want to jump, when
            //  it's at the base - this is when the next beacon is null.)
            updateNextBeacon()
            jumping = nextBeacon != null

            // Sitting on the federation base: each further turn counts
            // towards its destruction (vanilla rebel_flagship_base_turns).
            if (realBeacon == sector.finishBeacon) {
                baseTurns = min(baseTurns + 1, BASE_TURNS_TO_DESTROY)
            }
        } else {
            realBeacon = nextBeacon!!
            updateNextBeacon()

            // Don't jump again next turn
            jumping = false

            // If we were running away, we're allowed to go back to the base again.
            runningAway = false

            // Arriving at the base starts the countdown at one (vanilla
            // immediately shows "3 turns until the base is destroyed").
            // Anywhere else clears it.
            baseTurns = if (realBeacon == sector.finishBeacon) 1 else 0
        }
    }

    override fun drawJumpArc(g: Graphics, from: IPoint, to: IPoint) {
        val width = 10f
        val angle = atan2(from.y.f - to.y, from.x.f - to.x)
        val tangentX = cos(angle + PIf / 2f) * width / 2
        val tangentY = sin(angle + PIf / 2f) * width / 2

        g.drawCustomQuads { renderer ->
            renderer.pushVert(from.x + tangentX, from.y + tangentY, Constants.BEACON_LINE_FLAGSHIP)
            renderer.pushVert(from.x - tangentX, from.y - tangentY, Constants.BEACON_LINE_FLAGSHIP)
            renderer.pushVert(to.x - tangentX, to.y - tangentY, Constants.BEACON_LINE_FLAGSHIP)
            renderer.pushVert(to.x + tangentX, to.y + tangentY, Constants.BEACON_LINE_FLAGSHIP)
        }

        // Draw the animated flagship on top of it.
        // Note these numbers are approximate.
        val period = 1_500_000_000
        val progress: Float = (System.nanoTime() % period).toFloat() / period
        val movement = 20f + progress * 20f
        val alpha = min(1f, 2f - progress * 2f)

        g.pushTransform()
        g.rotate(from.x.f, from.y.f, Math.toDegrees(angle.toDouble()).toFloat() - 90f)
        flagshipIcon.draw(from.x - 32f, from.y - 32f - movement, Colour(1f, 1f, 1f, alpha))
        g.popTransform()
    }

    override fun bossShipKilled(enemy: Ship) {
        // The stage was destroyed - the next one is a fresh ship.
        stashedShip = null
        powerSurge = null
        enemy.clearSuperDrones()

        updateNextBeacon()

        // If we're not at the base, continue on our path there.
        // We have to set the is-jumping flag, otherwise the flagship
        // could wait at the same beacon as the player.
        if (nextBeacon != null) {
            jumping = true
        } else {
            // The flagship is at the base, force it to jump away.
            val adjacent: List<Beacon> = beacon.neighbours
            nextBeacon = adjacent[nextInt(adjacent.size)]
            runningAway = true
            jumping = true
        }


        if (isFinalStage(stage)) {
            // The retail game shows no BOSS_DESTROYED dialogue here: the
            // credits (vanilla's thank-you blurb and the backer roll) run
            // straight after the explosion, followed by the victory screen.
            // The run is over at this point, so the port deletes its save.
            game.markRunEnded(true)
            game.shipUI.showVictoryCredits()
        } else {
            stage++

            // The event when you defeat a stage
            val event: Event = game.eventManager["BOSS_ESCAPED"].resolve()
            game.shipUI.showEventDialogue(event, nextInt())
        }

        // Update the remaining living crew
        missilesCrew = getArtilleryCrewInfo(enemy, MISSILE_WEAPON_NAME)
        lasersCrew = getArtilleryCrewInfo(enemy, LASER_WEAPON_NAME)
        beamCrew = getArtilleryCrewInfo(enemy, BEAM_WEAPON_NAME)
        ionCrew = getArtilleryCrewInfo(enemy, ION_WEAPON_NAME)

        mainShipCrew.clear()
        for (crew in enemy.crew) {
            if (crew !is CrewHuman)
                continue
            if (crew.ownerShip != enemy)
                continue

            mainShipCrew.add(crew.info)
        }

        // Don't include the artillery crew in the main crew list, or they'll be duplicated
        missilesCrew?.let { mainShipCrew.remove(it) }
        lasersCrew?.let { mainShipCrew.remove(it) }
        ionCrew?.let { mainShipCrew.remove(it) }
        beamCrew?.let { mainShipCrew.remove(it) }
    }

    /**
     * The third-stage flagship starts with a full 12-point Zoltan shield.
     * Vanilla hard-codes this in BossShip::StartStage (no ship augment is
     * involved), so it is not in the BOSS_3 blueprint.
     */
    private fun applyStageSuperShield(ship: Ship) {
        applyStageSuperShield(ship, stage)
    }

    /**
     * Create the surge controller for the current stage, or null for
     * stages without one (stage 1). Recreating it when the player jumps
     * away and returns re-rolls the stage-2 drone split and restarts the
     * stage-3 surge cadence, matching #176's behaviour.
     */
    private fun makePowerSurge(ship: Ship): PowerSurge? {
        if (stage != PowerSurge.DRONE_SURGE_STAGE && stage != PowerSurge.BARRAGE_SURGE_STAGE)
            return null
        return PowerSurge(game, ship, stage)
    }

    private fun updateNextBeacon() {
        // If the flagship is running away, don't touch its next beacon.
        // That'll have been set when it was marked as running away.
        if (runningAway) {
            return
        }

        // If the flagship is at the rebel base, it'll stay there.
        if (beacon == sector.finishBeacon) {
            nextBeacon = null
            return
        }

        val path = sector.findShortestPath(beacon, sector.finishBeacon)
        require(path != null) { "Flagship has no path to the federation base!" }

        nextBeacon = path.first()
    }

    private fun getArtilleryRoom(ship: Ship, weaponName: String): Room? {
        return ship.artillery.firstOrNull { it.configuration.spec.weapon == weaponName }?.room
    }

    private fun addArtilleryCrew(ship: Ship, weaponName: String, info: LivingCrewInfo?) {
        if (info == null)
            return
        val room = getArtilleryRoom(ship, weaponName) ?: return

        val crew = ship.addCrewMember(info, true)
        crew.jumpTo(room, ConstPoint.ZERO)
    }

    private fun getArtilleryCrewInfo(ship: Ship, weaponName: String): LivingCrewInfo? {
        val room = getArtilleryRoom(ship, weaponName) ?: return null
        val crew = room.crew.filterIsInstance<CrewHuman>().filter { it.ownerShip == ship }
        return crew.firstOrNull()?.info
    }

    /**
     * Park the ship the player just jumped away from, so it can be restored
     * when they come back (GitHub issue #86). Called by InGameState when
     * leaving a boss fight.
     */
    override fun stashShip(ship: Ship) {
        stashedShip = ship
    }

    /**
     * Debug/testing hook: move this boss to the given beacon. Only touches
     * testing state - the fight itself spawns when the player arrives.
     */
    fun debugTeleportTo(beacon: Beacon) {
        realBeacon = beacon
        updateNextBeacon()
        jumping = false
    }

    /**
     * Debug/testing hook: jump straight to the given stage, so the later
     * stages can be tested without playing through the earlier ones. Any
     * ship stashed from a previous retreat is discarded so the selected
     * stage spawns fresh.
     */
    fun debugSetStage(stage: Int) {
        this.stage = stage.coerceIn(1, FINAL_STAGE)
        stashedShip = null
    }

    /**
     * Debug/testing hook: park the flagship on the federation base with the
     * given number of turns already counted (1 = "3 turns left", 4 = the
     * base is destroyed unless the player is there).
     */
    fun debugPlaceAtBase(turns: Int) {
        realBeacon = sector.finishBeacon
        nextBeacon = null
        jumping = false
        runningAway = false
        baseTurns = turns.coerceIn(0, BASE_TURNS_TO_DESTROY)
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrRef(elem, "beacon", refs, beacon)
        SaveUtil.addAttrRef(elem, "next", refs, nextBeacon)
        SaveUtil.addAttrBool(elem, "jumping", jumping)
        SaveUtil.addAttrBool(elem, "runningAway", runningAway)
        SaveUtil.addAttrInt(elem, "stage", stage)
        SaveUtil.addAttrInt(elem, "baseTurns", baseTurns)

        // The ship the player retreated from mid-stage (GitHub issue #86).
        // Registered first, exactly like saveGameState registers the live
        // boss ship before saving it.
        stashedShip?.let {
            refs.register(it, "stashedBossShip")
            val stashElem = Element("stashedShip")
            it.saveToXML(stashElem, refs)
            elem.addContent(stashElem)
        }

        fun saveCrew(info: LivingCrewInfo?, name: String) {
            if (info == null)
                return

            val crewElem = Element(name)
            elem.addContent(crewElem)

            info.saveToXML(crewElem, false)
        }

        saveCrew(missilesCrew, "missileCrewInfo")
        saveCrew(lasersCrew, "laserCrewInfo")
        saveCrew(beamCrew, "beamCrewInfo")
        saveCrew(ionCrew, "ionCrewInfo")

        for (crew in mainShipCrew) {
            val crewElem = Element("mainCrew")
            elem.addContent(crewElem)

            crew.saveToXML(crewElem, false)
        }
    }

    override fun loadFromXML(elem: Element, refs: RefLoader) {
        SaveUtil.getAttrRef(elem, "beacon", refs, Beacon::class.java) { realBeacon = it!! }
        SaveUtil.getAttrRef(elem, "next", refs, Beacon::class.java) { nextBeacon = it }
        jumping = SaveUtil.getAttrBool(elem, "jumping")
        runningAway = SaveUtil.getAttrBool(elem, "runningAway")
        stage = SaveUtil.getAttrInt(elem, "stage")
        baseTurns = (elem.getAttributeValue("baseTurns")?.toInt() ?: 0)
            .coerceIn(0, BASE_TURNS_TO_DESTROY)

        val human = game.blueprintManager["human"] as CrewBlueprint

        fun loadCrew(name: String): LivingCrewInfo? {
            val crewElem = elem.getChild(name) ?: return null
            return LivingCrewInfo.loadFromXMLWithRace(crewElem, human, game)
        }

        missilesCrew = loadCrew("missileCrewInfo")
        lasersCrew = loadCrew("laserCrewInfo")
        beamCrew = loadCrew("beamCrewInfo")
        ionCrew = loadCrew("ionCrewInfo")

        mainShipCrew.clear()
        for (crewElem in elem.getChildren("mainCrew")) {
            mainShipCrew += LivingCrewInfo.loadFromXMLWithRace(crewElem, human, game)
        }

        elem.getChild("stashedShip")?.let {
            stashedShip = game.deserialiseSingleShip(it, refs, null)
        }
    }

    companion object {
        const val SERIALISATION_TYPE = "wormhole.flagship"

        /**
         * Once the flagship has spent this many turns on the federation
         * base it destroys it (vanilla's rebel_flagship_base_turns == 4).
         */
        const val BASE_TURNS_TO_DESTROY = 4

        // Force the deserialiser to use this function rather than making the constructor public,
        // as it makes it very clear that this function isn't supposed to be used for anything ele.
        fun createForDeserialisation(sector: Sector, game: InGameState): FlagshipBoss {
            return FlagshipBoss(sector, game)
        }

        /**
         * The number of stages the vanilla flagship is fought over. Only
         * destroying the last one wins the game (GitHub issue #178).
         */
        internal const val FINAL_STAGE = 3


        /** True if destroying the given stage ends the game in victory. */
        internal fun isFinalStage(stage: Int): Boolean = stage >= FINAL_STAGE

        /** The stage that gets the boss-sized Zoltan shield (vanilla). */
        internal const val SUPER_SHIELD_STAGE = 3

        /** The third-stage flagship's Zoltan shield strength (vanilla). */
        internal const val SUPER_SHIELD_STRENGTH = 12

        /**
         * Apply the stage's starting super-shield to a freshly-created (or
         * restored) flagship. Split out so the test kit can exercise it.
         */
        internal fun applyStageSuperShield(ship: Ship, stage: Int) {
            if (stage != SUPER_SHIELD_STAGE)
                return

            // Set the maximum first: the current value is clamped to it.
            ship.maxSuperShield = SUPER_SHIELD_STRENGTH
            ship.superShield = SUPER_SHIELD_STRENGTH
        }

        private const val LASER_WEAPON_NAME = "ARTILLERY_BOSS_1"
        private const val MISSILE_WEAPON_NAME = "ARTILLERY_BOSS_2"
        private const val BEAM_WEAPON_NAME = "ARTILLERY_BOSS_3"
        private const val ION_WEAPON_NAME = "ARTILLERY_BOSS_4"
    }
}

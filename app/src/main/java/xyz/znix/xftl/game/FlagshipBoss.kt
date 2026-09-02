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
        // The ship name is in the format of BOSS_1_NORMAL_DLC
        var shipName = String.format("BOSS_%d_%s", stage, game.difficulty)
        if (game.content.enableAdvancedEdition) {
            shipName += "_DLC"
        }

        val blueprint: ShipBlueprint = game.blueprintManager.getShip(shipName)
        val flagship = Ship(blueprint, game, null, null, this)
        flagship.loadDefaultContents()

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

        return flagship
    }

    override fun drawMapIcon(g: Graphics, centre: IPoint) {
        val rotationSpeed = TWO_PI / 20f // Go around every 20 seconds
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
        } else {
            realBeacon = nextBeacon!!
            updateNextBeacon()

            // Don't jump again next turn
            jumping = false

            // If we were running away, we're allowed to go back to the base again.
            runningAway = false
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

        if (stage == 3) {
            game.shipUI.showGameOverScreen(GameOverWindow.Outcome.WIN)
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

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrRef(elem, "beacon", refs, beacon)
        SaveUtil.addAttrRef(elem, "next", refs, nextBeacon)
        SaveUtil.addAttrBool(elem, "jumping", jumping)
        SaveUtil.addAttrBool(elem, "runningAway", runningAway)
        SaveUtil.addAttrInt(elem, "stage", stage)

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
    }

    companion object {
        const val SERIALISATION_TYPE = "wormhole.flagship"

        // Force the deserialiser to use this function rather than making the constructor public,
        // as it makes it very clear that this function isn't supposed to be used for anything ele.
        fun createForDeserialisation(sector: Sector, game: InGameState): FlagshipBoss {
            return FlagshipBoss(sector, game)
        }

        private const val LASER_WEAPON_NAME = "ARTILLERY_BOSS_1"
        private const val MISSILE_WEAPON_NAME = "ARTILLERY_BOSS_2"
        private const val BEAM_WEAPON_NAME = "ARTILLERY_BOSS_3"
        private const val ION_WEAPON_NAME = "ARTILLERY_BOSS_4"
    }
}

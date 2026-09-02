package xyz.znix.xftl.crew

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.PlayerShipUI
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.systems.*
import java.util.*
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Represents a crew member that can be hired by the player.
 */
abstract class LivingCrew(blueprint: CrewBlueprint, anims: Animations, room: Room, mode: SlotType) :
    AbstractCrew(blueprint, anims, room) {

    override val repairSpeed: Float get() = 1f + REPAIR_SKILL_BONUS[getSkillLevel(Skill.REPAIRS).ordinal] / 100f

    override val attackDamageMult: Float get() = 1f + COMBAT_SKILL_BONUS[getSkillLevel(Skill.COMBAT).ordinal] / 100f

    open val isMindControlResistant: Boolean get() = false

    var info: LivingCrewInfo = LivingCrewInfo.generateRandom(blueprint, room.ship.sys)

    /**
     * The ship that 'owns' this crewmember, or null if they were
     * spawned in by an intruder event.
     */
    var ownerShip: Ship? = if (mode == SlotType.CREW) room.ship else null

    /**
     * The mind control system that is actively controlling this crewmember.
     */
    var mindControlledBy: MindControl? = null

    /**
     * The position the player saved this crewmember to, for the
     * return-to-stations button.
     *
     * This is only set for player crew, and is in the ship room (not
     * ship pixel) coordinate systetm.
     */
    var savedPosition: ConstPoint? = null

    private var mindControlAnimation: FTLAnimation? = null

    override val playerControllable: Boolean
        get() = mindControlledBy == null && ownerShip?.isPlayerShip == true

    override val showRedOutline: Boolean get() = ownerShip?.isPlayerShip != true

    // If a crewmember is mind controlled, you have vision of it whether
    // it belongs to the player or not.
    override val providesPlayerVision: Boolean
        get() = ownerShip?.isPlayerShip == true || mindControlledBy != null

    @Suppress("SENSELESS_COMPARISON")
    override val backImg: Image?
        get() = when {
            // This seems unnecessary since info is non-nullable, but it's read
            // in the AbstractCrew constructor.
            info == null -> super.backImg

            info.isFemale -> game.getImg("img/people/female_color.png")
            else -> super.backImg
        }

    override val mode: SlotType
        get() {
            var side = when (room.ship) {
                ownerShip -> SlotType.CREW
                else -> SlotType.INTRUDER
            }

            if (mindControlledBy != null) {
                side = side.other
            }

            return side
        }

    override val suffocationMultiplier: Float
        get() {
            getAugmentValue(AugmentBlueprint.OXYGEN_MASKS)?.let { return it }
            return 1f
        }

    override fun update(dt: Float) {
        // Update the mind-control status now, so it applies for
        // the rest of the update cycle.
        if (mindControlledBy?.isControlling(this) != true) {
            mindControlledBy = null
            mindControlAnimation = null
        } else {
            mindControlAnimation?.update(dt)
        }

        super.update(dt)
    }

    override fun drawForeground(g: Graphics) {
        super.drawForeground(g)

        if (mindControlledBy == null)
            return

        // Draw the mind-control animation
        if (mindControlAnimation == null) {
            mindControlAnimation = game.animations["mindcontrol"].startLooping(game)
        }

        val filter = when (showRedOutline) {
            true -> Colour.red
            false -> Colour.green
        }

        if (showHealthBar) {
            // Draw to the left of the health bar
            mindControlAnimation!!.draw(screenX.f - 11, screenY.f - 3, filter)
        } else {
            // Draw in the centre, above the crew
            mindControlAnimation!!.draw(screenX.f + 9, screenY.f, filter)
        }
    }

    override fun drawImage(x0: Float, y0: Float, x1: Float, y1: Float, baseFrame: Image, alpha: Float) {
        info.drawImage(x0, y0, x1, y1, baseFrame, alpha, true)
    }

    override fun onMidTeleport() {
        super.onMidTeleport()

        // Apply the reconstructive teleport effect now, since this
        // is both the mid-point of our animation, and we can't take
        // any more damage on the enemy ship.
        if (currentAction != Action.DYING && getAugmentValue(AugmentBlueprint.RECONSTRUCTIVE_TELEPORT) != null) {
            health = maxHealth
        }
    }

    override fun onFinishedDying() {
        super.onFinishedDying()

        val clonebay = ownerShip?.clonebay ?: return
        clonebay.addDeadCrew(this)
    }

    override fun onCloned() {
        super.onCloned()

        // Deduct 20% (of a single level) from all skills. This deduction
        // is then rounded down to an integer number of actions.
        for (skill in Skill.values()) {
            val actionsToDeduct = (skill.actionsPerLevel * 0.20f).toInt()
            val oldLevel = info.skills.getValue(skill)
            val newLevel = (oldLevel - actionsToDeduct * skill.amountPerAction).coerceIn(0f..1f)
            info.skills[skill] = newLevel
        }
    }

    override fun onFinishedRepair(sys: AbstractSystem) {
        super.onFinishedRepair(sys)
        addSkillPoint(Skill.REPAIRS)
    }

    override fun onFinishedExtinguishing() {
        super.onFinishedExtinguishing()

        // Putting out fires doesn't give repair skill. The code for it is
        // there in vanilla, but Repairable::PartialRepair compares the final
        // negative health value to zero to check if it's done, which is
        // thus always false.
    }

    override fun onKilledCrew(enemy: AbstractCrew) {
        super.onKilledCrew(enemy)
        addSkillPoint(Skill.COMBAT)
    }

    override fun onSabotagedSystem(system: AbstractSystem) {
        super.onSabotagedSystem(system)

        // Damaging systems isn't any faster with the combat skill, but
        // it still awards experience.
        addSkillPoint(Skill.COMBAT)
    }

    /**
     * A quick way for systems to credit the crewmember with performing a single action.
     */
    fun addSkillPoint(skill: Skill) {
        val oldProgress = info.skills.getValue(skill)
        val newProgress = (oldProgress + skill.amountPerAction).coerceIn(0f..1f)
        info.skills[skill] = newProgress
    }

    // Helper function
    fun getSkillLevel(skill: Skill): SkillLevel = info.getSkillLevel(skill)

    protected fun getAugmentValue(name: String): Float? {
        return ownerShip?.getAugmentValueOrNull(name)
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        SaveUtil.addAttrRef(elem, "ownerShip", refs, ownerShip)
        info.saveToXML(elem, false)

        savedPosition?.let { SaveUtil.addPoint(elem, "savedPos", it) }
    }

    override fun loadFromXML(elem: Element, refs: RefLoader) {
        super.loadFromXML(elem, refs)

        SaveUtil.getAttrRef(elem, "ownerShip", refs, Ship::class.java) { ownerShip = it }
        info = LivingCrewInfo.loadFromXMLWithRace(elem, blueprint, game)

        if (elem.getChild("savedPos") != null) {
            savedPosition = SaveUtil.getPoint(elem, "savedPos")
        }
    }

    companion object {
        val REPAIR_SKILL_BONUS = listOf(0, 10, 20)
        val COMBAT_SKILL_BONUS = listOf(0, 10, 20)
    }
}

/**
 * Attributes about a crewmember that isn't spawned into the world.
 *
 * This is used for things like events and shops, to show you a player
 * without spawning them.
 */
class LivingCrewInfo(
    private val game: InGameState,

    val race: CrewBlueprint,

    name: String,

    /**
     * The index of this crew's colour selection. This is used to select
     * what colours are applied to the layer filter.
     *
     * This is how different crewmembers can have different colours.
     */
    var colour: Int
) {
    /**
     * The player facing name of this crew, eg 'Slocknog' or some
     * other name that's selected by the player, by an event or
     * from the default list of names.
     */
    var name: String = name
        set(value) {
            if (field == value)
                return

            field = value
            shortName = findShortName()
        }

    var shortName: String = findShortName()
        private set

    /**
     * The crew's skills. 0 means no skill, 1 means fully yellow (max level).
     *
     * 0.5 is where the crew's skill turns from white to green, and starts showing
     * a yellow progress bar.
     */
    val skills: EnumMap<Skill, Float> = EnumMap(Skill.entries.associateWith { 0f })

    /**
     * For humans, this determines whether they're male or female.
     */
    @Suppress("ConvertTwoComparisonsToRangeCheck") // Intention is clearer like this
    val isFemale: Boolean get() = colour >= race.baseNumberOfColours && race.baseNumberOfColours > 0

    private val portraitImage: Image = game.animations["${race.name}_portrait"].spriteAt(game, 0)

    /**
     * Draw this crew's portrait, without having to construct the crewmember instance.
     *
     * Useful for events and shops.
     */
    fun drawPortrait(x: Int, y: Int, scale: Float = 1f) {
        drawImage(
            x.f, y.f,
            x.f + portraitImage.width * scale, y.f + portraitImage.height * scale,
            portraitImage,
            1f,
            false
        )
    }

    // This is moved out of LivingCrew so it can be used in drawPortrait.
    fun drawImage(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        baseFrame: Image,
        alpha: Float,
        skipColours: Boolean
    ) {
        val texX0 = baseFrame.textureOffsetX
        val texY0 = baseFrame.textureOffsetY
        val texX1 = texX0 + baseFrame.width
        val texY1 = texY0 + baseFrame.height

        // Find the sprite sheet for this race. In the case of humans, there's
        // separate male/female sprite sheets, with the unfortunate naming
        // of 'human' for the male version and 'female' for the female version.
        val effectiveColour = if (isFemale) colour - race.baseNumberOfColours else colour
        val baseAnimationName = if (isFemale) "female" else race.name
        val imageNames = if (isFemale) race.femaleLayerImageNames else race.layerImageNames

        val baseImagePath = when {
            isFemale -> "img/people/female_base.png"
            else -> game.animations["${baseAnimationName}_portrait"].sheet.sheetPath
        }
        val fullBaseImage = game.getImg(baseImagePath)

        // Draw the base image, without any tinting
        fullBaseImage.draw(x0, y0, x1, y1, texX0.f, texY0.f, texX1.f, texY1.f, alpha, Colour.white)

        if (skipColours)
            return

        // Draw all the layers
        for ((index, path) in imageNames.withIndex()) {
            val layer = game.getImg(path)

            val colours = race.colourFilters[index]
            val filter = colours[effectiveColour % colours.size]

            layer.draw(x0, y0, x1, y1, texX0.f, texY0.f, texX1.f, texY1.f, alpha, filter)
        }
    }

    fun getSkillLevel(skill: Skill): SkillLevel {
        val progress = skills.getValue(skill)
        return when {
            progress == 1f -> SkillLevel.MAX
            progress >= 0.5f -> SkillLevel.PARTIAL
            else -> SkillLevel.BASE
        }
    }

    fun drawSkillProgressBar(g: Graphics, x: Int, y: Int, width: Int, height: Int, skill: Skill) {
        val level = getSkillLevel(skill)

        val baseColour = when (level) {
            SkillLevel.MAX -> Constants.SYS_ENERGY_REPAIR
            SkillLevel.PARTIAL -> Constants.SYS_ENERGY_ACTIVE
            else -> Colour.transparent
        }
        val barColour = when (level) {
            // Max doesn't draw a bar
            SkillLevel.PARTIAL -> Constants.SYS_ENERGY_REPAIR
            else -> Constants.SYS_ENERGY_ACTIVE
        }

        // Convert the 0-1 progress amount (where the green level is 0.5)
        // to 0-1 over the range of a single colour.
        val rawSkillProgress = skills.getValue(skill)
        val progress = when (level) {
            SkillLevel.MAX -> 0f
            SkillLevel.PARTIAL -> (rawSkillProgress - 0.5f) * 2f
            else -> rawSkillProgress * 2f
        }

        // Draw the outer box around the bar.
        // Note that drawRect draws its lower and right lines outside
        // the specified region, so -1 from width and height.
        g.colour = Colour.white
        g.drawRect(x.f, y.f, width - 1f, height - 1f)

        val innerBarWidth = width - 2
        val progressWidth = (innerBarWidth * progress).roundToInt()

        g.colour = baseColour
        g.fillRect(x + 1f, y + 1f, innerBarWidth.f, height - 2f)

        g.colour = barColour
        g.fillRect(x + 1f, y + 1f, progressWidth.f, height - 2f)

        // Draw the white divider line between yellow and green sections
        if (level == SkillLevel.PARTIAL) {
            g.colour = Colour.white
            g.fillRect(x + 1f + progressWidth, y + 1f, 1f, height - 2f)
        }
    }

    private fun findShortName(): String {
        // Pick a short name.
        // This does a few things:
        // * Use the name as-is if it's short enough
        // * Check if there's a pre-defined short version of the name
        // * Pick the last (space-separated) part of the name
        // * Pick the shortest-by-pixel-length part of the name, and truncate it

        // If we're running in an automated test, we can't use fonts.
        if (game.isRunningAutomatedTest) {
            return name
        }

        val font = game.getFont(PlayerShipUI.CREW_NAME_FONT)

        // Already short enough?
        if (font.getWidth(name) < PlayerShipUI.MAX_NAME_WIDTH) {
            return name
        }

        // Pre-defined short version?
        game.nameManager.findShort(name)?.let { return it }

        // Last name short enough?
        val parts = name.split(" ")
        val lastName = parts.last()
        if (font.getWidth(lastName) < PlayerShipUI.MAX_NAME_WIDTH) {
            return lastName
        }

        // Find the shortest part, and truncate it
        var shortened = parts.minBy { font.getWidth(it) }

        while (font.getWidth("$shortened.") >= PlayerShipUI.MAX_NAME_WIDTH) {
            shortened = shortened.substring(0, shortened.length - 1)
        }

        return "$shortened."
    }

    fun saveToXML(elem: Element, includeRace: Boolean = true) {
        if (includeRace) {
            SaveUtil.addAttr(elem, "race", race.name)
        }
        SaveUtil.addAttr(elem, "selectedName", name)
        SaveUtil.addAttrInt(elem, "colour", colour)

        for ((skill, value) in skills) {
            if (value == 0f)
                continue

            val skillElem = Element("skill")
            SaveUtil.addAttr(skillElem, "id", skill.name)
            SaveUtil.addAttrFloat(skillElem, "value", value)
            elem.addContent(skillElem)
        }
    }

    companion object {
        @JvmStatic
        fun generateRandom(race: CrewBlueprint, game: InGameState): LivingCrewInfo {
            val isMale = when {
                race.name != "human" -> null // Aliens are genderless
                else -> Random.nextBoolean()
            }
            val colour: Int = when {
                race.numberOfColours == 0 -> 0

                // For humans, make sure we set the crew's visual gender to match their name.
                isMale == true -> Random.nextInt(race.baseNumberOfColours)
                isMale == false -> Random.nextInt(race.baseNumberOfColours) + race.baseNumberOfColours

                else -> Random.nextInt(race.numberOfColours) // Aliens
            }

            val name = game.nameManager.getForGender(isMale, Random.Default)
            return LivingCrewInfo(game, race, name, colour)
        }

        fun generateWithName(race: CrewBlueprint, game: InGameState, name: String): LivingCrewInfo {
            val colour: Int = when (val max = race.numberOfColours) {
                0 -> 0
                else -> Random.nextInt(max)
            }
            return LivingCrewInfo(game, race, name, colour)
        }

        fun loadFromXML(elem: Element, game: InGameState): LivingCrewInfo {
            val raceName = SaveUtil.getAttr(elem, "race")
            val race = game.blueprintManager[raceName] as CrewBlueprint
            return loadFromXMLWithRace(elem, race, game)
        }

        fun loadFromXMLWithRace(elem: Element, race: CrewBlueprint, game: InGameState): LivingCrewInfo {
            val name = SaveUtil.getAttr(elem, "selectedName")
            val colour = SaveUtil.getAttrInt(elem, "colour")
            val info = LivingCrewInfo(game, race, name, colour)

            // Skills are omitted if they're set to zero, which is what the default is anyway.
            for (skillElem in elem.getChildren("skill")) {
                val skillName = SaveUtil.getAttr(skillElem, "id")
                val value = SaveUtil.getAttrFloat(skillElem, "value")

                val skill = Skill.valueOf(skillName)
                info.skills[skill] = value
            }

            return info
        }
    }
}

enum class Skill(
    val xmlName: String,

    /**
     * The number of actions a non-human (no 10% boost) has to perform
     * to increase their skill level from 0->50% or 50->100% (a single
     * colour change).
     */
    val actionsPerLevel: Int
) {
    PILOTING("pilot", 15),
    ENGINES("engines", 15),
    SHIELDS("shields", 55),
    WEAPONS("weapons", 65),
    REPAIRS("repair", 18),
    COMBAT("combat", 8),
    ;

    val iconPath: String = "img/people/skill_${xmlName}_white.png"

    // Divide by 2f since the skill level from 0-1 represents the range
    // of no skills to yellow (with green being 0.5), while actionsPerLevel
    // is the number of actions to make one colour change (or 0.5 progress).
    val amountPerAction = 1f / (actionsPerLevel * 2f)

    fun getDescription(game: InGameState, level: SkillLevel): String {
        val levelNum = level.ordinal

        return when (this) {
            PILOTING -> game.translator["pilot_skill"].replaceArg(Piloting.SKILL_BONUSES[levelNum])
            ENGINES -> game.translator["engine_skill"].replaceArg(Engines.SKILL_BONUSES[levelNum])
            SHIELDS -> game.translator["shield_skill"].replaceArg(Shields.SKILL_BONUSES[levelNum])
            WEAPONS -> game.translator["weapon_skill"].replaceArg(Weapons.SKILL_BONUSES[levelNum])

            REPAIRS -> when (level) {
                SkillLevel.BASE -> game.translator["repair_0"]
                else -> game.translator["repair_skilled"].replaceArg(LivingCrew.REPAIR_SKILL_BONUS[levelNum])
            }

            COMBAT -> when (level) {
                SkillLevel.BASE -> game.translator["combat_0"]
                else -> game.translator["combat_skilled"].replaceArg(LivingCrew.COMBAT_SKILL_BONUS[levelNum])
            }
        }
    }
}

enum class SkillLevel {
    BASE, // No advantage
    PARTIAL, // Medium advantage
    MAX; // Skill maxed
}

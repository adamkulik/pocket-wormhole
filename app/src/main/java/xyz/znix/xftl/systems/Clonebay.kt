package xyz.znix.xftl.systems

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.ClonebayHealing
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.RoomPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.min

class Clonebay(blueprint: SystemBlueprint) : MainSystem(blueprint) {
    override val sortingType: SortingType get() = SortingType.CLONEBAY

    val queue = ArrayList<LivingCrew>()

    var cloneProgress: Float = 0f
    var dyingProgress: Float = 0f

    private val cloneDuration: Float
        get() {
            return CLONE_DURATIONS.getOrNull(powerSelected - 1) ?: CLONE_DURATIONS[0]
        }

    private val isEnabled: Boolean get() = powerSelected > 0 && !isHackActive
    private val isDying: Boolean get() = !isEnabled && !hasBackupDNA

    private val hasBackupDNA: Boolean get() = ship.hasAugment(AugmentBlueprint.BACKUP_DNA)

    /**
     * The location of the cloning tub.
     *
     * This can be null on some ships, for example CIRCLE_SCOUT_DLC.
     *
     * In those cases, the cloning tub is hidden.
     */
    private val equipmentLocation: RoomPoint? by lazy {
        val obstruction = room!!.obstructions.firstOrNull() ?: return@lazy null
        RoomPoint(room!!, obstruction)
    }

    private val bottomImage by onInit { it.getImg("img/ship/interior/clone_bottom.png") }
    private val gasImage by onInit { it.getImg("img/ship/interior/clone_gas.png") }
    private val topImage by onInit { it.getImg("img/ship/interior/clone_top.png") }

    private val soloBarImage by onInit { it.getImg("img/systemUI/clonebar_small_solo.png") }
    private val bottomBarImage by onInit { it.getImg("img/systemUI/clonebar_small_bottom.png") }
    private val topBarImage by onInit { it.getImg("img/systemUI/clonebar_small_top.png") }
    private val middleBarImage by onInit { it.getImg("img/systemUI/clonebar_small_middle.png") }
    private val fillImage by onInit { it.getImg("img/systemUI/clonebar_small_fill.png") }

    private val cloneSound by onInit { it.sounds.getSample("cloneArrive") }
    private val offSound by onInit { it.sounds.getLoop("cloneBroken") }

    private val hiddenCrewFont by onInit { it.getFont("JustinFont10") }

    override fun update(dt: Float) {
        super.update(dt)

        if (queue.isEmpty()) {
            cloneProgress = 0f
            dyingProgress = 0f
            return
        }

        if (isEnabled) {
            // Use a 0-1 progress instead of a timer, so changing
            // the power doesn't adjust your progress.
            cloneProgress += dt / cloneDuration

            if (cloneProgress >= 1f) {
                cloneFinished()
            }
        } else {
            cloneProgress = 0f
        }

        if (isDying) {
            dyingProgress += dt / 3f

            if (dyingProgress > 1f) {
                killCrew()
            }

            // Play the suitably nasty crew dying sound
            offSound.continueLoopPlayerOnly(ship)
        } else {
            dyingProgress = 0f
        }
    }

    override fun onJump() {
        super.onJump()

        // Heal our crew each jump.
        if (undamagedEnergy == 0)
            return

        val healing = PASSIVE_HEALING[undamagedEnergy - 1]

        for (crew in ship.crew) {
            // Skip drones
            if (crew !is LivingCrew)
                continue

            // Skip boarders
            if (crew.ownerShip != ship)
                continue

            crew.dealDamage(ClonebayHealing(-healing.f))
        }
    }

    override fun drawRoom(g: Graphics) {
        // Draw the cloning tub behind the system icon.
        if (room!!.playerHasVision) {
            drawEquipment()
        }

        super.drawRoom(g)
    }

    private fun drawEquipment() {
        // Don't draw anything if there's no computer location set in the XML.
        val location = equipmentLocation ?: return

        val x = location.offsetX
        val y = location.offsetY

        bottomImage.draw(x, y)

        val currentCrew = queue.firstOrNull()
        if (currentCrew != null) {
            gasImage.draw(x, y)
        }

        topImage.draw(x, y)
    }

    override fun drawIconAndPower(
        game: InGameState,
        g: Graphics,
        isPlayer: Boolean,
        drawPower: Boolean,
        hoverGlow: Boolean,
        x: Int,
        y: Int
    ) {
        super.drawIconAndPower(game, g, isPlayer, drawPower, hoverGlow, x, y)

        if (!isPlayer)
            return

        val queueX = x - 7 - 6

        // Find the position of the top energy bar, which we put the queue on top of.
        val barY = y - 11 - (energyLevels - 1) * 8

        // This is the bottom of the contents area of the boxes, not
        // including the white outline.
        var queueBottomY = barY - 4 - 3

        // Only show up to three bars with cloning crew in them.
        // If we have four or more crew in the queue, the top box
        // has a '+x' in it to show the number of hidden crew.
        val barCount = min(queue.size, 3)

        // Draw the crew icons/progress bar
        for (index in 0 until barCount) {
            val img = when {
                barCount == 1 -> soloBarImage
                index == 0 -> bottomBarImage
                index == barCount - 1 -> topBarImage
                else -> middleBarImage
            }

            // The Y of the bottom of the contents area, relative to the top of the image.
            val imageBottomOffset = when (index) {
                barCount - 1 -> 29
                else -> 23
            }

            img.draw(queueX, queueBottomY - imageBottomOffset)

            // Find the bounds of the window in which the crewmember is shown.
            // This is used for animating the cloning/dying bar.
            val windowWidth = 34
            val windowHeight = 20
            val windowX = queueX + 9
            val windowY = queueBottomY - windowHeight

            // Draw the progress bar
            if (index == 0 && isEnabled) {
                val fillTopHeight = windowHeight - (cloneProgress * windowHeight).toInt()
                fillImage.draw(
                    windowX.f, windowY + fillTopHeight.f,
                    windowX.f + windowWidth, queueBottomY.f,

                    0f, fillTopHeight.f,
                    windowWidth.f, windowHeight.f,

                    1f, Colour.green
                )
            }

            // Crew are drawn over the progress bar, but under the dying bar.
            if (index == barCount - 1 && queue.size > barCount) {
                // Draw the number of hidden crew
                val shownCrew = barCount - 1 // -1 because this is taking up a slot
                val hiddenCrew = queue.size - shownCrew
                val numStr = "+$hiddenCrew"

                hiddenCrewFont.drawString(windowX + 11f, windowY + 15f, numStr, Colour.white)
            } else {
                // Draw the crewmember portrait
                val crew = queue[index]
                crew.drawPortrait(windowX, windowY - 5, false)
            }

            // Draw the crew dying bar
            if (index == barCount - 1 && isDying) {
                val fillTopHeight = (dyingProgress * windowHeight).toInt()

                // Draw it twice to get the right colours
                for (i in 1..2) {
                    fillImage.draw(
                        windowX.f, windowY.f,
                        windowX.f + windowWidth, windowY.f + fillTopHeight,

                        0f, 0f,
                        windowWidth.f, fillTopHeight.f,

                        1f, Constants.CLONE_DYING_FILTER
                    )
                }
            }

            // This is the height of the blank area, plus the height of the
            // outline bar that sits atop it.
            queueBottomY -= 20 + 3
        }
    }

    fun addDeadCrew(crew: LivingCrew) {
        queue += crew
    }

    private fun cloneFinished() {
        cloneProgress = 0f

        cloneSound.play()

        if (ship.sys.debugFlags.noClone.set)
            return

        val freeSpace = ship.findSpaceForCrew(room!!, AbstractCrew.SlotType.CREW)

        val crew = queue.removeAt(0)
        crew.onCloned()
        ship.crew.add(crew)
        crew.jumpTo(freeSpace)
    }

    private fun killCrew() {
        dyingProgress = 0f

        if (ship.sys.debugFlags.noClone.set)
            return

        // Kill crew in the opposite order they are cloned in.
        queue.removeAt(queue.size - 1)
    }

    override fun saveSystem(elem: Element, refs: ObjectRefs) {
        SaveUtil.addTagFloat(elem, "cloneProgress", cloneProgress, 0f)
        SaveUtil.addTagFloat(elem, "dyingProgress", dyingProgress, 0f)

        val crewListElem = Element("cloningQueue")
        for (crew in queue) {
            refs.register(crew, "cloningCrew")

            val crewElem = Element("crewMember")
            crew.saveToXML(crewElem, refs)
            crewListElem.addContent(crewElem)
        }
        elem.addContent(crewListElem)
    }

    override fun loadSystem(elem: Element, refs: RefLoader) {
        cloneProgress = SaveUtil.getOptionalTagFloat(elem, "cloneProgress") ?: 0f
        dyingProgress = SaveUtil.getOptionalTagFloat(elem, "dyingProgress") ?: 0f

        for (crewElem in elem.getChild("cloningQueue").getChildren("crewMember")) {
            val race = crewElem.getAttributeValue("type")
            val blueprint = ship.sys.blueprintManager.blueprints[race]
                ?: error("Missing crew blueprint for race '$race' (while loading clonebay queue)")
            require(blueprint is CrewBlueprint)

            val crew = blueprint.spawn(room!!, AbstractCrew.SlotType.CREW)
            crew.loadFromXML(crewElem, refs)
            queue.add(crew)
        }
    }

    companion object {
        val INFO: SystemInfo = ClonebayInfo

        val CLONE_DURATIONS = listOf(12f, 9f, 7f, 0.001f)
        val PASSIVE_HEALING = listOf(8, 16, 25, 65536)
    }
}

private object ClonebayInfo : SystemInfo("clonebay") {
    override val canBeManned: Boolean get() = false

    override val isComputerObstruction: Boolean get() = true

    override fun create(blueprint: SystemBlueprint) = Clonebay(blueprint)

    override fun getLevelName(level: Int, translator: Translator): String {
        val time = Clonebay.CLONE_DURATIONS[level].toInt()
        val heal = Clonebay.PASSIVE_HEALING[level]
        return translator["clone_full"].replaceArg(time).replaceArg(heal, 2)
    }
}

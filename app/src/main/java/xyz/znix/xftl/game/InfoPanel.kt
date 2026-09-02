package xyz.znix.xftl.game

import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.crew.SkillLevel
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.*
import kotlin.math.max

// TODO vertically offset all the non-system boxes correctly

class InfoPanel(private val game: InGameState) {
    private val systemLevelFont = game.getFont("JustinFont10")
    private val numberFont = game.getFont("num_font")
    private val titleFont = game.getFont("c&cnew", 2f)
    private val descriptionFont = game.getFont("JustinFont10")
    private val tipFont = game.getFont("JustinFont8")

    var position: IPoint = ConstPoint.ZERO

    fun drawAugment(blueprint: AugmentBlueprint) {
        drawDescriptionBox(blueprint.title, blueprint.desc, null, emptyList(), INFO_HEIGHT_AUGMENT)
    }

    fun drawItem(blueprint: ItemBlueprint) {
        drawDescriptionBox(blueprint.title, blueprint.desc, null, emptyList(), INFO_HEIGHT_ITEM)
    }

    fun drawDrone(blueprint: DroneBlueprint) {
        val lines = ArrayList<String>()
        lines += game.translator["required_power"].replaceArg(blueprint.power)
        lines += game.translator["drone_required"]
        drawDescriptionBox(blueprint.title, blueprint.desc, blueprint.tip, lines, INFO_HEIGHT_DRONE)
    }

    fun drawWeapon(blueprint: AbstractWeaponBlueprint) {
        val lines = ArrayList<String>()

        // Properly calculate the effective system and crew damage.
        val damage = Damage(blueprint)

        // Build all the weapon attributes that appear when hovering over it

        lines += game.translator["required_power"].replaceArg(blueprint.power)
        lines += game.translator["charge_time"].replaceArg(UIUtils.formatFloat(blueprint.chargeTime))
        if (blueprint.boost?.type == AbstractWeaponBlueprint.BoostType.COOLDOWN) {
            val maxBoost = blueprint.boost.maxCount * blueprint.boost.perShot
            lines += game.translator["boost_power_speed"]
            lines += game.translator["speed_cap"].replaceArg(maxBoost)
        }
        if (blueprint.boost?.type == AbstractWeaponBlueprint.BoostType.DAMAGE) {
            // We have to include the initial damage in the max value shown.
            // How does vanilla FTL do this?
            val baseDamage = max(blueprint.damage, blueprint.ionDamage)
            val maxDamage = blueprint.boost.maxCount * blueprint.boost.perShot + baseDamage
            lines += game.translator["boost_power_damage"]
            lines += game.translator["damage_cap"].replaceArg(maxDamage)
        }
        if (blueprint.missilesUsed > 0) {
            lines += game.translator["requires_missiles"]
        }
        if (blueprint is FlakBlueprint) {
            lines += game.translator["shots"].replaceArg(blueprint.projectileCount)
        } else if (blueprint.shots != 1 || blueprint is LaserBlueprint) {
            lines += game.translator["shots"].replaceArg(blueprint.shots)
        }
        if (blueprint.chargeLevels != null) {
            lines += game.translator["charge"].replaceArg(blueprint.chargeLevels)
        }
        if (blueprint is BeamBlueprint) {
            lines += game.translator["damage_room"].replaceArg(blueprint.damage)
        } else {
            lines += game.translator["damage_shot"].replaceArg(blueprint.damage)
        }
        if (blueprint.shieldPiercing != 0) {
            lines += game.translator["shield_piercing"].replaceArg(blueprint.shieldPiercing)
        }
        addChanceString(lines, "fire_chance", blueprint.fireChance)
        addChanceString(lines, "breach_chance", blueprint.breachChance)
        addChanceString(lines, "stun_chance", blueprint.stunChance)
        if (blueprint.ionDamage != 0) {
            lines += game.translator["ion_damage"].replaceArg(blueprint.ionDamage)
        }
        if (damage.effectiveCrewDamage != 0) {
            lines += game.translator["personnel_damage"].replaceArg(damage.effectiveCrewDamage)
        }
        if (damage.effectiveSysDamage != blueprint.damage) {
            lines += game.translator["system_damage"].replaceArg(damage.effectiveSysDamage)
        }
        if (blueprint.hullBust != 0) {
            lines += game.translator["double_damage"]
        }

        drawDescriptionBox(blueprint.title, blueprint.desc, blueprint.tip, lines, INFO_HEIGHT_WEAPON)
    }

    fun drawCrew(g: Graphics, info: LivingCrewInfo) {
        val raceName = game.translator[info.race.title!!]
        val title = GameText.literal("${info.name} ($raceName)")
        val powers = info.race.powerStringIds.map { "-" + game.translator[it] }
        drawDescriptionBox(title, info.race.desc, null, powers, INFO_HEIGHT_CREW)

        // Draw the skills box
        val skillsY = position.y + INFO_HEIGHT_CREW + 24
        game.windowRenderer.render(position.x, skillsY, 323, 242)

        val leftIconX = position.x + 9
        val rightIconX = position.x + 164

        titleFont.drawString(position.x + 28f, skillsY + 32f, game.translator["crew_skills"], Colour.white)

        for ((index, skill) in Skill.values().withIndex()) {
            val row = index / 2
            val iconX = if (index % 2 == 0) leftIconX else rightIconX
            val iconY = skillsY + 46 + row * 66

            val iconColour = when (info.getSkillLevel(skill)) {
                SkillLevel.MAX -> Constants.SYS_ENERGY_REPAIR
                SkillLevel.PARTIAL -> Constants.SYS_ENERGY_ACTIVE
                else -> Constants.UI_BACKGROUND_GLOW_COLOUR
            }
            val icon = game.getImg(skill.iconPath)
            icon.draw(iconX, iconY, iconColour)

            // Draw the skill bar
            info.drawSkillProgressBar(g, iconX + 30, iconY + 8, 110, 9, skill)

            // Draw the description text
            val level = info.getSkillLevel(skill)
            val baseDescription = skill.getDescription(game, level)
            val skillLocName = when (skill) {
                Skill.PILOTING -> "pilot"
                Skill.ENGINES -> "engines"
                Skill.SHIELDS -> "shields"
                Skill.WEAPONS -> "weapons"
                Skill.REPAIRS -> "repair"
                Skill.COMBAT -> "combat"
            }
            val description = game.translator[skillLocName + "_skill_infobox"].replaceArg(baseDescription)
            val lines = tipFont.wrapString(description, 140)
            val textCentreX = iconX + 85

            for ((i, line) in lines.withIndex()) {
                val lineY = iconY + 32 + i * 15
                tipFont.drawStringCentred(textCentreX.f, lineY.f, 0f, line, Colour.white)
            }
        }
    }

    /**
     * Note: this does not render the system power bars!
     */
    fun drawDescriptionBoxSystem(blueprint: SystemBlueprint) {
        drawDescriptionBox(blueprint.title, blueprint.desc, null, emptyList(), INFO_HEIGHT_SYSTEM)
    }

    /**
     * Note: this does not render the system power bars!
     */
    fun drawDescriptionBoxSystem(system: AbstractSystem) {
        // The artillery system uses it's weapon's title/description.
        drawDescriptionBox(system.title, system.description, null, emptyList(), INFO_HEIGHT_SYSTEM)
    }

    fun drawDescriptionBox(
        title: GameText?,
        description: GameText?,
        tip: GameText?,
        extraLines: List<String>,
        height: Int
    ) {
        game.windowRenderer.render(position.x, position.y, 333, height)

        var y = position.y + 30

        val titleStr = title?.get(game.translator)
        if (titleStr != null) {
            val lines = titleFont.wrapString(titleStr, 310)
            for (line in lines) {
                titleFont.drawString(
                    position.x + 11f, y.f,
                    line,
                    Colour.white
                )
                y += 20
            }
        }

        y += 3

        val descriptionStr = description?.get(game.translator)
        if (descriptionStr != null) {
            val lines = descriptionFont.wrapString(descriptionStr, 310)

            for (line in lines) {
                descriptionFont.drawString(position.x + 11f, y.f, line, Colour.white)
                y += 17
            }

            // Draw the extra lines, which are for stuff like weapon attributes.
            y += 17
            for (extra in extraLines) {
                for (line in descriptionFont.wrapString(extra, 310)) {
                    descriptionFont.drawString(position.x + 11f, y.f, line, Colour.white)
                    y += 17
                }
            }
        }

        if (tip != null) {
            val tipStr = game.translator[tip]
            val tipLines = tipFont.wrapString(tipStr, 303)

            val tipY = position.y + height + 17
            val tipHeight = 31 + tipLines.size * 15

            game.windowRenderer.render(position.x, tipY, 333, tipHeight)

            var lineY = tipY + 27
            for (line in tipLines) {
                tipFont.drawString(position.x + 13f, lineY.f, line, Colour.white)
                lineY += 15
            }
        }
    }

    fun drawSystemPowerBox(g: Graphics, system: SystemBlueprint, energyLevels: Int, undoablePower: Int) {
        val x = position.x
        val y = position.y + 133
        val totalWidth = 333 // Maybe this comes from localisation?

        val scrapIcon = game.getImg("img/upgradeUI/details_scrap.png")

        val maxNonUndoable = energyLevels - undoablePower

        // Draw the level text
        for (i in 0 until 8) {
            val canHaveLevel = i < system.maxPower
            val hasLevel = i < energyLevels
            val undoable = hasLevel && i >= maxNonUndoable
            val boxY = y + 192 - i * 26

            // The details images are drawn on top, so we have to paint
            // the background that the text sits on here.
            g.colour = when (canHaveLevel) {
                true -> Constants.UPGRADE_DETAILS_BG_ON
                false -> Constants.UPGRADE_DETAILS_BG_OFF
            }
            g.fillRect(x + 68f, boxY.f, 261f, 24f)

            if (!canHaveLevel) {
                continue
            }

            // Only draw the prices for levels the player can buy or refund.
            if (!hasLevel || undoable) {
                scrapIcon.draw(x + 68, boxY)

                val price = system.upgradeCost.getOrElse(i - 1) { -1 }
                numberFont.drawString(x + 98f, boxY + 19f, price.toString(), Colour.white)
            }

            val description = system.info!!.getLevelName(i, game.translator)
            systemLevelFont.drawString(x + 142f, boxY + 17f, description, Colour.white)
        }

        val offsetY = y + 6 // Due to the ledge at the top of the left side

        // The images are drawn on top of the text
        val leftSideImg = game.getImg("img/upgradeUI/details_base_A.png")
        leftSideImg.draw(x - ShipWindow.GLOW_WIDTH, y - ShipWindow.GLOW_WIDTH)

        val rightSideImg = game.getImg("img/upgradeUI/details_base_C.png")
        val rightX = x + totalWidth + ShipWindow.GLOW_WIDTH - rightSideImg.width
        rightSideImg.draw(rightX, offsetY - ShipWindow.GLOW_WIDTH)

        val middleImg = game.getImg("img/upgradeUI/details_base_B.png")
        val rightSideOfLeftImg = x - ShipWindow.GLOW_WIDTH + leftSideImg.width
        middleImg.draw(
            rightSideOfLeftImg.f, offsetY.f - ShipWindow.GLOW_WIDTH,
            rightX.f, offsetY.f - ShipWindow.GLOW_WIDTH + middleImg.height,
            0f, 0f, middleImg.width.f, middleImg.height.f
        )

        // Finally, draw on the energy bars
        for (i in 0 until system.maxPower) {
            val hasLevel = i < energyLevels
            val undoable = hasLevel && i >= maxNonUndoable

            g.colour = when {
                undoable -> Constants.SYS_ENERGY_PURCHASE_UNDOABLE
                hasLevel -> Constants.SYS_ENERGY_ACTIVE
                else -> Constants.UPGRADE_DETAILS_POWER_OFF
            }
            g.fillRect(x + 20f, y.f + 195 - i * 26, 28f, 18f)
        }
    }

    private fun addChanceString(lines: ArrayList<String>, baseKey: String, chance: Int) {
        // Always look this up to quickly catch invalid keys
        val baseStr = game.translator[baseKey]

        if (chance == 0) {
            return
        }

        val chanceKey = when (chance) {
            in 1..3 -> "chance_low"
            in 4..6 -> "chance_medium"
            else -> "chance_high"
        }

        val chanceStr = game.translator[chanceKey]
        lines += baseStr.replaceArg(chanceStr)
    }

    companion object {
        const val INFO_HEIGHT_SYSTEM = 121
        const val INFO_HEIGHT_AUGMENT = 136
        const val INFO_HEIGHT_CREW = 168
        const val INFO_HEIGHT_ITEM = 121
        const val INFO_HEIGHT_DRONE = 162
        const val INFO_HEIGHT_WEAPON = 252
    }
}

package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Animations
import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.Constants
import xyz.znix.xftl.drones.*
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Image

class DroneBlueprint(xml: Element) : Blueprint(xml) {
    val type: DroneType = DroneType.valueOf(xml.getChildTextTrim("type"))
    override val cost: Int? = xml.getChildTextTrim("cost")?.toInt()
    val power: Int = xml.getChildTextTrim("power").toInt()
    val speed: Int? = xml.getChildTextTrim("speed")?.toInt()
    val droneImage: String? = xml.getChildTextTrim("droneImage")
    val iconImage: String? = xml.getChildTextTrim("iconImage")

    // For defence drones (which includes anti-drones)
    val cooldown: Int? = xml.getChildTextTrim("cooldown")?.toInt() // In milliseconds
    val defenceTarget: String? = xml.getChildTextTrim("target") // Either DRONES or LASERS

    // For external drones, a 0-10 chance that this drone will dodge projectiles that hit it.
    // Nothing in vanilla uses it, but it's easy to implement.
    val dodge: Int? = xml.getChildTextTrim("dodge")?.toInt()

    private val weaponBlueprintName: String? = xml.getChildTextTrim("weaponBlueprint")
    var weaponBlueprint: AbstractWeaponBlueprint? = null
        private set

    // The size of the icon, as it should appear in the UI. This is for roughly
    // positioning them, and obviously different drones are different sizes.
    // These are mostly guessed, I couldn't find anywhere to measure them and
    // disassembling the UI code to look for this seems a bit excessive.
    val iconSize: ConstPoint = when (type) {
        // TODO find some logic behind this - is it the laser arm
        //  in defence drones that make them taller?
        // DEFENSE_1 drones use 46 pixels of Y in the event option UI, while COMBAT_1 uses 38.
        DroneType.DEFENSE -> ConstPoint(29, 37)
        DroneType.COMBAT -> ConstPoint(29, 29)

        // TODO fill in all the drones properly
        else -> ConstPoint(25, 25)
    }

    fun drawIconUI(game: InGameState, pos: IPoint) {
        drawIconUI(game.animations, pos, game::getImg)
    }

    // This version is used by the ship editor, since it doesn't have an InGameState instance.
    fun drawIconUI(animations: Animations, pos: IPoint, getImg: (String) -> Image) {
        var base: Image? = null
        var layer: Image? = null

        // Indoor drones are drawn the same way as vanilla's
        // DroneBlueprint::RenderIcon: the walk_down frame 0 of
        // people/<race>_base.png, overlaid with the matching frame of
        // people/<race>_layer1.png tinted SYS_ENERGY_ACTIVE. The old port
        // used the (different) portrait frame and skipped the layer, so the
        // repair/battle/boarder icons showed the bare red/grey crew art
        // instead of the green drone icon (GitHub issue #174).
        // The ion intruder drone uses the BOARDER_ION race - vanilla
        // special-cases its name here since the blueprint has no iconImage.
        val indoorRace = when (type) {
            DroneType.REPAIR -> "repair"
            DroneType.BATTLE -> "battle"
            DroneType.BOARDER -> if (name == "BOARDER_ION") "boarder_ion" else "battle"
            else -> null
        }
        if (indoorRace != null) {
            val walkAnim = animations.animations["${indoorRace}_walk_down"]
            if (walkAnim != null) {
                val img = getImg(walkAnim.sheet.sheetPath)
                base = walkAnim.sheet.getSprite(img, walkAnim.x, walkAnim.y)

                val layerImg = getImg("img/people/${indoorRace}_layer1.png")
                layer = walkAnim.sheet.getSprite(layerImg, walkAnim.x, walkAnim.y)
            }
        }

        // Special-case the hacking drone, though obviously it's probably
        // not great if the player is seeing this outside a debug menu.
        if (name == "DRONE_HACKING") {
            base = getImg("img/ship/drones/drone_hack_base.png")
        }

        // Otherwise use the outside charged image - this won't exist
        // for indoor drones, so only use it if there isn't a portrait.
        if (base == null && droneImage != null) {
            base = getImg("img/ship/drones/${droneImage}_charged.png")
        }

        if (base == null)
            base = getImg(Constants.MISSING_FILE_PATH)

        // Be sure to pixel-align the image, to make it as sharp as possible.
        val imgX = pos.x - base.width / 2
        var imgY = pos.y - base.height / 2

        if (type == DroneType.DEFENSE) {
            // Shift everything down to account for the gun sticking out.
            imgY += 4
        }

        base.draw(imgX.f, imgY.f)

        // The tinted crew layer that gives indoor drones their green look.
        layer?.draw(imgX.f, imgY.f, Constants.SYS_ENERGY_ACTIVE)

        // Draw the laser for defence drones.
        // Note that while the normal defence drones have a fully-charged
        // gun sprite, the anti-drone doesn't and uses a separate gun and
        // charge glow sprite.
        if (type == DroneType.DEFENSE) {
            val gunBase = getImg("img/ship/drones/${droneImage}_gun_on.png")
            gunBase.draw(imgX.f, imgY.f)

            val gunGlow = getImg("img/ship/drones/${droneImage}_gun_charged.png")
            gunGlow.draw(imgX.f, imgY.f)
        }
    }

    fun makeInstance(): AbstractDrone {
        return when (type) {
            DroneType.REPAIR -> RepairDrone(this)
            DroneType.BOARDER -> BoardingDrone(this)
            DroneType.COMBAT -> CombatDrone(this)
            DroneType.DEFENSE -> DefenceDrone(this)
            DroneType.SHIP_REPAIR -> HullRepairDrone(this)
            DroneType.SHIELD -> ShieldDrone(this)
            else -> DummyDrone(this)
        }
    }

    override fun finishSetup(content: InGameState.GameContent) {
        super.finishSetup(content)

        if (weaponBlueprintName != null) {
            weaponBlueprint = content.blueprintManager[weaponBlueprintName] as AbstractWeaponBlueprint
        }
    }

    enum class DroneType(val needsHostileShip: Boolean) {
        COMBAT(true),
        SHIP_REPAIR(false),
        DEFENSE(false),
        REPAIR(false),
        BATTLE(false),
        BOARDER(true),
        HACKING(true),
        SHIELD(false),
    }

    // A fake drone that does nothing, to avoid the game crashing
    // if an unsupported drone is deployed.
    // (This is more useful with enemy ships, as otherwise
    // unsupported drones being deployed by the enemy ship would trigger
    // a for-the-player unavoidable crash, with no apparent reason).
    private class DummyDrone(type: DroneBlueprint) : AbstractDrone(type) {
        init {
            println("WARNING: Initialising dummy drone ${type.name}, actual drone isn't implemented.")
        }
    }
}

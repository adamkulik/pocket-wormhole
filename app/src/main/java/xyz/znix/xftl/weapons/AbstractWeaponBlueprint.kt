package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.game.FTLSound
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.InGameState.GameContent
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.RefLoader
import java.util.*

abstract class AbstractWeaponBlueprint(xml: Element) : Blueprint(xml) {
    val launcher: String = xml.getChildTextTrim("weaponArt")
    val projectile: String? = xml.getChildTextTrim("image")
    open val explosion: String? = xml.getChildTextTrim("explosion")
    val shots = xml.getChildTextTrim("shots")?.toInt() ?: 1
    val damage = xml.getChildTextTrim("damage")?.toInt() ?: 0
    val sysDamage = xml.getChildTextTrim("sysDamage")?.toInt() ?: 0
    val ionDamage = xml.getChildTextTrim("ion")?.toInt() ?: 0
    val shieldPiercing: Int = xml.getChildTextTrim("sp")?.toInt() ?: 0
    val missilesUsed: Int = xml.getChildTextTrim("missiles")?.toInt() ?: 0
    val speed: Int? = xml.getChildTextTrim("speed")?.toInt()
    val personnelDamage: Int = xml.getChildTextTrim("persDamage")?.toInt() ?: 0
    val chargeLevels: Int? = xml.getChildTextTrim("chargeLevels")?.toInt()
    val hullBust: Int = xml.getChildTextTrim("hullBust")?.toInt() ?: 0

    // Parse these as floats and round back to ints, as vanilla appears to
    // accept but silently truncate them. The Insurrection+ mod has floats
    // for these, and while it's probably a bug, we still have to load it.
    val fireChance: Int = xml.getChildTextTrim("fireChance")?.toFloat()?.toInt() ?: 0
    val breachChance: Int = xml.getChildTextTrim("breachChance")?.toFloat()?.toInt() ?: 0

    // If set, always 3s stun duration
    val stunChance: Int = xml.getChildTextTrim("stunChance")?.toInt() ?: 0

    // This is the duration, if set always 100% chance
    val stun: Int = xml.getChildTextTrim("stun")?.toInt() ?: 0

    // These tags are from Hyperspace, and disable the hull damage being added
    // to personnel and system damage.
    val noPersonnelDamage = xml.getChildTextTrim("noPersDamage")?.toBoolean() ?: false
    val noSysDamage = xml.getChildTextTrim("noSysDamage")?.toBoolean() ?: false

    // Hyperspace tag, a flat reduction to the evasion used in calculations with that gun (can be negative)
    val accuracyModifier: Int = xml.getChildTextTrim("accuracyMod")?.toInt() ?: 0

    // Power, charge time and cost are null for drone blueprints.
    // Use some semi-sane defaults to avoid having to check everywhere.
    val power = xml.getChildTextTrim("power")?.toInt() ?: 1
    val chargeTime: Float = xml.getChildTextTrim("cooldown")?.toFloat() ?: 5f
    override val cost: Int = xml.getChildTextTrim("cost")?.toInt() ?: 0

    val launchSounds = xml.getChild("launchSounds")?.let { SoundList(it) }
    val hitShipSounds = xml.getChild("hitShipSounds")?.let { SoundList(it) }
    val hitShieldSounds = xml.getChild("hitShieldSounds")?.let { SoundList(it) }
    val missSounds = xml.getChild("missSounds")?.let { SoundList(it) }

    val flavourType: GameText? = xml.getGameTextChild("flavorType")

    val boost: ChainBoost?

    init {
        val boostElem = xml.getChild("boost")
        if (boostElem == null) {
            boost = null
        } else {
            val typeName = boostElem.getChildTextTrim("type")
            val type = BoostType.valueOf(typeName.toUpperCase(Locale.UK))
            val amount = boostElem.getChildTextTrim("amount").toFloat()
            val count = boostElem.getChildTextTrim("count").toInt()
            boost = ChainBoost(type, amount, count)
        }
    }

    fun getLauncher(game: InGameState): Animations.WeaponAnimationSpec {
        game.animations.weaponAnimations[launcher]?.let { return it }

        // Missing animation?
        val sheet = Animations.SpriteSheetSpec(Constants.MISSING_FILE_PATH, 20, 20, 20, 20)
        return Animations.WeaponAnimationSpec(
            sheet, 0, 0, 1, 0, 0,
            ConstPoint.ZERO, ConstPoint.ZERO, null, 0, null
        )
    }

    /**
     * Draw the weapon's launcher image as it should be shown in UIs, for example
     * quest rewards or in a store.
     */
    fun drawLauncherUI(game: InGameState, g: Graphics, x: Float, y: Float) {
        val anim = getLauncher(game)
        val spr = anim.getChargedImage(game)
        drawLauncherUI(spr, g, x, y)
    }

    fun drawLauncherUI(chargedImage: Image, g: Graphics, x: Float, y: Float) {
        g.pushTransform()

        // Flip and rotate the sprite appropriately to make it loop like it's mounted above
        // a horizontal surface.

        // Note we have to add the width (height, but we've rotated it 90°) and height
        // to fix the offset caused by the rotation and mirroring.
        g.translate(x + chargedImage.height, y + chargedImage.width)

        g.scale(1f, -1f)
        g.rotate(0f, 0f, 90f)

        chargedImage.draw(0, 0)

        g.popTransform()
    }

    override fun finishSetup(content: GameContent) {
        super.finishSetup(content)

        // Load all the sounds
        launchSounds?.load(content)
        hitShipSounds?.load(content)
        hitShieldSounds?.load(content)
        missSounds?.load(content)
    }

    abstract fun buildInstance(ship: Ship): AbstractWeaponInstance

    open fun loadProjectileFromXML(
        game: InGameState,
        elem: Element, refs: RefLoader,
        callback: ProjectileLoadCallback
    ) {
        throw UnsupportedOperationException("Weapon blueprint '$name' doesn't support weapon projectile deserialisation.")
    }

    class SoundList(elem: Element) {
        private val names: List<String> = elem.getChildren("sound").map { it.textTrim }
        private var soundsInternal: List<FTLSound?>? = null

        /**
         * The sounds in this list.
         *
         * Null entries represent invalid sounds, which don't appear in sounds.xml.
         */
        val sounds: List<FTLSound?> get() = soundsInternal ?: error("SoundList not yet loaded!")

        fun load(content: GameContent) {
            if (soundsInternal != null) {
                error("Cannot re-initialise sound list!")
            }

            soundsInternal = names.map { content.sounds.getSampleOrWarn(it) }
        }

        fun get(): FTLSound? {
            if (sounds.isEmpty())
                return null
            return sounds.random()
        }
    }

    // This is for the charge lasers (including the Vulkan) and the chain ion
    class ChainBoost(val type: BoostType, val perShot: Float, val maxCount: Int)
    enum class BoostType { COOLDOWN, DAMAGE }
}

package xyz.znix.xftl

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Image

class Animations {
    private val sheets: Map<String, SpriteSheetSpec>
    val animations: Map<String, AnimationSpec>
    val weaponAnimations: Map<String, WeaponAnimationSpec>

    constructor(specifications: List<Document>) {
        sheets = HashMap()
        animations = HashMap()
        weaponAnimations = HashMap()

        for (spec in specifications) {
            load(spec)
        }
    }

    constructor(df: Datafile) : this(
        listOf(
            df.parseXML(df["data/animations.xml"]),
            df.parseXML(df["data/dlcAnimations.xml"])
        )
    )

    private fun load(doc: Document) {
        sheets as MutableMap
        animations as MutableMap
        weaponAnimations as MutableMap

        // Parse everything
        // Sadly, duplicate sprite sheets exist in vanilla (eg for the ion bomb,
        // where both the projectile and launcher have the same name). Animations
        // will then use whatever one is used last, which means we have to parse
        // everything in one big loop.
        for (elem in doc.rootElement.children) {
            when (elem.name) {
                "animSheet" -> {
                    val name = elem.getAttributeValue("name")
                    sheets[name] = buildSheet(elem, name) ?: continue
                }

                "anim" -> {
                    val name = elem.getAttributeValue("name")
                    animations[name] = buildAnimation(elem, name) ?: continue
                }

                "weaponAnim" -> {
                    val name = elem.getAttributeValue("name")
                    weaponAnimations[name] = buildWeaponAnimation(elem) ?: continue
                }

                else -> error("Invalid element name '${elem.name}' in animations XML")
            }
        }

        // Late-bind the weapon animations to their boost animations
        for (weapon in weaponAnimations.values) {
            weapon.finishLoading(this)
        }
    }

    private fun buildSheet(elem: Element, name: String): SpriteSheetSpec? {
        // These have image sizes which don't match the XML, skip verification of them for now
        if (TMP_BROKEN_IMAGES.contains(name))
            return null

        // The filename is the text inside the element
        val path = "img/${elem.textTrim}"

        val imgWidth = elem.requireAttributeValueInt("w")
        val imgHeight = elem.requireAttributeValueInt("h")

        val frameWidth = elem.getAttributeValue("fw").toInt()
        val frameHeight = elem.getAttributeValue("fh").toInt()

        return SpriteSheetSpec(path, frameWidth, frameHeight, imgWidth, imgHeight)
    }

    private fun buildAnimation(xml: Element, name: String): AnimationSpec? {
        val sheetName = xml.getChild("sheet").textTrim

        // Skip the broken animation files
        if (TMP_BROKEN_IMAGES.contains(sheetName))
            return null

        val sheet = this.sheets[sheetName]
        if (sheet == null) {
            println("[warn] Unknown animation sheet '$sheetName' for animation '$name'")
            return null
        }

        val desc = xml.getChild("desc")

        val length = desc.getAttributeValue("length").toInt()
        val x = desc.getAttributeValue("x").toInt()
        val y = sheet.verticalCount - desc.getAttributeValue("y").toInt() - 1

        // The chargeion_charge animation claims to be four frames long,
        // but it's really only three frames long if you look at the image,
        // or the width/framewidth values.
        val realLength = length.coerceAtMost(sheet.horizontalCount)

        // Convert from per-cycle to per-frame
        // Special handling for zero-length animations, for the artillery_blank
        // one in the mod 'Alpha_-_The_sM_Polish_Kit_for_1.6'
        val time = when (realLength) {
            0 -> 0.01f // Avoid further divide-by-zero errors
            else -> xml.getChild("time").textTrim.toFloat() / realLength
        }

        return AnimationSpec(sheet, name, x, y, realLength, time)
    }

    private fun buildWeaponAnimation(xml: Element): WeaponAnimationSpec? {
        // Add this here since weapon animations don't have time elements
        // Supply some fixed name that'll be easy to identify if this somehow
        // ends up being used - it shouldn't, we throw it away very soon.
        xml.addContent(Element("time").apply { text = "-1" })
        val anim = buildAnimation(xml, "<unnamed-weapon-anim>") ?: return null

        val chargedFrame = xml.getChild("chargedFrame").textTrim.toInt()
        val fireFrame = xml.getChild("fireFrame").textTrim.toInt()

        val mountPoint = Utils.parsePosElem(xml.getChild("mountPoint"))
        val firePoint = Utils.parsePosElem(xml.getChild("firePoint"))
        val chargeOffset = xml.getChild("firePoint").getAttributeValue("charge")?.toInt() ?: 0

        val chargeImage = xml.getChild("chargeImage")?.textTrim?.let { "img/$it" }

        // The boost animation, unlike the animation sheet, references the
        // last animation that was defined. Thus you can reference the boost
        // animation before it's declared.
        // Thus we have to reference it by name, instead of looking it up,
        // and then resolve it at the end.
        val boostAnim = xml.getChildTextTrim("boost")

        return WeaponAnimationSpec(
            anim.sheet,
            anim.x,
            anim.y,
            anim.length,
            chargedFrame,
            fireFrame,
            mountPoint,
            firePoint,
            chargeImage,
            chargeOffset,
            boostAnim
        )
    }

    fun getOrNull(name: String): AnimationSpec? {
        if (name == "explosion_random") {
            val test = arrayOf("explosion_big2", "explosion_big3", "explosion_big4")
            return this[test.random()]
        }

        return animations[name]
    }

    operator fun get(name: String): AnimationSpec {
        return getOrNull(name) ?: throw IllegalArgumentException("Cannot find animation $name")
    }

    class WeaponAnimationSpec(
        val sheet: SpriteSheetSpec, val x: Int, val y: Int, val length: Int, val chargedFrame: Int,
        val fireFrame: Int, val mountPoint: ConstPoint, val firePoint: ConstPoint,
        val chargeImage: String?,

        /**
         * For charge weapons, this is how far the firing point moves for each projectile.
         */
        val chargeOffset: Int,

        private val boostAnimName: String?
    ) {
        /**
         * For charge weapons and weapons that improve over time, this has the
         * lights that indicate their progress.
         */
        var boostAnim: AnimationSpec? = null
            private set

        fun spriteAt(spriteSheet: Image, i: Int): Image? {
            // Some weapons from Multiverse have invalid charged frame indices,
            // and handling it here makes us more tolerant to invalid indices
            // coming from other places too.
            if (i >= length)
                return null

            return sheet.getSprite(spriteSheet, x + i, y)
        }

        fun spriteAt(game: InGameState, i: Int): Image {
            val img = game.getImg(sheet.sheetPath)
            return spriteAt(img, i) ?: game.missingImage
        }

        fun getChargedImage(game: InGameState): Image {
            return spriteAt(game, chargedFrame)
        }

        /**
         * This finds what frame of the firing animation should be shown at a given
         * [time] after the firing animation started.
         *
         * [duration] is the time the animation is supposed to last, and should
         * be either [PROJECTILE_WEAPON_FIRE_TIME] or [BOMB_FIRE_TIME] to match FTL.
         */
        fun fireIndex(time: Float, duration: Float): Int {
            val progress = time / duration

            val startFrame = chargedFrame
            val fireLength = length - startFrame

            return (progress * fireLength).toInt().coerceIn(0 until fireLength) + startFrame
        }

        fun finishLoading(animations: Animations) {
            if (boostAnimName == null)
                return

            boostAnim = animations.getOrNull(boostAnimName)
            if (boostAnim == null) {
                println("[WARN] Invalid boost animation '$boostAnimName', not found.")
            }
        }
    }

    class SpriteSheetSpec(
        val sheetPath: String,
        val frameWidth: Int, val frameHeight: Int,
        val sheetWidth: Int, val sheetHeight: Int
    ) {
        // Note that mods (tested with 'Alpha_-_The_sM_Polish_Kit_for_1.6') can
        // have blank sprite sheets.
        val verticalCount: Int
            get() = when (frameHeight) {
                0 -> 0
                else -> sheetHeight / frameHeight
            }
        val horizontalCount: Int
            get() = when (frameWidth) {
                0 -> 0
                else -> sheetWidth / frameWidth
            }

        fun getSprite(sheetImage: Image, x: Int, y: Int): Image {
            require(x in 0 until horizontalCount)
            require(y in 0 until verticalCount)

            // Some images, most notably some bombs, have frame heights in the XML set that are greater than
            // the image height. This was causing all sorts of trouble, most notably that bombs were shifted
            // down a bit and the bottom part of their images were cut off.
            val effectiveFrameHeight = frameHeight.coerceAtMost(sheetImage.height)

            return sheetImage.getSubImage(
                x * frameWidth, y * frameHeight,
                frameWidth, effectiveFrameHeight
            )
        }
    }

    companion object {
        val TMP_BROKEN_IMAGES = setOf("artillery_fed", "explosion_big1", "room_touch2x2", "room_touch2x1")

        // How long it takes a weapon animation to play from the charged frame to the end.
        // These were reverse-engineered from vanilla FTL.
        // Note beams have their own unrelated logic.
        // TODO should also be 1sec for charge weapons?
        const val PROJECTILE_WEAPON_FIRE_TIME = 0.25f
        const val BOMB_FIRE_TIME = 1f
    }
}

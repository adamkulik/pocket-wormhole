package xyz.znix.xftl.game

import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.JDOMException
import org.jdom2.input.SAXBuilder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Represents the user's profile: achievements/ships/high scores/settings/etc.
 *
 * Unlike the game state saves, this is supposed to be durable, as it'd be
 * much, much worse to lose it than a savegame.
 */
class SaveProfile private constructor() {
    /**
     * Store the unlocked achievements by ID, rather than using [Achievement]
     * objects, so that profiles don't get broken if you add or remove a mod.
     */
    private val unlockedAchievements = HashMap<String, AchievementUnlockInfo>()

    /**
     * Stores the ships (indexed by their unlockShip id) which the player
     * has completed the unlock quest for.
     *
     * This re-uses the AchievementUnlockInfo class, since we need to track
     * the difficulty the quests were completed on to show in the UI.
     */
    private val unlockedShips = HashMap<String, AchievementUnlockInfo>()

    /**
     * Stores all the user-modified hotkeys. This maps from the hotkey ID
     * to that of the key. It's stored here as a string mapping, as mods
     * will be able to add their own keybinds and we don't want to delete
     * those if the user loads up without said mod.
     *
     * Keys that are mapped to their default values aren't stored here,
     * which means that adding a mod won't immediately fill your profile
     * with all it's keybinds unless you modify them.
     *
     * Mapping to a null string means the key is unbound.
     */
    private val keybinds = HashMap<String, String?>()

    // These must be manually marked as dirty, since they can change quite
    // a lot when the slider is being moved.
    var musicVolume: Float = 1f
    var soundVolume: Float = 1f

    /**
     * Set to true if this profile needs saving.
     */
    var dirty = false
        private set

    fun getAchievement(ach: Achievement): AchievementUnlockInfo? {
        return unlockedAchievements[ach.id]
    }

    /**
     * Remove an achievement, if it's unlocked. This is intended for debugging.
     */
    fun deleteAchievement(ach: Achievement) {
        unlockedAchievements.remove(ach.id)
        dirty = true
    }

    fun unlockAchievement(ach: Achievement, difficulty: Difficulty) {
        val current = unlockedAchievements[ach.id]

        // If we've already unlocked the achievement on a harder difficulty,
        // don't overwrite it with the easier unlock.
        if (current != null && current.difficulty.ordinal <= difficulty.ordinal) {
            return
        }

        unlockedAchievements[ach.id] = AchievementUnlockInfo(difficulty)
        dirty = true
    }

    fun getShipUnlock(shipFamily: ShipFamily): AchievementUnlockInfo? {
        requireNotNull(shipFamily.unlockId)
        return unlockedShips[shipFamily.unlockId]
    }

    fun deleteShipUnlock(shipFamily: ShipFamily) {
        requireNotNull(shipFamily.unlockId)
        unlockedShips.remove(shipFamily.unlockId)
        dirty = true
    }

    fun unlockShip(shipFamily: ShipFamily, difficulty: Difficulty) {
        requireNotNull(shipFamily.unlockId)

        val current = unlockedShips[shipFamily.unlockId]

        // If we've already unlocked the achievement on a harder difficulty,
        // don't overwrite it with the easier unlock.
        if (current != null && current.difficulty.ordinal <= difficulty.ordinal) {
            return
        }

        unlockedShips[shipFamily.unlockId] = AchievementUnlockInfo(difficulty)
        dirty = true
    }

    fun getKeybinds(): Map<String, String?> {
        return keybinds
    }

    fun setKeybind(action: Hotkey, button: HotkeyButton) {
        keybinds[action.id] = button.id
        dirty = true
    }

    fun unbindKey(action: Hotkey) {
        keybinds[action.id] = null
        dirty = true
    }

    fun resetHotkeyToDefault(action: Hotkey) {
        keybinds.remove(action.id)
        dirty = true
    }

    fun save(): Document {
        val doc = Document(Element("xftl-profile"))
        val root = doc.rootElement

        for ((id, info) in unlockedAchievements) {
            val elem = Element("ach")
            root.addContent(elem)

            elem.setAttribute("id", id)
            elem.setAttribute("diff", info.difficulty.name)
        }

        for ((id, info) in unlockedShips) {
            val elem = Element("ship")
            root.addContent(elem)

            elem.setAttribute("unlockId", id)
            elem.setAttribute("diff", info.difficulty.name)
        }

        for ((hotkey, button) in keybinds) {
            val elem = Element("hotkey")
            root.addContent(elem)

            elem.setAttribute("action", hotkey)
            if (button != null) {
                elem.setAttribute("key", button)
            }
        }

        val volumes = Element("soundVolume")
        root.addContent(volumes)
        volumes.setAttribute("sfx", soundVolume.toString())
        volumes.setAttribute("music", musicVolume.toString())

        return doc
    }

    /**
     * Request that the profile should be saved.
     */
    fun markDirty() {
        dirty = true
    }

    fun markSaveComplete() {
        dirty = false
    }

    private fun load(doc: Document) {
        val root = doc.rootElement
        check(root.name == "xftl-profile")

        for (elem in root.getChildren("ach")) {
            val id = elem.getAttributeValue("id")
            val difficultyName = elem.getAttributeValue("diff")

            unlockedAchievements[id] = AchievementUnlockInfo(
                Difficulty.valueOf(difficultyName)
            )
        }

        for (elem in root.getChildren("ship")) {
            val id = elem.getAttributeValue("unlockId")
            val difficultyName = elem.getAttributeValue("diff")

            unlockedShips[id] = AchievementUnlockInfo(
                Difficulty.valueOf(difficultyName)
            )
        }

        for (elem in root.getChildren("hotkey")) {
            val action: String = elem.getAttributeValue("action")
            val key: String? = elem.getAttributeValue("key")
            keybinds[action] = key
        }

        val volumes = root.getChild("soundVolume")
        if (volumes != null) {
            soundVolume = volumes.getAttributeValue("sfx").toFloat()
            musicVolume = volumes.getAttributeValue("music").toFloat()
        }
    }

    class AchievementUnlockInfo(val difficulty: Difficulty)

    companion object {
        @JvmField
        val PROFILE_NAME = "save-profile.xml"

        // Make it a bit more explicit what this is doing
        @JvmStatic
        fun createBlank(): SaveProfile {
            val profile = SaveProfile()

            // Immediately save the empty profile, so there's at least something there.
            profile.dirty = true

            return profile
        }

        @JvmStatic
        fun load(path: Path): SaveProfile? {
            val profile = SaveProfile()

            val doc: Document = try {
                Files.newBufferedReader(path).use { reader ->
                    val builder = com.pocketwormhole.android.SafeXML.builder()
                                        builder.build(reader)
                }
            } catch (ex: IOException) {
                ex.printStackTrace()
                return null
            } catch (ex: JDOMException) {
                ex.printStackTrace()
                return null
            }

            profile.load(doc)
            return profile
        }
    }
}

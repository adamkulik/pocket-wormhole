package xyz.znix.xftl.vanillasave

import xyz.znix.xftl.game.Achievement
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.SaveProfile
import xyz.znix.xftl.game.ShipFamily

/**
 * Moves progress between the engine's profile and vanilla FTL's
 * ae_prof.sav, both ways: achievements and unlocked ships.
 *
 * What the engine doesn't track (ship victories, quest records, the stats
 * screen's scores and records) is kept from the last imported ae_prof.sav,
 * in [SaveProfile.vanillaProfile], and written back on export - so moving a
 * profile to the phone and back doesn't lose any of it.
 */
object VanillaProfileSync {
    class ImportResult(val newAchievements: Int, val newShips: Int)

    /**
     * Merge an ae_prof.sav into [profile]: nothing already unlocked is
     * lost. Throws [VanillaProfile.InvalidProfileException] if it isn't one.
     */
    fun import(
        profile: SaveProfile,
        bytes: ByteArray,
        achievements: Achievement.AchievementTable,
        families: ShipFamily.FamilyTable
    ): ImportResult {
        val imported = VanillaProfile.parse(bytes)

        var newAchievements = 0
        for (record in imported.achievements) {
            val ach = achievements.achievements[record.id] ?: continue
            if (profile.getAchievement(ach) == null)
                newAchievements++
            profile.unlockAchievement(ach, difficulty(record.difficulty))
        }

        var newShips = 0
        for (family in families.families) {
            if (family.unlockId == null)
                continue
            val baseShip = family.ships.firstOrNull() ?: continue
            val slot = VanillaProfile.SHIPS.indexOf(baseShip)
            if (slot == -1)
                continue

            // Unlocked by its quest or otherwise (vanilla also unlocks ships
            // for achievements); layout C counts too, as the hangar here
            // offers every layout of an unlocked ship.
            val quest = imported.achievements.firstOrNull { it.id == "${baseShip}_QUEST" }
            if (imported.unlockedA[slot] || imported.unlockedC[slot] || quest != null) {
                if (!family.isUnlocked(profile))
                    newShips++
                profile.unlockShip(family, difficulty(quest?.difficulty ?: 1))
            }
        }

        val kept = keptProfile(profile)
        profile.vanillaProfile = (if (kept == null) imported else merge(kept, imported)).write()
        return ImportResult(newAchievements, newShips)
    }

    /** An ae_prof.sav with this profile's achievements and ships. */
    fun export(
        profile: SaveProfile,
        achievements: Achievement.AchievementTable,
        families: ShipFamily.FamilyTable
    ): ByteArray {
        val out = keptProfile(profile) ?: VanillaProfile().also {
            // A fresh vanilla profile: the Kestrel is always unlocked.
            it.unlockedA[0] = true
        }

        for (ach in achievements.achievements.values) {
            val info = profile.getAchievement(ach) ?: continue
            addAchievement(out, VanillaProfile.Achievement(ach.id, flag(info.difficulty)))
        }

        // Ships unlocked here by their quest. Families that are always
        // available here (the Engi and Lanius cruisers, unlocked by
        // achievements in vanilla) keep vanilla's own setting.
        for (family in families.families) {
            val slot = VanillaProfile.SHIPS.indexOf(family.ships.firstOrNull())
            if (slot != -1 && family.unlockId != null && family.isUnlocked(profile))
                out.unlockedA[slot] = true
        }

        return out.write()
    }

    private fun keptProfile(profile: SaveProfile): VanillaProfile? {
        val bytes = profile.vanillaProfile ?: return null
        return try {
            VanillaProfile.parse(bytes)
        } catch (e: VanillaProfile.InvalidProfileException) {
            null
        }
    }

    /** [old] plus everything in [new]; the stats come from [new]. */
    private fun merge(old: VanillaProfile, new: VanillaProfile): VanillaProfile {
        val out = VanillaProfile()
        out.newbieLevel = maxOf(old.newbieLevel, new.newbieLevel)
        for (record in old.achievements + new.achievements)
            addAchievement(out, record)
        for (i in VanillaProfile.SHIPS.indices) {
            out.unlockedA[i] = old.unlockedA[i] || new.unlockedA[i]
            out.unlockedC[i] = old.unlockedC[i] || new.unlockedC[i]
        }
        out.stats = new.stats
        return out
    }

    /** Add or update an achievement, keeping the hardest difficulty of each. */
    private fun addAchievement(profile: VanillaProfile, record: VanillaProfile.Achievement) {
        val index = profile.achievements.indexOfFirst { it.id == record.id }
        if (index == -1) {
            profile.achievements += record
            return
        }
        val old = profile.achievements[index]
        val layouts = if (old.layouts == null && record.layouts == null) null else
            IntArray(3) { maxOf(old.layouts?.getOrNull(it) ?: -1, record.layouts?.getOrNull(it) ?: -1) }
        profile.achievements[index] = VanillaProfile.Achievement(record.id, maxOf(old.difficulty, record.difficulty), layouts)
    }

    private fun difficulty(flag: Int): Difficulty = Difficulty.values().getOrElse(flag) { Difficulty.NORMAL }

    private fun flag(difficulty: Difficulty): Int = difficulty.ordinal
}

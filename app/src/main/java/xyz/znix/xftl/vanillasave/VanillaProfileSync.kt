package xyz.znix.xftl.vanillasave

import xyz.znix.xftl.game.Achievement
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.RunRecord
import xyz.znix.xftl.game.SaveProfile
import xyz.znix.xftl.game.ShipFamily

/**
 * Moves progress between the engine's profile and vanilla FTL's
 * ae_prof.sav, both ways: achievements, unlocked ships and stats.
 *
 * What the engine doesn't track (ship victories, quest records) is kept
 * from the last imported ae_prof.sav, in [SaveProfile.vanillaProfile], and
 * written back on export - so moving a profile to the phone and back
 * doesn't lose any of it.
 *
 * Stats: exporting adds the runs played here since the last export
 * ([SaveProfile.runs] from [SaveProfile.runsExported] on) to the stats
 * kept from FTL, so a run is never counted twice; importing takes FTL's
 * stats as they are now (they include whatever was exported to it).
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

    /**
     * An ae_prof.sav with this profile's achievements, ships and runs. The
     * runs count as exported from now on.
     */
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

        addRuns(out.stats, profile.runs.drop(profile.runsExported))

        val bytes = out.write()
        profile.vanillaProfile = bytes
        profile.runsExported = profile.runs.size
        return bytes
    }

    /**
     * The stats as FTL will show them after the next export: the ones
     * kept from FTL plus the runs played here since.
     */
    fun currentStats(profile: SaveProfile): VanillaProfile.Stats {
        val stats = keptProfile(profile)?.stats ?: VanillaProfile.Stats()
        addRuns(stats, profile.runs.drop(profile.runsExported))
        return stats
    }

    /** Count [runs] in vanilla's [stats], like vanilla does at the end of a run. */
    fun addRuns(stats: VanillaProfile.Stats, runs: List<RunRecord>) {
        val r = stats.records
        for (run in runs) {
            r[VanillaProfile.Stats.MOST_SHIPS_DEFEATED] = maxOf(r[VanillaProfile.Stats.MOST_SHIPS_DEFEATED], run.shipsDefeated)
            r[VanillaProfile.Stats.TOTAL_SHIPS_DEFEATED] += run.shipsDefeated
            r[VanillaProfile.Stats.MOST_BEACONS_EXPLORED] = maxOf(r[VanillaProfile.Stats.MOST_BEACONS_EXPLORED], run.beaconsExplored)
            r[VanillaProfile.Stats.TOTAL_BEACONS_EXPLORED] += run.beaconsExplored
            r[VanillaProfile.Stats.MOST_SCRAP_COLLECTED] = maxOf(r[VanillaProfile.Stats.MOST_SCRAP_COLLECTED], run.scrapCollected)
            r[VanillaProfile.Stats.TOTAL_SCRAP_COLLECTED] += run.scrapCollected
            r[VanillaProfile.Stats.MOST_CREW_HIRED] = maxOf(r[VanillaProfile.Stats.MOST_CREW_HIRED], run.crewHired)
            r[VanillaProfile.Stats.TOTAL_CREW_HIRED] += run.crewHired
            r[VanillaProfile.Stats.TOTAL_GAMES_PLAYED]++
            if (run.victory)
                r[VanillaProfile.Stats.TOTAL_VICTORIES]++

            val score = VanillaProfile.Score(
                run.shipName, run.shipId, run.score, run.sector, run.victory, run.difficulty.ordinal, run.advancedEdition
            )
            stats.topScores += score
            stats.topScores.sortByDescending { it.score }
            while (stats.topScores.size > TOP_SCORES)
                stats.topScores.removeAt(stats.topScores.size - 1)

            val best = stats.shipBest.indexOfFirst { it.shipId == run.shipId }
            if (best == -1)
                stats.shipBest += score
            else if (score.score > stats.shipBest[best].score)
                stats.shipBest[best] = score
        }
    }

    /** How many top scores vanilla's stats screen keeps. */
    private const val TOP_SCORES = 5

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

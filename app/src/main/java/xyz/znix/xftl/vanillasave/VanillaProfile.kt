package xyz.znix.xftl.vanillasave

/**
 * Vanilla FTL's profile, ae_prof.sav (file format 9, FTL 1.5.4 and later):
 * achievements, which ships are unlocked, and the stats screen's scores and
 * records. The layout follows FTL Profile Editor's ProfileParser (Vhati).
 *
 * Everything is decoded, and written back exactly as read.
 */
class VanillaProfile {
    /**
     * One achievement record. [difficulty] is vanilla's flag: 0 easy,
     * 1 normal, 2 hard. Ship victory records (`PLAYER_SHIP_*_VICTORY`)
     * also carry the difficulty each layout (A, B, C) won on, -1 for none.
     */
    class Achievement(val id: String, val difficulty: Int, val layouts: IntArray? = null)

    /** 0: show the ship unlock intro, 1: the ship list intro, 2: veteran. */
    var newbieLevel: Int = 2

    val achievements = ArrayList<Achievement>()

    /** Per [SHIPS] slot: layout A unlocked, and layout C (Advanced Edition). */
    val unlockedA = BooleanArray(SHIPS.size)
    val unlockedC = BooleanArray(SHIPS.size)

    /** One run's entry in the top scores or the best-per-ship list. */
    class Score(
        val shipName: String, val shipId: String, val score: Int, val sector: Int,
        val victory: Boolean, val difficulty: Int, val advancedEdition: Boolean
    )

    /** A crew record: the value, and who set it. */
    class CrewRecord(val value: Int, val name: String, val race: String, val male: Boolean)

    /** The stats screen. */
    class Stats {
        val topScores = ArrayList<Score>()
        val shipBest = ArrayList<Score>()

        /**
         * Most and total ships defeated, beacons explored, scrap collected
         * and crew hired (most in one run, then the total), then games
         * played and victories.
         */
        val records = IntArray(10)

        /** Most repairs, combat kills, piloted evasions, jumps survived, skill masteries. */
        val crewRecords = Array(5) { CrewRecord(0, "", "", false) }

        fun write(w: VanillaSaveByteWriter) {
            for (list in listOf(topScores, shipBest)) {
                w.int(list.size)
                for (score in list) {
                    w.string(score.shipName); w.string(score.shipId)
                    w.int(score.score); w.int(score.sector)
                    w.int(if (score.victory) 1 else 0); w.int(score.difficulty)
                    w.bool(score.advancedEdition)
                }
            }
            for (value in records) w.int(value)
            for (rec in crewRecords) {
                w.int(rec.value); w.string(rec.name); w.string(rec.race); w.bool(rec.male)
            }
        }

        companion object {
            const val MOST_SHIPS_DEFEATED = 0
            const val TOTAL_SHIPS_DEFEATED = 1
            const val MOST_BEACONS_EXPLORED = 2
            const val TOTAL_BEACONS_EXPLORED = 3
            const val MOST_SCRAP_COLLECTED = 4
            const val TOTAL_SCRAP_COLLECTED = 5
            const val MOST_CREW_HIRED = 6
            const val TOTAL_CREW_HIRED = 7
            const val TOTAL_GAMES_PLAYED = 8
            const val TOTAL_VICTORIES = 9
        }
    }

    var stats = Stats()

    fun write(): ByteArray {
        val w = VanillaSaveByteWriter()
        w.int(FORMAT)
        w.int(newbieLevel)
        w.int(achievements.size)
        for (ach in achievements) {
            w.string(ach.id)
            w.int(ach.difficulty)
            if (isVictory(ach.id))
                for (i in 0 until 3) w.int(ach.layouts?.getOrNull(i) ?: -1)
        }
        for (i in SHIPS.indices) {
            w.bool(unlockedA[i])
            w.bool(unlockedC[i])
        }
        stats.write(w)
        return w.toByteArray()
    }

    class InvalidProfileException(message: String) : Exception(message)

    companion object {
        const val FORMAT = 9

        /** The ship slots, in the file's order. The last two are unused. */
        val SHIPS: List<String?> = listOf(
            "PLAYER_SHIP_HARD", "PLAYER_SHIP_STEALTH", "PLAYER_SHIP_MANTIS",
            "PLAYER_SHIP_CIRCLE", "PLAYER_SHIP_FED", "PLAYER_SHIP_JELLY",
            "PLAYER_SHIP_ROCK", "PLAYER_SHIP_ENERGY", "PLAYER_SHIP_CRYSTAL",
            "PLAYER_SHIP_ANAEROBIC", null, null
        )

        fun isVictory(id: String) = id.endsWith("_VICTORY")

        fun parse(bytes: ByteArray): VanillaProfile {
            try {
                return parseOrThrow(bytes)
            } catch (e: InvalidProfileException) {
                throw e
            } catch (e: Exception) {
                throw InvalidProfileException("Not an FTL profile (ae_prof.sav): ${e.message}")
            }
        }

        private fun parseOrThrow(bytes: ByteArray): VanillaProfile {
            val r = VanillaSaveReader(bytes)
            val format = r.int()
            if (format == 4)
                throw InvalidProfileException("That's a profile from FTL 1.03 or older (prof.sav) - pick ae_prof.sav")
            if (format != FORMAT)
                throw InvalidProfileException("Not an FTL profile (ae_prof.sav)")

            val p = VanillaProfile()
            p.newbieLevel = r.int()
            require(p.newbieLevel in 0..2) { "bad newbie flag ${p.newbieLevel}" }

            val count = r.int()
            require(count in 0..10000) { "bad achievement count $count" }
            repeat(count) {
                val id = r.string()
                val difficulty = r.int()
                require(difficulty in 0..2) { "bad difficulty $difficulty for $id" }
                val layouts = if (isVictory(id)) IntArray(3) { r.int() } else null
                p.achievements += Achievement(id, difficulty, layouts)
            }

            for (i in SHIPS.indices) {
                p.unlockedA[i] = r.bool()
                p.unlockedC[i] = r.bool()
            }

            for (list in listOf(p.stats.topScores, p.stats.shipBest)) {
                val scores = r.int()
                require(scores in 0..10000) { "bad score count $scores" }
                repeat(scores) {
                    list += Score(
                        shipName = r.string(), shipId = r.string(), score = r.int(), sector = r.int(),
                        victory = r.int() == 1, difficulty = r.int(), advancedEdition = r.bool()
                    )
                }
            }
            for (i in p.stats.records.indices) p.stats.records[i] = r.int()
            for (i in p.stats.crewRecords.indices) p.stats.crewRecords[i] = CrewRecord(r.int(), r.string(), r.string(), r.bool())
            return p
        }
    }
}

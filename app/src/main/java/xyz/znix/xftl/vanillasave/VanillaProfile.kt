package xyz.znix.xftl.vanillasave

/**
 * Vanilla FTL's profile, ae_prof.sav (file format 9, FTL 1.5.4 and later):
 * achievements, which ships are unlocked, and the stats screen's scores and
 * records. The layout follows FTL Profile Editor's ProfileParser (Vhati).
 *
 * Only the parts the engine has an equivalent for are decoded; the stats
 * block (top scores, per-ship bests, records) is kept as raw bytes so that
 * importing a profile and exporting it again doesn't lose anything.
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

    var stats: ByteArray = EMPTY_STATS

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
        w.raw(stats)
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

        /** An empty stats block: no scores, all records zero. */
        val EMPTY_STATS: ByteArray = VanillaSaveByteWriter().run {
            int(0) // top scores
            int(0) // best score per ship
            repeat(10) { int(0) } // ships defeated, beacons, scrap, crew hired, games, victories
            repeat(5) { // crew records: value, name, race, male
                int(0); string(""); string(""); bool(false)
            }
            toByteArray()
        }

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

            // Walk the stats block to check it's all there, then keep it as-is.
            val statsStart = r.pos
            repeat(2) { // top scores, then the best score per ship
                val scores = r.int()
                require(scores in 0..10000) { "bad score count $scores" }
                repeat(scores) {
                    r.string(); r.string() // ship name, ship id
                    repeat(4) { r.int() } // score, sector, victory, difficulty
                    r.bool() // Advanced Edition
                }
            }
            repeat(10) { r.int() }
            repeat(5) { r.int(); r.string(); r.string(); r.bool() }
            p.stats = bytes.copyOfRange(statsStart, r.pos)
            return p
        }
    }
}

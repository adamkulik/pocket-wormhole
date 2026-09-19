package xyz.znix.xftl.vanillasave

import java.io.ByteArrayOutputStream

/**
 * Low-level primitives for vanilla FTL's binary save format (continue.sav,
 * format 11) and the fixed field ordering it uses.
 *
 * All integers are little-endian int32, booleans are stored as int32 0/1, and
 * strings are length-prefixed UTF-8. This layout was verified against nine
 * real saves (see saveformat-ref/model11.py in the project workspace), which
 * is the executable spec this code was written from.
 */
object VanillaSaveFormat {
    const val FILE_FORMAT = 11

    /**
     * Vanilla's fixed system slot ordering, mapped to the xftl system
     * blueprint names ("pilot" and "drones" rather than vanilla's internal
     * slot names "piloting" and "dronecontrol").
     */
    val SYSTEM_SLOTS: List<Pair<String, String>> = listOf(
        "shields" to "shields",
        "engines" to "engines",
        "oxygen" to "oxygen",
        "weapons" to "weapons",
        "dronecontrol" to "drones",
        "medbay" to "medbay",
        "piloting" to "pilot",
        "sensors" to "sensors",
        "doors" to "doors",
        "teleporter" to "teleporter",
        "cloaking" to "cloaking",
        "artillery" to "artillery",
        "battery" to "battery",
        "clonebay" to "clonebay",
        "mind" to "mind",
        "hacking" to "hacking",
    )

    /** The same mapping as [SYSTEM_SLOTS], in a form usable from Java. */
    fun systemSlotEntries(): List<Map.Entry<String, String>> =
        SYSTEM_SLOTS.map { java.util.AbstractMap.SimpleEntry(it.first, it.second) }

    /** A seeded [kotlin.random.Random], callable from Java. */
    @JvmStatic
    fun seededRandom(seed: Int): kotlin.random.Random = kotlin.random.Random(seed)

    /** Vanilla store shelf item-type codes. */
    const val SHELF_WEAPON = 0
    const val SHELF_DRONE = 1
    const val SHELF_AUGMENT = 2
    const val SHELF_CREW = 3
    const val SHELF_SYSTEM = 4

    /**
     * Vanilla ticks run at 1000 per second (a full FTL charge is 85000 ticks
     * = 85 seconds). xftl tracks the same quantities as floats in seconds.
     */
    const val TICKS_PER_SECOND = 1000f

    /** Outcome event names vanilla writes into the encounter tail. */
    const val DEFAULT_SURRENDER_EVENT = "PIRATE_SURRENDER"
    const val DEFAULT_ESCAPE_EVENT = "PIRATE_ESCAPE"
    const val DEFAULT_DESTROYED_EVENT = "DESTROYED_DEFAULT"
    const val DEFAULT_DEAD_CREW_EVENT = "DEAD_CREW_DEFAULT"

    /**
     * A byte-exact encounter tail (everything from unknown_mu onward,
     * including the mu int) taken from a REAL vanilla 1.6.14 save.
     * Proven to load under our header by the Q_H bisect test.
     */
    val TEMPLATE_TAIL: ByteArray = java.util.Base64.getDecoder().decode(
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIwAAAGV2ZW50X05FQlVMQV9QSVJBVEVfU01VR0dMRV9jMl90ZXh0/////wIAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAPgqAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP////8BAAAAAAAAAAAAAAAAAAAA6AMAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/////wAAAAAAAAAAAAAAAAAAAADoAwAAGPz//xj8////////AAAAAAAAAADgLgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/////AQAAAAAAAAAAAAAAAAAAAOgDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP////8AAAAAAAAAAAAAAAAAAAAA6AMAABj8//8Y/P///////wAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAADB1AAAAAAAAAAAAAA=="
    )
}

class VanillaSaveByteWriter {
    private val buf = ByteArrayOutputStream()

    fun int(v: Int) {
        buf.write(v)
        buf.write(v ushr 8)
        buf.write(v ushr 16)
        buf.write(v ushr 24)
    }

    fun bool(v: Boolean) = int(if (v) 1 else 0)

    fun string(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        int(bytes.size)
        buf.write(bytes)
    }

    fun raw(bytes: ByteArray) {
        buf.write(bytes)
    }

    fun size(): Int = buf.size()

    fun toByteArray(): ByteArray = buf.toByteArray()
}

class VanillaSaveReader(private val data: ByteArray) {
    var pos = 0
        private set

    fun int(): Int {
        val v = (data[pos].toInt() and 0xFF) or
                ((data[pos + 1].toInt() and 0xFF) shl 8) or
                ((data[pos + 2].toInt() and 0xFF) shl 16) or
                (data[pos + 3].toInt() shl 24)
        pos += 4
        return v
    }

    fun bool(): Boolean = int() != 0

    fun string(): String {
        val len = int()
        require(len in 0..(data.size - pos)) { "Bad string length $len at $pos" }
        val s = String(data, pos, len, Charsets.UTF_8)
        pos += len
        return s
    }

    fun ticksToSeconds(ticks: Int): Float = ticks / VanillaSaveFormat.TICKS_PER_SECOND

    val remaining: Int get() = data.size - pos
}

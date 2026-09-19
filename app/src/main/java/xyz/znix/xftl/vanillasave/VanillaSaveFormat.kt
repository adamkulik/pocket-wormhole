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
     * including the mu int) taken from a REAL vanilla 1.6.14 save: a fresh
     * Kestrel, parked at the start beacon.
     *
     * Vanilla's own tail encodes the open event dialogue, tutorial state and
     * encounter bookkeeping, which we don't model - and our earlier invented
     * minimal tail hung vanilla's loader. This template is proven to load
     * under one of our headers (the expH bisect test).
     *
     * Semantically it's the fresh-start state, so an import may re-show the
     * tutorial text; the beacon's actual event lives in the beacon section,
     * which we write properly. When importing a vanilla save, the donor's
     * own tail is preserved instead (see InGameState.vanillaTail).
     */
    val TEMPLATE_TAIL: ByteArray = hexToBytes(
        "00000000ffc9e933100000005049524154455f53555252454e4445520d0000005049524154455f4553434150451100000044" +
        "455354524f5945445f44454641554c5411000000444541445f435245575f44454641554c541500000050495241544520676f" +
        "742061776179202031323134000000000100000041010000546865206461746120796f752063617272792069732076697461" +
        "6c20746f207468652072656d61696e696e672046656465726174696f6e20666c6565742e20596f75276c6c206e6565642073" +
        "7570706c69657320666f7220746865206a6f75726e65792c20736f206d616b65207375726520746f206578706c6f72652065" +
        "61636820736563746f72206265666f7265206d6f76696e67206f6e20746f20746865206e6578742e20427574206765742074" +
        "6f207468652065786974206265666f726520746865207075727375696e6720526562656c20666c6565742063616e20636174" +
        "636820757021200a200a5449503a20536f756e64202d2020476f20696e746f20746865206f7074696f6e73206d656e752074" +
        "6f207475726e206f6e206f72206f66662074686520736f756e64206566666563747320616e64206d757369632effffffff01" +
        "0000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000020000" +
        "0000000000f82a00000000000000000000000000000000000000000000000000000000000000000000ffffffff0100000000" +
        "0000000000000000000000e80300000000000000000000000000000000000000000000ffffffff0000000000000000000000" +
        "0000000000e803000018fcffff18fcffffffffffff0000000000000000e02e00000000000000000000000000000000000000" +
        "000000000000000000000000000000ffffffff01000000000000000000000000000000e80300000000000000000000000000" +
        "000000000000000000ffffffff00000000000000000000000000000000e803000018fcffff18fcffffffffffff0000000000" +
        "00000000000000000000000000000001000000307500000000000000000000"
    )

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
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

    fun floatSeconds(seconds: Float): Int = (seconds * VanillaSaveFormat.TICKS_PER_SECOND).toInt()

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

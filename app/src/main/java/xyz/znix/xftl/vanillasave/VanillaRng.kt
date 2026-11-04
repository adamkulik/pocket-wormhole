package xyz.znix.xftl.vanillasave

/**
 * Vanilla FTL's random number generator (1.6.14), for generating things the
 * same way vanilla does from the seeds its saves store (eg the sector tree).
 *
 * It's a 64-bit linear congruential generator, the same one musl's rand()
 * uses: seeding sets the state to the seed, each draw multiplies the state
 * by 0x5851F42D4C957F2D, adds one and returns its top 31 bits. Vanilla uses
 * it unless the save's random_native flag asks for the C runtime's rand().
 */
class VanillaRng(seed: Int) {
    private var state: Long = seed.toLong() and 0xffffffffL

    /** The next value, 0 to 2^31 - 1, like vanilla's rand(). */
    fun next(): Int {
        state = state * 0x5851F42D4C957F2DL + 1
        return (state ushr 33).toInt()
    }

    /** next() % [n], the way vanilla picks from n options. */
    fun nextInt(n: Int): Int = next() % n
}

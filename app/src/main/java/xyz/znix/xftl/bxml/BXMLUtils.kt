/**
 * Utility functions for implementing BXML.
 */
package xyz.znix.xftl.bxml

import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.math.min

object BXMLUtils {
    const val ID_TEXT = 0
    const val ID_ELEM = 1
    const val ID_EOF = 2
}

fun DataOutputStream.writeVarInt(value: Int) {
    require(value >= 0)

    var remaining = value
    do {
        val segment = remaining and 0x7f
        remaining = remaining ushr 7
        val hasMore = remaining != 0

        val byte = segment or (if (hasMore) 0x80 else 0)
        write(byte)
    } while (hasMore)
}

/**
 * A slightly faster version of Java's [java.io.BufferedInputStream]. Since
 * we read lots and LOTS of single bytes, the overhead for BufferedInputStream's
 * synchronised read method is otherwise quite significant.
 */
class BXMLBufferedInputStream(val base: InputStream) {
    private var bufOffset = 0
    private var bufAmount = 0
    private val buffer = ByteArray(1024 * 8)

    fun readVarInt(): Int {
        val initial = readByte()
        if (initial < 128) {
            return initial
        }

        var value = initial and 0x7f
        var shift = 7
        do {
            val byte = readByte()

            val segment = byte and 0x7f
            val moreMarker = byte and 0x80

            value += segment shl shift
            shift += 7
        } while (moreMarker != 0)

        return value
    }

    /**
     * Read a byte, or throw [EOFException] if the EOF is reached.
     */
    fun readByte(): Int {
        if (bufOffset < bufAmount) {
            return buffer[bufOffset++].toInt() and 0xff
        }

        refill()
        return readByte()
    }

    fun readNBytes(length: Int): ByteArray {
        val result = ByteArray(length)
        var outPosition = 0

        while (true) {
            val bufRemaining = bufAmount - bufOffset
            val outRemaining = length - outPosition
            val toCopy = min(outRemaining, bufRemaining)

            System.arraycopy(buffer, bufOffset, result, outPosition, toCopy)
            outPosition += toCopy
            bufOffset += toCopy

            if (toCopy == outRemaining) {
                break
            }

            refill()
        }

        return result
    }

    /**
     * Read more data into our buffer, or throw [EOFException] if none more is left.
     */
    private fun refill() {
        bufOffset = 0
        bufAmount = base.read(buffer)

        if (bufAmount <= 0)
            throw EOFException()
    }
}

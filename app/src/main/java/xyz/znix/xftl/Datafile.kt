package xyz.znix.xftl

import org.jdom2.Document
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.modding.*
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.rendering.TextureLoader
import xyz.znix.xftl.sys.PlatformSpecific
import xyz.znix.xftl.sys.ResourceContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.*

const val HEADER_SIZE = 16
const val ENTRY_SIZE = 20

/**
 * Represents a vanilla FTL .dat file, without any mods applied.
 *
 * This is an interface, so tests can run without the vanilla assets.
 *
 * [Datafile] acts as a layer over the top of this, applying changes from
 * [SlipstreamPatcher] and any other mod sources we might later have.
 */
interface IVanillaDatafile {
    fun getAllFiles(): List<VanillaDatafile.Entry>
    fun containsFile(name: String): Boolean
    fun read(file: VanillaDatafile.Entry): ByteArray

    /**
     * True if patched files can be stored in the XML cache.
     *
     * This is notably false for automated tests.
     */
    val xmlCacheSupported: Boolean
}

class VanillaDatafile(val underlyingFile: File) : IVanillaDatafile {
    private val files: MutableMap<String, Entry> = HashMap()
    private val fi: RandomAccessFile = RandomAccessFile(underlyingFile, "r")

    override val xmlCacheSupported: Boolean get() = true

    init {
        // Skip the 'PKG\n' header
        fi.seek(4)

        // Header size
        check(fi.readUnsignedShort() == HEADER_SIZE)

        // Entry size
        check(fi.readUnsignedShort() == ENTRY_SIZE)

        val fileCount = fi.readInt()
        val nameSize = fi.readInt()

        val entriesStart = fi.filePointer
        check(entriesStart == HEADER_SIZE.toLong())

        // We used to read the data directly out of the stream, but this caused
        // lots and lots of syscalls, which wasn't great from a performance
        // standpoint.
        val entryData = ByteBuffer.allocate(fileCount * ENTRY_SIZE)
        fi.readFully(entryData.array())

        val namesStart = entriesStart + fileCount * ENTRY_SIZE
        check(namesStart == fi.filePointer)

        val nameData = ByteBuffer.allocate(nameSize)
        fi.readFully(nameData.array())

        for (i in 1..fileCount) {
            entryData.getInt() // The file name hash

            val flags = entryData.get().toInt()
            check(flags == 0) { "Datafile does not yet support compression" }

            val nameOffset = read3byte(entryData)

            val name = readStringNulTerm(nameData, nameOffset)

            val offset = entryData.getInt()
            val compressedSize = entryData.getInt()
            val decompressedSize = entryData.getInt()

            check(compressedSize == decompressedSize) { "Compressed and uncompressed file sizes do not match" }

            files[name] = Entry(name, offset, decompressedSize)

            check(entryData.position() == i * ENTRY_SIZE)
        }
    }

    private fun readStringNulTerm(buf: ByteBuffer, position: Int): String {
        var count = 0

        while (true) {
            val ch = buf.get(position + count)
            if (ch == 0.toByte())
                break

            count++
        }

        return String(buf.array(), position, count, Charsets.UTF_8)
    }

    private fun read3byte(buf: ByteBuffer): Int {
        val ch1 = buf.get().toInt() and 0xff
        val ch2 = buf.get().toInt() and 0xff
        val ch3 = buf.get().toInt() and 0xff
        return (ch1 shl 16) + (ch2 shl 8) + ch3
    }

    operator fun get(name: String): Entry =
        files[name] ?: throw IllegalArgumentException("No such file '$name'")

    override fun containsFile(name: String): Boolean = files.containsKey(name)

    override fun read(file: Entry): ByteArray {
        fi.seek(file.offset.toLong())
        val bytes = ByteArray(file.length)
        fi.read(bytes)
        return bytes
    }

    override fun getAllFiles(): List<Entry> {
        return files.values.toList()
    }

    class Entry(val name: String, val offset: Int, val length: Int)

    companion object {
        @JvmStatic
        fun createWithDefaultPath(): VanillaDatafile {
            val path = MainGame.findFtlDat() ?: error("Datafile path not set!")
            return VanillaDatafile(path.toFile())
        }
    }
}

open class Datafile(val vanilla: IVanillaDatafile, slipstreamMods: List<SlipstreamMod>) {
    // TODO handle closing this, to close the zip files
    private val patcher = SlipstreamPatcher(vanilla)
    private val xmlCache = PatchedXMLCache(PlatformSpecific.INSTANCE.xmlCacheDirectory)
    private val files: Map<String, FTLFile>

    init {
        patcher.patch(slipstreamMods)
        files = patcher.files.keys.map { FTLFile(it) }.associateBy { it.name }
    }

    operator fun get(name: String): FTLFile =
        files[name] ?: throw IllegalArgumentException("No such file '$name'")

    fun getOrNull(name: String): FTLFile? = files[name]

    fun read(file: FTLFile): ByteArray {
        return patcher.files[file.name]!!.open().use { it.readAllBytes() }
    }

    fun readString(file: FTLFile): String {
        // The vanilla files use LF endings, but mods might introduce CRLF endings too.
        return String(read(file), Charsets.UTF_8).replace("\r\n", "\n")
    }

    fun parseXML(file: FTLFile): Document {
        val source = patcher.files[file.name]!!

        // Don't cache vanilla XML files, they're quick to parse.
        // Also don't cache files added by mods, both because otherwise the cache
        // files could build up over time from old mods, and because they're not
        // usually patched by other mods and are thus relatively quick to parse.
        val canCache = source !is VanillaFileSource && vanilla.containsFile(file.name)
                && vanilla.xmlCacheSupported

        if (canCache) {
            // Try to re-use a previous copy of this file, if possible.
            // Applying patches doesn't take that long, but it's not trivial either.
            xmlCache.lookup(file, source)?.let { return it }
        }

        val document = source.openXML()

        if (canCache) {
            println("Caching patched XML file '${file.name}'")
            xmlCache.save(file, source, document)
        }

        return document
    }

    fun open(file: FTLFile): InputStream {
        return ByteArrayInputStream(read(file))
    }

    open fun readImage(context: ResourceContext, file: FTLFile): Image {
        return TextureLoader.loadImage(context, open(file), file.name)
    }

    fun getAllFiles(): List<FTLFile> {
        return files.values.toList()
    }

    companion object {
        fun loadWithMods(vanilla: VanillaDatafile): Datafile {
            // Load slipstream mods from a 'mods' folder. The mod order is specified
            // with the order.txt file, which has the names of each of the mods
            // on separate lines.
            // TODO move into a GUI.
            val mods: List<SlipstreamMod>
            val modDir = PlatformSpecific.INSTANCE.modsDirectory
            val modOrderFile = modDir.resolve("order.txt")
            if (Files.isRegularFile(modOrderFile)) {
                println("Using mod order file: '$modOrderFile'")
                mods = Files.readAllLines(modOrderFile)
                    .filter { it.isNotBlank() }
                    .filter { !it.startsWith("//") } // Comments
                    .map { modDir.resolve(it) }
                    .map {
                        if (Files.isDirectory(it)) {
                            SlipstreamDirectoryMod(it)
                        } else {
                            SlipstreamZipMod(it.toFile())
                        }
                    }
            } else {
                println("Missing mod order file, mods disabled: '$modOrderFile'")
                mods = Collections.emptyList()
            }

            return Datafile(vanilla, mods)
        }
    }
}

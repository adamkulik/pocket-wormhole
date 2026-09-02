package xyz.znix.xftl.modding

import org.jdom2.Document
import xyz.znix.xftl.FTLFile
import xyz.znix.xftl.bxml.BXMLReader
import xyz.znix.xftl.bxml.BXMLWriter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * A cache to speed up loading, by reusing previously-parsed XML.
 *
 * A very common case is for a player to use the same mods over and over,
 * and this avoids having to re-parse files in that case.
 */
class PatchedXMLCache(val dir: Path) {
    fun lookup(file: FTLFile, source: FileSource): Document? {
        val path = getCacheFile(file)

        if (!Files.isRegularFile(path)) {
            return null
        }

        // Get a fingerprint of the current source, so we know if
        // it's been changed and the cache thus invalidated.
        val digest = createDigest()
        source.addCacheContribution(digest)
        val currentHash = digest.digest()

        val document = try {
            loadCached(path, currentHash)
        } catch (ex: IOException) {
            System.err.println("Failed to read cached XML file '$path', re-building.")
            ex.printStackTrace()
            null
        }

        // If the hash is wrong or there was an exception, delete it so
        // we don't try and load it again until we next update it.
        if (document == null) {
            Files.delete(path)
        }

        return document
    }

    private fun loadCached(path: Path, expectedHash: ByteArray): Document? {
        BufferedInputStream(Files.newInputStream(path)).use {
            val oldHash = it.readNBytes(DIGEST_LENGTH)
            if (!oldHash.contentEquals(expectedHash))
                return null

            return BXMLReader.read(it)
        }
    }

    fun save(file: FTLFile, source: FileSource, document: Document) {
        // Create the cache directory, if it doesn't already exist.
        if (!Files.isDirectory(dir)) {
            Files.createDirectory(dir)
        }

        val path = getCacheFile(file)

        // Get a fingerprint of the current source, so we know if
        // it's been changed and the cache thus invalidated.
        val digest = createDigest()
        source.addCacheContribution(digest)
        val currentHash = digest.digest()

        // If there's an exception while writing, we'll leave around a broken
        // file. That's fine, since it'll be truncated and thus loading it
        // will fail, and just run the patcher again.
        BufferedOutputStream(Files.newOutputStream(path)).use {
            it.write(currentHash)
            BXMLWriter.write(document, it)
        }
    }

    private fun getCacheFile(file: FTLFile): Path {
        val name = file.name
            .replace('/', '-')
            .removeSuffix(".xml") + ".bxml"
        return dir.resolve(name)
    }


    companion object {
        private val DIGEST_LENGTH: Int = createDigest().digestLength

        private fun createDigest(): MessageDigest {
            return MessageDigest.getInstance("MD5")
        }
    }
}

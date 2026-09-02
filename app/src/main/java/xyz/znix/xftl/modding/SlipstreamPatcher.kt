// Significant portions of this file (mainly that relating to XML handling)
// are derived from Slipstream.

package xyz.znix.xftl.modding

import net.vhati.modmanager.core.ModUtilities
import net.vhati.modmanager.core.XMLPatcher
import org.jdom2.Content
import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.input.SAXBuilder
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import xyz.znix.xftl.IVanillaDatafile
import xyz.znix.xftl.VanillaDatafile
import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.*
import java.util.logging.Logger
import java.util.regex.Pattern
import java.util.zip.ZipFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import java.util.stream.Collectors


/**
 * Dynamically applies patches from Slipstream mods to files, as they're loaded.
 *
 * A lot of this is copied from net.vhati.modmanager.core.ModPatchThread, which
 * isn't included in xftl as rewriting the stuff we need here is easier.
 */
class SlipstreamPatcher(vanilla: IVanillaDatafile) {
    private val log: Logger = Logger.getLogger(javaClass.name)

    val files = HashMap<String, FileSource>()

    /**
     * Files should be case-insensitive (using the English locale, since that's
     * what almost all mods will be tested against), this maps the lower-case
     * name of a file to the one that's first been seen.
     */
    private val toOriginalCase = HashMap<String, String>()

    init {
        // Register all the files from vanilla
        for (file in vanilla.getAllFiles()) {
            // Use checkCase to populate the toOriginalCase map
            files[checkCase(file.name)] = VanillaFileSource(vanilla, file)
        }
    }

    fun patch(mods: List<SlipstreamMod>) {
        if (mods.isEmpty()) {
            log.info("No mods installed.")
            return
        }

        log.info("Loading mods: ${mods.joinToString(", ") { it.name }}")

        // Track modified innerPaths in case they're clobbered.
        val moddedItems = HashSet<String>()

        for (mod in mods) {
            log.info("Installing mod: ${mod.name}")

            for (filePath in mod.entries) {
                if (isJunkFile(filePath))
                    continue
                processRegularEntry(mod, filePath, moddedItems)
            }
        }

        //log.info( "Adding XSL Transforms to temporary database..." );
        val stylesheets = HashMap<String, ByteArray>()
        for (mod in mods) {
            log.info("Applying XSLT transforms of mod: ${mod.name}")

            // First, read in all the stylesheets, as they can be used
            // as libraries by each other.
            // It's not ideal to read stuff in now - it's better to load
            // it on-demand - but the XSLT files shouldn't be very big, and
            // it'd be a pretty invasive change to load the libraries on-demand.
            for (filePath in mod.entries) {
                if (isJunkFile(filePath))
                    continue

                if (!filePath.endsWith(".xsl")) {
                    continue
                }

                val innerPath = checkCase(filePath)
                if (stylesheets.containsKey(filePath)) {
                    log.warning(String.format("Clobbering earlier stylesheet: %s", innerPath))
                }
                stylesheets[filePath] = mod.openFile(filePath).use { it.readBytes() }
                log.info("Added stylesheet to temporary database: $innerPath")
                if (!moddedItems.contains(innerPath)) {
                    moddedItems.add(innerPath)
                }
                // copied from below
            }

            // same basic form as above
            var noTransformsYet = true
            for (filePath in mod.entries) {
                if (isJunkFile(filePath))
                    continue

                if (!filePath.endsWith(".xsl")) {
                    continue
                }

                if (noTransformsYet) {
                    noTransformsYet = false
                    log.info("Applying XSL Transforms...")
                }

                var innerPath = filePath.removeSuffix(".xsl") + ".xml"
                innerPath = checkCase(innerPath)
                val oldFileSource = files[innerPath]
                if (oldFileSource == null) {
                    val padding = String(CharArray(23)).replace("\u0000", " ")
                    log.warning(
                        String.format(
                            "Could not find base file: %s\n%sAssuming %s is an XSL library",
                            innerPath,
                            padding,
                            filePath
                        )
                    )
                    continue
                }

                log.info(String.format("Creating transform mapping for file: %s", innerPath))
                files[innerPath] = XSLTransformFileSource(oldFileSource, mod, filePath, stylesheets)
                if (!moddedItems.contains(innerPath)) {
                    moddedItems.add(innerPath)
                }
            }

            if (noTransformsYet) {
                log.info("Mod had no transforms, nothing applied")
            }
        }
    }

    /**
     * Process a non-XSLT entry.
     */
    private fun processRegularEntry(
        mod: SlipstreamMod,
        filePath: String,
        moddedItems: HashSet<String>
    ) {
        var innerPath = filePath

        if (filePath.endsWith(".xml.append") || filePath.endsWith(".append.xml")) {
            innerPath = filePath.replace("[.](?:xml[.]append|append[.]xml)$".toRegex(), ".xml")
            innerPath = checkCase(innerPath)

            val previous = files[innerPath]

            if (previous == null) {
                log.warning(String.format("Non-existent innerPath wasn't appended: %s", innerPath))
                return
            }

            files[innerPath] = XMLPatchFileSource(previous, mod, filePath)

            if (!moddedItems.contains(innerPath)) {
                moddedItems.add(innerPath)
            }
            return
        }
        if (filePath.endsWith(".xml.rawappend") || filePath.endsWith(".rawappend.xml")) {
            innerPath = filePath.replace(
                "[.](?:xml[.]rawappend|rawappend[.]xml)$".toRegex(),
                ".xml"
            )
            innerPath = checkCase(innerPath)
            val original = files[innerPath]
            if (original == null) {
                log.warning(String.format("Non-existent innerPath wasn't raw appended: %s", innerPath))
                return
            }

            log.warning(String.format("Appending xml as raw text: %s", innerPath))
            files[innerPath] = XMLRawAppendFileSource(original, mod, filePath)

            if (!moddedItems.contains(innerPath)) {
                moddedItems.add(innerPath)
            }
            return
        }
        if (filePath.endsWith(".xml.rawclobber") || filePath.endsWith(".rawclobber.xml")) {
            innerPath = filePath.replace(
                "[.](?:xml[.]rawclobber|rawclobber[.]xml)$".toRegex(),
                ".xml"
            )
            innerPath = checkCase(innerPath)
            log.warning(String.format("Copying xml as raw text: %s", innerPath))

            // Slipstream fiddles around with the line endings here,
            // we don't care as our parsers can tolerate either line ending.
            files[innerPath] = ModFileSource(filePath, mod)

            if (!moddedItems.contains(innerPath)) {
                moddedItems.add(innerPath)
            } else {
                log.warning(String.format("Clobbering earlier mods: %s", innerPath))
            }
            return
        }
        if (filePath.endsWith(".xml")) {
            innerPath = checkCase(innerPath)

            files[innerPath] = ReParseXMLFileSource(mod, filePath)

            if (!moddedItems.contains(innerPath)) {
                moddedItems.add(innerPath)
            } else {
                log.warning(String.format("Clobbering earlier mods: %s", innerPath))
            }
            return
        }
        if (filePath.endsWith(".txt")) {
            innerPath = checkCase(innerPath)

            // Don't care about newlines, our parsers can handle both
            files[innerPath] = ModFileSource(filePath, mod)

            if (!moddedItems.contains(innerPath)) {
                moddedItems.add(innerPath)
            } else {
                log.warning(String.format("Clobbering earlier mods: %s", innerPath))
            }
            return
        }

        innerPath = checkCase(innerPath)
        if (!moddedItems.contains(innerPath)) {
            moddedItems.add(innerPath)
        } else {
            log.warning(String.format("Clobbering earlier mods: %s", innerPath))
        }

        files[innerPath] = ModFileSource(filePath, mod)
    }


    /**
     * Checks if an innerPath exists, ignoring letter case.
     *
     * If there is no collision, the innerPath is added to the known lists.
     * A warning will be logged if a path with differing case exists.
     *
     * @return the existing path (if different), or innerPath
     */
    private fun checkCase(innerPath: String): String {
        // Use the original case, if we're replacing a file
        val lower = innerPath.lowercase(Locale.UK)
        var originalCase = toOriginalCase[lower]

        if (originalCase != null && innerPath != originalCase) {
            log.warning("Modded file's case doesn't match existing path: \"$innerPath\" vs \"$originalCase\"")
        }

        // If we haven't seen this filename before, register it
        if (originalCase == null) {
            originalCase = innerPath
            toOriginalCase[lower] = innerPath
        }

        return originalCase
    }

    private fun isJunkFile(filePath: String): Boolean {
        // Silently discard anything in a __MACOSX directory, as there
        // can be lots and lots of files there.
        if (filePath.contains("__MACOSX/")) {
            return true
        }

        // ModUtilities.isJunkFile uses a regex, which takes a few hundred ms
        // when loading Multiverse.
        // Thus match the behaviour with faster checks here.
        val lastStroke = filePath.lastIndexOf('/')
        val fileName = when {
            lastStroke == -1 -> filePath
            else -> filePath.substring(lastStroke + 1)
        }

        if (fileName == "thumbs.db" || fileName == ".dropbox" || fileName.endsWith(".DS_Store")
            || fileName.startsWith('~') || fileName.endsWith('~')
            || (fileName.startsWith('#') && fileName.endsWith('#'))
        ) {
            log.warning(String.format("Skipping junk file: %s", filePath))
            return true
        }

        return false
    }
}

/**
 * Represents a mod applied via the Slipstream patching process.
 *
 * This doesn't have to be a real Slipstream .ftl file: it could be a directory
 * of an un-zipped mod during development, or it could be assets taken from a
 * Java-based mod.
 */
interface SlipstreamMod {
    val name: String

    /**
     * The file paths of all the entries inside the mod.
     */
    val entries: List<String>

    fun openFile(name: String): InputStream

    /**
     * Put *something* into the message digest that's very, very likely to change
     * if the given file also changes.
     *
     * This is used to implement caching, to check if a cache is out of date or not.
     */
    fun addCacheContribution(name: String, digest: MessageDigest)
}

/**
 * Represents some way of obtaining a file - either loading it directly from
 * the FTL assets, from a mod, or some combination thereof (via patching it).
 */
interface FileSource {
    /**
     * The path of this file source, as shown in error messages.
     */
    val messagePath: String

    /**
     * Open a raw input stream of this file source.
     *
     * If you're going to parse this file into an XML document, use [openXML]
     * instead - that way, multiple back-to-back XML transforms don't have to
     * repeatedly serialise/parse the same file.
     */
    fun open(): InputStream

    fun openXML(): Document

    /**
     * Put *something* into the message digest that's very, very likely to change
     * if the result of this source changes.
     *
     * This is used to implement caching, to check if a cache is out of date or not.
     */
    fun addCacheContribution(digest: MessageDigest)

    companion object {
        /**
         * Convenience methods for subclasses that only want to implement [openXML].
         */
        fun openFromXML(document: Document): InputStream {
            val output = XMLOutputter(Format.getRawFormat())
            val string = output.outputString(document)
            return ByteArrayInputStream(string.toByteArray(Charsets.UTF_8))
        }

        /**
         * Utility method to parse a mod XML file, while wrapping it in a wrapper tag.
         *
         * If [removeFtlTags], all <FTL> tags are removed.
         */
        fun parseModdedXML(input: InputStream, messagePath: String, removeFtlTags: Boolean): Document {
            return BufferedReader(InputStreamReader(input)).use { reader ->
                parseModdedXML(reader, messagePath, removeFtlTags)
            }
        }

        private fun parseModdedXML(input: BufferedReader, messagePath: String, removeFtlTags: Boolean): Document {
            val wrapperStart = "<wrapper" +
                    " xmlns:mod='mod'" +
                    " xmlns:mod-append='mod-append'" +
                    " xmlns:mod-overwrite='mod-overwrite'" +
                    " xmlns:mod-prepend='mod-prepend'" +
                    " xmlns:mod-before='mod-before'" +
                    " xmlns:mod-after='mod-after'>"
            val wrapperEnd = "</wrapper>"

            val modified = StringBuilder()
            modified.append(wrapperStart)

            // We'll only target the first XML declaration, this is what
            // regular Slipstream does (plus it's faster).
            var foundXmlDecl = false

            while (true) {
                var line = input.readLine() ?: break

                // Get rid of the XML declaration
                if (!foundXmlDecl && line.contains("<?xml")) {
                    line = xmlDeclPattern.matcher(line).replaceFirst("")
                    foundXmlDecl = true
                }

                // Get rid of FTL tags.
                // This matches both start and end tags, but if it gets
                // a false-positive the only cost is running replace twice.
                if (removeFtlTags && line.contains("FTL>")) {
                    line = line
                        .replace("<FTL>", "")
                        .replace("</FTL>", "")
                }

                modified.append(line)
                modified.append('\n')
            }

            modified.append(wrapperEnd)

            return ModUtilities.parseStrictOrSloppyXML(modified, "$messagePath (wrapped)")
        }

        private val xmlDeclPattern = Pattern.compile("<\\?xml [^>]*\\?>")
    }
}

class VanillaFileSource(val df: IVanillaDatafile, val file: VanillaDatafile.Entry) : FileSource {
    override val messagePath: String get() = "FTL:${file.name}"

    override fun open(): InputStream {
        // TODO open and return a stream. This mostly matters for images.
        val bytes = df.read(file)
        return ByteArrayInputStream(bytes)
    }

    override fun openXML(): Document {
        // Vanilla always has <FTL> tags, this is the simple case
        val builder = com.pocketwormhole.android.SafeXML.builder()
        builder.expandEntities = false
        return open().use { builder.build(it) }
    }

    override fun addCacheContribution(digest: MessageDigest) {
        digest.update(messagePath.toByteArray(Charsets.UTF_8))

        // If the offset or length has changed, that ought to be enough
        // to catch changes to ftl.dat.
        val tmp = ByteBuffer.allocate(4 * 2)
        tmp.putInt(file.length)
        tmp.putInt(file.offset)
        tmp.flip()
        digest.update(tmp)
    }
}

class SlipstreamZipMod(val file: File) : SlipstreamMod, Closeable {
    override val name: String get() = file.name
    override val entries: List<String>

    private val zip = ZipFile(file)

    init {
        entries = zip.stream()
            .filter { !it.isDirectory }
            .map { it.name }
            // Non-standard zips, this is copied over from Slipstream.
            // I'm not entirely sure whether this is still needed, but there's
            // no harm in keeping it.
            .map { it.replace('\\', '/') }
            .collect(Collectors.toList())
    }

    override fun openFile(name: String): InputStream {
        val entry = zip.getEntry(name) ?: error("Missing entry '$name' for slipstream mod '$file'")
        return zip.getInputStream(entry)
    }

    override fun addCacheContribution(name: String, digest: MessageDigest) {
        val entry = zip.getEntry(name) ?: error("Missing entry '$name' for slipstream mod '$file'")

        digest.update("ZIP-MOD".toByteArray(Charsets.UTF_8))

        val tmp = ByteBuffer.allocate(8 * 3)
        tmp.putLong(entry.crc)
        tmp.putLong(entry.size)
        tmp.putLong(entry.lastModifiedTime.toMillis())
        tmp.flip()
        digest.update(tmp)
    }

    override fun close() {
        zip.close()
    }
}

/**
 * Represents an un-zipped Slipstream mod, which is often used for development.
 */
class SlipstreamDirectoryMod(val dir: Path) : SlipstreamMod, Closeable {
    override val name: String get() = dir.name

    override val entries: List<String> = Files.walk(dir)
        .filter { Files.isRegularFile(it) }
        .map { it.relativeTo(dir).toString() }
        // Always use forward strokes, regardless of platform
        .map { it.replace('\\', '/') }
        .collect(Collectors.toList())

    override fun openFile(name: String): InputStream {
        return Files.newInputStream(dir.resolve(name))
    }

    override fun addCacheContribution(name: String, digest: MessageDigest) {
        digest.update("FS-MOD".toByteArray(Charsets.UTF_8))

        // This follows symbolic links by default, which is good.
        val attributes = Files.readAttributes(
            dir.resolve(name),
            BasicFileAttributes::class.java
        )

        val tmp = ByteBuffer.allocate(8 * 3)
        tmp.putLong(attributes.size())
        tmp.putLong(attributes.creationTime().toMillis())
        tmp.putLong(attributes.lastModifiedTime().toMillis())
        tmp.flip()
        digest.update(tmp)
    }

    override fun close() {
        // Nothing required.
    }
}

class ModFileSource(val path: String, val mod: SlipstreamMod) : FileSource {
    override val messagePath: String get() = "${mod.name}:$path"

    override fun open(): InputStream {
        return mod.openFile(path)
    }

    override fun openXML(): Document {
        // This isn't really designed for handling XML files,
        // but the rawclobber mode uses it.
        println("[WARN] Loading ModFileSource as XML, won't add <FTL> tags: $messagePath")
        val builder = com.pocketwormhole.android.SafeXML.builder()
        builder.expandEntities = false
        return open().use { builder.build(it) }
    }

    override fun addCacheContribution(digest: MessageDigest) {
        digest.update("ModFileSource[$messagePath]".toByteArray(Charsets.UTF_8))
        mod.addCacheContribution(path, digest)
    }
}

class XSLTransformFileSource(
    val previous: FileSource,
    val mod: SlipstreamMod,
    val xslFilePath: String,
    val stylesheets: HashMap<String, ByteArray>
) : FileSource {
    override val messagePath: String get() = "${mod.name}:$xslFilePath"

    override fun open(): InputStream {
        return FileSource.openFromXML(openXML())
    }

    override fun openXML(): Document {
        val prevDoc = previous.openXML()

        mod.openFile(xslFilePath).use { transformStream ->
            return ModUtilities.transformDocument(prevDoc, transformStream, stylesheets)
        }
    }

    override fun addCacheContribution(digest: MessageDigest) {
        digest.update("XSLT[$messagePath,${stylesheets.size}]".toByteArray(Charsets.UTF_8))
        mod.addCacheContribution(xslFilePath, digest)

        for (stylesheet in stylesheets) {
            digest.update("sheet[${stylesheet.key}]".toByteArray(Charsets.UTF_8))

            // It's possible for this to cause problems if the stylesheet was
            // designed to cause them, since we don't have any kind of secure
            // marker at the end, but this isn't supposed to be secure.
            // It's only there for cache invalidation.
            digest.update(stylesheet.value)
        }

        previous.addCacheContribution(digest)
    }
}

class XMLPatchFileSource(
    val previous: FileSource,
    val mod: SlipstreamMod,
    val appendFilePath: String,
) : FileSource {
    override val messagePath: String get() = "${mod.name}:$appendFilePath"

    override fun open(): InputStream {
        return FileSource.openFromXML(openXML())
    }

    override fun openXML(): Document {
        val prevDoc = previous.openXML()

        val appendDoc = mod.openFile(appendFilePath).use { appendStream ->
            FileSource.parseModdedXML(appendStream, messagePath, true)
        }

        val patcher = XMLPatcher()
        patcher.setGlobalPanic(false)

        // In regular Slipstream, there's always a wrapper as the root
        // element for prevDoc, with the <FTL> element removed.
        // We're using an element where the FTL element is the root one,
        // so it ought to work fine.
        return patcher.patch(prevDoc, appendDoc)
    }

    override fun addCacheContribution(digest: MessageDigest) {
        digest.update("Patch[$messagePath]".toByteArray(Charsets.UTF_8))
        mod.addCacheContribution(appendFilePath, digest)
        previous.addCacheContribution(digest)
    }
}

class XMLRawAppendFileSource(
    val previous: FileSource,
    val mod: SlipstreamMod,
    val appendFilePath: String,
) : FileSource {
    override val messagePath: String get() = "${mod.name}:$appendFilePath"

    override fun open(): InputStream {
        previous.open().use { mainStream ->
            mod.openFile(appendFilePath).use { append ->
                return ModUtilities.appendXMLFile(
                    mainStream,
                    append,
                    "UTF-8",
                    previous.messagePath,
                    messagePath
                )
            }
        }
    }

    override fun openXML(): Document {
        // We can probably do this properly, do it the easy way for now
        // as this probably isn't used much?
        println("[WARN] Using .rawappend, the fast-path for this is not yet implemented: $messagePath")
        val builder = com.pocketwormhole.android.SafeXML.builder()
        builder.expandEntities = false
        return open().use { builder.build(it) }
    }

    override fun addCacheContribution(digest: MessageDigest) {
        digest.update("RawAppend[$messagePath]".toByteArray(Charsets.UTF_8))
        mod.addCacheContribution(appendFilePath, digest)
        previous.addCacheContribution(digest)
    }
}

class ReParseXMLFileSource(
    val mod: SlipstreamMod,
    val filePath: String,
) : FileSource {
    override val messagePath: String get() = "${mod.name}:$filePath"

    override fun open(): InputStream {
        return FileSource.openFromXML(openXML())
    }

    override fun openXML(): Document {
        val doc = mod.openFile(filePath).use { baseStream ->
            FileSource.parseModdedXML(baseStream, messagePath, false)
        }

        // Wrap the document in a root <FTL> tag if required
        if (doc.getRootElement().getChild("FTL") == null) {
            val newRoot = doc.getRootElement()
            val ftlNode = Element("FTL")
            val rootContent: List<Content> =
                ArrayList(newRoot.content)
            for (c in rootContent) {
                c.detach()
            }
            ftlNode.addContent(rootContent)
            newRoot.addContent(ftlNode)
        }

        // Now we're certain we have the FTL root element, delete
        // the wrapper element.
        val ftlNode = doc.detachRootElement().getChild("FTL")!!
        ftlNode.detach()
        doc.rootElement = ftlNode

        return doc
    }

    override fun addCacheContribution(digest: MessageDigest) {
        digest.update("ReParse[$messagePath]".toByteArray(Charsets.UTF_8))
        mod.addCacheContribution(filePath, digest)
    }
}

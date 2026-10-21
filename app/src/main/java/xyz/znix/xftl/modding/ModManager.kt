package xyz.znix.xftl.modding

import xyz.znix.xftl.sys.PlatformSpecific
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * Installs, enables and removes the Slipstream mods in the mods folder.
 *
 * Datafile.loadWithMods only reads `order.txt`, loading each line as a mod
 * and skipping lines starting with `//`. A disabled mod is kept in that file
 * as `//name`, so it stays in its place in the load order when re-enabled.
 * Mods are applied when the datafile loads, so changes need a restart.
 */
object ModManager {
    class Entry(val name: String, val enabled: Boolean)

    class InvalidModException(message: String) : Exception(message)

    private val modDir: Path get() = PlatformSpecific.INSTANCE.modsDirectory
    private val orderFile: Path get() = modDir.resolve("order.txt")

    /**
     * The installed mods, in load order. Mods in the folder but missing
     * from order.txt are listed (disabled) at the end.
     */
    fun list(): List<Entry> {
        val entries = ArrayList<Entry>()
        if (Files.isRegularFile(orderFile)) {
            for (rawLine in Files.readAllLines(orderFile, StandardCharsets.UTF_8)) {
                val line = rawLine.trim()
                if (line.isEmpty())
                    continue

                // Treat '//name' as a disabled mod only if that mod exists,
                // so ordinary comments aren't shown as mods.
                val enabled = !line.startsWith("//")
                val name = line.removePrefix("//").trim()
                if (name.isEmpty() || !Files.exists(modDir.resolve(name)))
                    continue
                if (entries.none { it.name == name })
                    entries += Entry(name, enabled)
            }
        }

        if (Files.isDirectory(modDir)) {
            Files.list(modDir).use { files ->
                for (file in files.sorted()) {
                    val name = file.fileName.toString()
                    if (name == "order.txt" || entries.any { it.name == name })
                        continue
                    if (Files.isDirectory(file) || isModFileName(name))
                        entries += Entry(name, false)
                }
            }
        }

        return entries
    }

    fun setEnabled(name: String, enabled: Boolean) {
        save(list().map { if (it.name == name) Entry(name, enabled) else it })
    }

    /**
     * Copy a mod into the mods folder and enable it, at the end of the load
     * order. Returns the name it was saved under.
     */
    fun install(fileName: String, bytes: ByteArray): String {
        validate(bytes)

        // Only keep the file's name: no paths, and nothing that would
        // confuse order.txt.
        var name = fileName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9 ._()+-]"), "_").trim().trimStart('.')
        if (!isModFileName(name))
            name = "${name.ifEmpty { "mod" }}.ftl"

        Files.createDirectories(modDir)
        Files.write(modDir.resolve(name), bytes)

        save(list().filter { it.name != name } + Entry(name, true))
        return name
    }

    fun delete(name: String) {
        val remaining = list().filter { it.name != name }
        val file = modDir.resolve(name)
        if (Files.isRegularFile(file))
            Files.delete(file)
        save(remaining)
    }

    private fun save(entries: List<Entry>) {
        Files.createDirectories(modDir)
        val lines = entries.map { if (it.enabled) it.name else "//${it.name}" }
        Files.write(orderFile, lines, StandardCharsets.UTF_8)
    }

    private fun isModFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".ftl") || lower.endsWith(".zip")
    }

    /**
     * Check this is a zip with something a Slipstream mod would contain,
     * so picking the wrong file doesn't break the game on the next start.
     */
    private fun validate(bytes: ByteArray) {
        var anyEntries = false
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    anyEntries = true
                    val top = entry.name.substringBefore('/').lowercase()
                    if (top in MOD_FOLDERS)
                        return
                }
            }
        } catch (e: Exception) {
            throw InvalidModException("Not a mod file (not a .ftl or .zip): ${e.message}")
        }

        if (!anyEntries)
            throw InvalidModException("Not a mod file (not a .ftl or .zip)")
        throw InvalidModException("Not an FTL mod (no data, img, audio or fonts folder inside)")
    }

    private val MOD_FOLDERS = setOf("data", "img", "audio", "fonts", "mod-appendix")
}

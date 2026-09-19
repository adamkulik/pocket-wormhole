package xyz.znix.xftl.sys

import xyz.znix.xftl.game.SaveProfile
import java.nio.file.Path
import java.nio.file.Paths

sealed interface PlatformSpecific {
    /**
     * The directory the user's saves should be stored in.
     */
    val saveGamePath: Path

    val saveProfilePath: Path get() = saveGamePath.resolve(SaveProfile.PROFILE_NAME)
    val modsDirectory: Path get() = saveGamePath.resolve("mods")

    /**
     * The path to a text file, containing the path of the ftl.dat file.
     */
    val ftlDatPathFile: Path get() = saveGamePath.resolve("ftl-path.txt")

    /**
     * The path to a directory, where the cache for modded XML files is stored.
     */
    val xmlCacheDirectory: Path get() = saveGamePath.resolve("mod-xml-cache")

    /**
     * Look through the OS's running processes, to find FTL.
     *
     * If it's running, find the path to it's ftl.dat file.
     */
    fun findRunningInstanceDat(): Path?

    /**
     * True when the game is driven by a touch screen, so the UI should
     * offer touch-friendly affordances (eg the iPad port's auto-pause +
     * room-selection mode when crew are selected).
     */
    val isTouchUi: Boolean get() = false

    /**
     * Hooks for moving saves in and out of the game on platforms where the
     * save directory isn't directly user-accessible (Android's SAF). Null on
     * platforms that expose the save directory directly (desktop), in which
     * case UIs should hide their transfer affordances.
     */
    open val saveTransfer: SaveTransfer? get() = null

    /**
     * Transfer a vanilla-format continue.sav between the app and a
     * user-chosen location.
     */
    interface SaveTransfer {
        /** Ask the user for a destination and write [bytes] (a continue.sav). */
        fun exportSave(bytes: ByteArray)

        /** Ask the user to pick a continue.sav; [onResult] receives its
         * bytes, or null if the user cancelled. May be invoked on any
         * thread. */
        fun importSave(onResult: (ByteArray?) -> Unit)
    }

    companion object {
        @JvmField
        val INSTANCE: PlatformSpecific = AndroidPlatform

        /**
         * Must be called by the Android launcher before the game starts,
         * pointing at the app's private storage directory.
         */
        fun setBaseDir(path: Path) {
            AndroidPlatform.baseDir = path
        }
    }
}

/**
 * Android platform support. All data (saves, ftl.dat, mods) lives inside the
 * app's private storage, provided by the launcher activity.
 */
object AndroidPlatform : PlatformSpecific {
    var baseDir: Path = Paths.get(".")

    override val isTouchUi: Boolean get() = true

    /** Wired up by [com.pocketwormhole.android.MainActivity] at startup. */
    override var saveTransfer: PlatformSpecific.SaveTransfer? = null

    override val saveGamePath: Path
        get() = baseDir.resolve("ProjectWormhole")

    override fun findRunningInstanceDat(): Path? = null
}

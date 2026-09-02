package com.pocketwormhole.android

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import kotlin.concurrent.thread

/**
 * Tees the app's own logcat output to rotating session files in both the
 * internal and the external app-files directories, so failures on a physical
 * device can be diagnosed without a live adb tether.
 *
 * Captured tags: XFTL (engine), AndroidRuntime / System.err (Java crash traces)
 * and libc "Fatal signal" lines (native crashes, which CrashCatcher's Java
 * handler never sees). Keeps only the newest few sessions per directory.
 *
 * Retrieval note: on Android 11+ the shell user and file managers cannot
 * browse other apps' dirs under Android/data (MTP shows it empty too), so
 * pull these with run-as (works because debug builds are debuggable):
 *
 *   adb shell run-as com.pocketwormhole.android ls -la files
 *   adb exec-out run-as com.pocketwormhole.android cat files/log-session-<ts>.txt > log.txt
 */
object LogTee {
    private const val PREFIX = "log-session-"
    private const val KEEP = 5

    private val tags = arrayOf(
        "XFTL:V", "AndroidRuntime:E", "System.err:W", "libc:F", "art:E", "*:S"
    )

    fun start(app: Context) {
        val header = "=== FTL-Android session ${System.currentTimeMillis()} | " +
                "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT}) ==="
        val dirs = listOfNotNull(app.getExternalFilesDir(null), app.filesDir)

        thread(name = "logcat-tee", isDaemon = true) {
            try {
                // --pid exits logcat when our process dies (e.g. native crash)
                // and filters to only our lines.
                val pid = Process.myPid()
                var proc = try {
                    ProcessBuilder("logcat", "-v", "time", "--pid=$pid", *tags)
                        .redirectErrorStream(true)
                        .start()
                } catch (_: Exception) {
                    null // ancient logcat without --pid; fall back below
                } ?: ProcessBuilder("logcat", "-v", "time", *tags)
                    .redirectErrorStream(true)
                    .start()

                val writers = dirs.map { dir ->
                    File(dir, PREFIX + System.currentTimeMillis() + ".txt")
                        .bufferedWriter()
                        .also { it.write(header); it.newLine(); it.flush() }
                }

                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            for (w in writers) {
                                try {
                                    w.write(line); w.newLine(); w.flush()
                                } catch (_: Exception) {
                                    // Directory vanished mid-run; keep the others going
                                }
                            }
                        }
                    }
                } finally {
                    writers.forEach { runCatching { it.close() } }
                    proc.destroy()
                }
            } catch (_: Throwable) {
                // Diagnostics must never take the app down
            } finally {
                prune(dirs)
            }
        }
    }

    private fun prune(dirs: List<File>) {
        for (dir in dirs) {
            runCatching {
                dir.listFiles { f -> f.name.startsWith(PREFIX) }
                    ?.sortedByDescending { it.name }
                    ?.drop(KEEP)
                    ?.forEach { it.delete() }
            }
        }
    }
}

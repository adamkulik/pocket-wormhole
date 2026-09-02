package xyz.znix.xftl.sys

import java.io.InputStream

/**
 * Loads engine resources (shaders, fonts, XML data).
 *
 * On the desktop these live on the classpath; on Android the app installs an
 * opener that reads from the APK's assets/resources directory.
 */
object XftlResources {
    @Volatile
    var opener: ((String) -> InputStream?)? = null

    @JvmStatic
    fun open(path: String): InputStream? {
        val p = path.removePrefix("/")
        opener?.let { return it(p) }
        return XftlResources::class.java.classLoader.getResourceAsStream(p)
    }
}

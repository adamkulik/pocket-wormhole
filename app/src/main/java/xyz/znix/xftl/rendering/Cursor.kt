package xyz.znix.xftl.rendering

import xyz.znix.xftl.Datafile
import xyz.znix.xftl.FTLFile
import xyz.znix.xftl.sys.INativeResource
import xyz.znix.xftl.sys.ResourceContext

/**
 * Represents a mouse cursor.
 *
 * On Android there is no OS cursor; touch input is used directly (as on the
 * official iPad version of FTL), so this is a stub that keeps the engine's
 * resource-management invariants intact.
 */
class Cursor(
    context: ResourceContext,
    df: Datafile,
    val mainFile: FTLFile,
    overlayFile: FTLFile?
) : INativeResource {
    override var freed: Boolean = false
        private set

    init {
        context.register(this)
    }

    fun setActive(glfwWindow: Long) {
        // No OS cursors on Android
    }

    override fun free() {
        check(!freed) { "Cannot double-free cursor '${mainFile.name}'" }
        freed = true
    }
}

package xyz.znix.xftl.sys

/**
 * A class to track the allocation of native resources (such as OpenGL
 * textures) and bulk-free them as appropriate.
 *
 * Note that on Linux, the NVIDIA drivers double-free some memory unless you
 * delete *all* your images before exiting (though this seems LWJGL-related),
 * which is annoying for debugging as the process locks up while a coredump
 * is generated.
 */
class ResourceContext {
    private val resources = ArrayList<INativeResource>()
    private var freed: Boolean = false

    fun register(resource: INativeResource) {
        if (freed) {
            throw IllegalArgumentException("Cannot register resources to freed context")
        }

        if (resource.freed) {
            throw IllegalArgumentException("Cannot register already-freed resource $resource")
        }

        resources.add(resource)
    }

    fun freeAll() {
        for (resource in resources) {
            // Don't double-free resources
            if (resource.freed)
                continue

            resource.free()
        }

        freed = true
    }
}

interface INativeResource {
    val freed: Boolean

    fun free()
}

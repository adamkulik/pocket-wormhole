package xyz.znix.xftl.sys

import xyz.znix.xftl.rendering.Cursor

interface GameContainer {
    val input: Input
    val width: Int
    val height: Int

    fun exit()

    /**
     * Switch to the given cursor image, or the default image if [cursor]
     * is null.
     */
    fun setCursor(cursor: Cursor?)
}

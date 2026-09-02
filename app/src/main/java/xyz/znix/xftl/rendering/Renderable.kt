package xyz.znix.xftl.rendering

// Copied from Slick, changed to use XFTL's colour class.

/**
 * Description of anything that can be drawn
 *
 * @author kevin
 */
interface Renderable {
    /**
     * Draw this artefact at the given location
     *
     * @param x The x coordinate to draw the artefact at
     * @param y The y coordinate to draw the artefact at
     */
    fun draw(x: Float, y: Float)

    /**
     * Draw this artefact at the given location
     *
     * @param x The x coordinate to draw the artefact at
     * @param y The y coordinate to draw the artefact at
     * @param filter The color filter to apply when drawing
     */
    fun draw(x: Float, y: Float, filter: Colour)

    /**
     * Draw this artefact at the given location with the specified size
     *
     * @param x The x coordinate to draw the artefact at
     * @param y The y coordinate to draw the artefact at
     * @param width The width to render the artefact at
     * @param height The width to render the artefact at
     */
    fun draw(x: Float, y: Float, width: Float, height: Float)

    /**
     * Draw this artefact at the given location with the specified size
     *
     * @param x The x coordinate to draw the artefact at
     * @param y The y coordinate to draw the artefact at
     * @param width The width to render the artefact at
     * @param height The width to render the artefact at
     * @param filter The color filter to apply when drawing
     */
    fun draw(x: Float, y: Float, width: Float, height: Float, filter: Colour)
}

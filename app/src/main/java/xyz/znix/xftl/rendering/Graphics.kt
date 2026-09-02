package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL11
import xyz.znix.xftl.f
import xyz.znix.xftl.math.Matrix3f
import kotlin.math.*

/**
 * A custom graphics class, whose functions generally match that of [org.newdawn.slick.Graphics].
 *
 * See it's JavaDoc for descriptions of these methods.
 */
class Graphics {
    var lineWidth: Float = 1f

    var colour: Colour = Colour.white

    /**
     * The tooltip provider for whatever is currently moused over.
     *
     * It's a bit of a hack to put this in here, but every other option is worse.
     *
     * This isn't actually read by this class, instead whatever state (eg the
     * in-game or menu state) should read and render from it.
     */
    var tooltip: ITooltipProvider? = null

    // The stack of transforms from pushTransform/popTransform calls.
    private val transformStack = ArrayList<Matrix3f>()

    // The 'live' transform that we're currently rendering with.
    // This is NOT on transformStack until pushTransform is called.
    private var transform = Matrix3f()

    // These must be lazy-initialised, as the Graphics instance
    // is created before the OpenGL context is set.
    private val quadRenderer by lazy { BulkColourRenderer(GL11.GL_QUADS) }
    private val triangleRenderer by lazy { BulkColourRenderer(GL11.GL_TRIANGLES) }
    private val lineRenderer by lazy { BulkColourRenderer(GL11.GL_LINES) }
    private val imageRenderer by lazy { BulkImageRenderer() }

    // When non-zero, the renderers shouldn't be flushed if possible
    private var flushSuppression: Int = 0

    fun fillRect(x: Int, y: Int, width: Int, height: Int) {
        fillRect(x.f, y.f, width.f, height.f)
    }

    fun fillRect(x: Float, y: Float, width: Float, height: Float) {
        quadRenderer.pushVert(x, y, colour)
        quadRenderer.pushVert(x + width, y, colour)
        quadRenderer.pushVert(x + width, y + height, colour)
        quadRenderer.pushVert(x, y + height, colour)
        if (flushSuppression == 0)
            quadRenderer.flush()
    }

    fun drawRect(x: Int, y: Int, widthMinusOne: Int, heightMinusOne: Int) {
        drawRect(x.f, y.f, widthMinusOne.f, heightMinusOne.f)
    }

    fun drawRect(x: Float, y: Float, widthMinusOne: Float, heightMinusOne: Float) {
        drawBatched {
            fillRect(x, y, widthMinusOne, 1f)
            fillRect(x, y, 1f, heightMinusOne)
            fillRect(x + widthMinusOne, y, 1f, heightMinusOne)
            fillRect(x, y + heightMinusOne, widthMinusOne + 1f, 1f)
        }
    }

    fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int) {
        drawLine(x1.f, y1.f, x2.f, y2.f)
    }

    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        // Use rectangles to draw straight lines, copied from Slick's graphics class.
        // This ensures that the vast majority of lines won't vary between OpenGL
        // implementations, which can vary by upto one pixel.
        // Also, it means we won't have to change all our current line-rendering
        // stuff, since this works the same.
        val rectWidth = lineWidth - 1
        if (x1 == x2) {
            val yMin = min(y1, y2)
            val height = abs(y1 - y2)
            val step = 1f
            fillRect(x1 - (rectWidth / 2f), yMin - (rectWidth / 2f), rectWidth + step, height + rectWidth + step)
            return
        } else if (y1 == y2) {
            val xMin = min(x1, x2)
            val width = abs(x1 - x2)
            val step = 1f
            fillRect(xMin - (rectWidth / 2.0f), y1 - (rectWidth / 2.0f), width + rectWidth + step, rectWidth + step)
            return
        }

        lineRenderer.pushVert(x1, y1, colour)
        lineRenderer.pushVert(x2, y2, colour)
        if (flushSuppression == 0)
            lineRenderer.flush()
    }

    fun fillOval(x: Float, y: Float, width: Float, height: Float) {
        fillArc(x, y, width, height, 50, 0f, 360f)
    }

    fun drawOval(x: Float, y: Float, width: Float, height: Float) {
        drawArc(x, y, width, height, 50, 0f, 360f)
    }

    /**
     * Draw a 'pie slice' of an image, where any pixel between given angles
     * from the centre is drawn. The 'pie'-ness doesn't imply the outside
     * of the image is round, merely that the angles are set.
     *
     * This is used for rendering the system ion 'clock' (the blue ring around
     * the timer number of an ionised system), but mods can re-use it for
     * other stuff too.
     */
    fun drawImagePieSlice(image: Image, x: Float, y: Float, startAngle: Float, endAngle: Float, filter: Colour) {
        fun point(px: Float, py: Float) {
            imageRenderer.pushVert(
                px + x, py + y,
                px, py,
                filter.r, filter.g, filter.b, filter.a
            )
        }

        fun pointByAngle(angle: Float) {
            val px = (cos(angle) * 0.5f + 0.5f) * image.width
            val py = (-sin(angle) * 0.5f + 0.5f) * image.height
            point(px, py)
        }

        // Draw a bunch of triangles covering different parts of the image,
        // each one covering a specific angle.
        fun drawTriangle(offset: Int, minAngleDeg: Int, maxAngleDeg: Int) {
            val minAngle = Math.toRadians((offset + minAngleDeg).toDouble()).toFloat()
            val maxAngle = Math.toRadians((offset + maxAngleDeg).toDouble()).toFloat()

            // If this triangle doesn't intersect the desired area, skip it
            if (endAngle < minAngle || maxAngle < startAngle)
                return

            val thisStartAngle = max(minAngle, startAngle)
            val thisEndAngle = min(maxAngle, endAngle)

            point(image.width / 2f, image.height / 2f)
            pointByAngle(thisEndAngle)
            pointByAngle(thisStartAngle)
        }

        // Draw a bunch of triangles. The constraint with how
        // large they can each be is that the line between
        // their two points can't cut through the middle of the image.
        // Go around twice, to support sections that wrap almost all
        // the way around and end in their original section.
        drawTriangle(0, 0, 45)
        drawTriangle(0, 45, 135)
        drawTriangle(0, 135, 225)
        drawTriangle(0, 225, 270)
        drawTriangle(0, 270, 360)
        drawTriangle(360, 0, 45)
        drawTriangle(360, 45, 135)
        drawTriangle(360, 135, 225)
        drawTriangle(360, 225, 270)
        drawTriangle(360, 270, 360)

        imageRenderer.imageFiltering = Image.DEFAULT_TEXTURE_FILTERING
        imageRenderer.flush(image)
    }

    fun drawCustomQuads(fn: (BulkColourRenderer) -> Unit) {
        fn(quadRenderer)
        if (flushSuppression == 0)
            quadRenderer.flush()
    }

    /**
     * Run a function where the order of the draws don't matter.
     *
     * This can batch together most non-image draws into a single drawcall,
     * significantly improving efficiency.
     *
     * This function is reentrant: it can be called inside of itself, so
     * a function won't disrupt it's caller, like the following code would
     * if there was a simple on/off function:
     *
     * ```
     * enableBatching()
     *   // In subroutine
     *   enableBatching()
     *   fillRect()
     *   disableBatching()
     * // Batching is now disabled by the subroutine!
     * disableBatching()
     * ```
     *
     * This function is inlined to avoid creating a closure function.
     */
    inline fun drawBatched(fn: () -> Unit) {
        nestedEnableBatching()
        fn()
        nestedDisableBatching()
    }

    /**
     * Use [drawBatched] instead!
     *
     * This is required since that's an inline function, which can't access
     * private variables directly.
     */
    fun nestedEnableBatching() {
        flushSuppression++
    }

    /**
     * Use [drawBatched] instead!
     */
    fun nestedDisableBatching() {
        flushSuppression--

        if (flushSuppression == 0) {
            quadRenderer.flush()
            triangleRenderer.flush()
            lineRenderer.flush()
        }
    }

    fun pushTransform() {
        transformStack.add(Matrix3f(transform))
    }

    fun popTransform() {
        transform = transformStack.removeAt(transformStack.size - 1)
    }

    @Suppress("ReplaceWithOperatorAssignment")
    fun translate(x: Float, y: Float) {
        // Do transform = transform*translate which means we're effectively
        // using a translation matrix which is an identity matrix, aside from
        // its right-hand column. That then gets dot-product-ed into the
        // top and middle row of the transform matrix to get the new right-hand
        // column of the transform matrix.
        transform.m02 = x * transform.m00 + y * transform.m01 + transform.m02
        transform.m12 = x * transform.m10 + y * transform.m11 + transform.m12
    }

    /**
     * Rotates subsequent drawing by [degrees], around the point at [pointX],[pointY].
     */
    fun rotate(pointX: Float, pointY: Float, degrees: Float) {
        val radians = Math.toRadians(degrees.toDouble()).toFloat()
        rotateRadians(pointX, pointY, radians)
    }

    fun rotateRadians(pointX: Float, pointY: Float, angle: Float) {
        // Translate so the rotation point is at the origin
        translate(pointX, pointY)

        // Effectively, we build a 2x2 matrix to use for our rotations. We can
        // trivially expand it by assuming all but the bottom-right entries of
        // the 3rd row and column are zero, since we're not applying a translation.
        // In case you're not familiar with transform matrices, we're effectively
        // translating the x and y unit vectors in our post-rotation space
        // into the pre-rotation space.
        val m00 = cos(angle) // X part of the x unit vector
        val m10 = sin(angle) // Y part of the x unit vector
        val m01 = -sin(angle) // X part of the y unit vector
        val m11 = cos(angle) // Y part of the y unit vector

        // Do current*rotate matrix multiply, which only affects the top-left 2x2.
        // The bottom row is always 0,0,1 and for the right column, our rotation
        // matrix's right column is implicitly 0,0,1 (as stated above) so it'll
        // just be multiplying the current values by one.
        val newM00 = m00 * transform.m00 + m10 * transform.m01
        val newM10 = m00 * transform.m10 + m10 * transform.m11
        val newM01 = m01 * transform.m00 + m11 * transform.m01
        val newM11 = m01 * transform.m10 + m11 * transform.m11

        transform.m00 = newM00
        transform.m10 = newM10
        transform.m01 = newM01
        transform.m11 = newM11

        // Undo the origin point translation we did at the start
        translate(-pointX, -pointY)
    }

    fun scale(xScaling: Float, yScaling: Float) {
        // Multiplying by a scaled identity matrix (where m00 and m11
        // come from the x and y scaling factors) results in scaling the
        // first two columns.
        transform.m00 *= xScaling
        transform.m10 *= xScaling
        transform.m01 *= yScaling
        transform.m11 *= yScaling
    }

    fun clear(colour: Colour) {
        GL11.glClearColor(colour.r, colour.g, colour.b, colour.a)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)

        // In case something went horribly wrong last frame
        flushSuppression = 0

        // We don't want the tooltip to stick around between frames.
        tooltip = null
    }

    fun checkNoPushedTransforms() {
        require(transformStack.size == 0)
    }

    fun loadIdentityMatrix() {
        transform = Matrix3f()
    }

    /**
     * Use this instance's transform matrix for image rendering.
     */
    fun markCurrentImageTransformSource() {
        CURRENT = this
    }

    private fun drawArc(
        x1: Float, y1: Float, width: Float, height: Float,
        segments: Int, start: Float, end: Float
    ) {
        addArcVertices(x1, y1, width, height, segments, start, end) { _, _, lastX, lastY, x, y ->
            lineRenderer.pushVert(lastX, lastY, colour)
            lineRenderer.pushVert(x, y, colour)
        }

        if (flushSuppression == 0)
            lineRenderer.flush()
    }

    private fun fillArc(
        x1: Float, y1: Float, width: Float, height: Float,
        segments: Int, start: Float, end: Float
    ) {
        addArcVertices(x1, y1, width, height, segments, start, end) { cx, cy, lastX, lastY, x, y ->
            triangleRenderer.pushVert(cx, cy, colour)
            triangleRenderer.pushVert(x, y, colour)
            triangleRenderer.pushVert(lastX, lastY, colour)
        }

        if (flushSuppression == 0)
            triangleRenderer.flush()
    }

    private fun addArcVertices(
        x1: Float, y1: Float, width: Float, height: Float,
        segments: Int, start: Float, end: Float,
        fn: (Float, Float, Float, Float, Float, Float) -> Unit
    ) {
        // This was copied from Slick's graphics class, factored out
        // from drawing filled and non-filled arcs.

        require(end >= start)

        val cx = x1 + width / 2.0f
        val cy = y1 + height / 2.0f
        val step = 360 / segments
        var a = start.toInt()
        var lastX: Float? = null
        var lastY: Float? = null
        while (a < (end + step).toInt()) {
            var ang = a.toFloat()
            if (ang > end) {
                ang = end
            }
            val x = (cx + cos(Math.toRadians(ang.toDouble())) * width / 2.0f).toFloat()
            val y = (cy + sin(Math.toRadians(ang.toDouble())) * height / 2.0f).toFloat()
            a += step

            if (lastX != null && lastY != null) {
                fn(cx, cy, lastX, lastY, x, y)
            }

            lastX = x
            lastY = y
        }
    }

    private fun drawImage(
        image: Image,
        x: Float, y: Float,
        x2: Float, y2: Float,
        srcX1: Float, srcY1: Float,
        srcX2: Float, srcY2: Float,
        filter: Colour,
        alpha: Float
    ) {
        imageRenderer.pushImage(
            x, y, x2, y2,
            srcX1, srcY1, srcX2, srcY2,
            filter.r, filter.g, filter.b, alpha
        )
        imageRenderer.flush(image)
    }

    companion object {
        private var CURRENT: Graphics? = null

        fun getTextureTransformMatrix(): Matrix3f {
            return CURRENT!!.transform
        }

        /**
         * Called by [Image]'s draw function, should not be used elsewhere.
         */
        fun internalDrawImage(
            image: Image,
            x: Float, y: Float,
            x2: Float, y2: Float,
            srcX1: Float, srcY1: Float,
            srcX2: Float, srcY2: Float,
            filter: Colour,
            alpha: Float
        ) {
            CURRENT!!.drawImage(
                image,
                x, y, x2, y2,
                srcX1, srcY1, srcX2, srcY2,
                filter, alpha
            )
        }
    }
}

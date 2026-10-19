package xyz.znix.xftl.game

import xyz.znix.xftl.math.IPoint
import kotlin.random.Random

/**
 * One nebula cloud image to draw on the beacon map.
 */
internal data class NebulaCloudPlan(
    /**
     * Index into the chosen (small or large) image list.
     */
    val imageIndex: Int,

    /**
     * Left edge of the cloud, in sector/beacon coordinates.
     */
    val x: Float,

    /**
     * Top edge of the cloud, in sector/beacon coordinates.
     */
    val y: Float,
)

internal data class NebulaCloudLayout(
    /**
     * True if [clouds] index into the large image list rather than the small one.
     */
    val useLargeImages: Boolean,

    val clouds: List<NebulaCloudPlan>,
)

/**
 * Vanilla uses the large nebula images when there are more than this many
 * nebula beacons in the sector, and the small images otherwise.
 */
internal const val NEBULA_LARGE_IMAGE_THRESHOLD = 5

/**
 * How many times vanilla tries to grow a cloud cluster onto another nebula
 * beacon before it gives up and starts a new cluster at that beacon.
 */
internal const val NEBULA_CLOUD_MAX_MISSES = 21

private const val NEBULA_CLOUD_MARGIN = 5

/**
 * Lay out the beacon map's nebula clouds.
 *
 * This mirrors vanilla's cloud generator (FTLGame.exe 1.6.14, the function
 * that copies either `img/map/nebula_0..3.png` or
 * `img/map/nebula_large_0..2.png` into its working set): the first cloud is
 * centred on a nebula beacon, and every following cloud is placed so it
 * overlaps a random existing cloud while covering another nebula beacon.
 * That overlap is what makes the nebula areas connect instead of looking
 * like a handful of disconnected dots. After [NEBULA_CLOUD_MAX_MISSES]
 * failed attempts, a new cluster is started at a beacon that is still
 * uncovered.
 *
 * Only the layout is computed here, so it can be unit-tested without a GL
 * context; [JumpWindow] supplies the real image sizes and resolves the
 * indices back to images.
 */
internal fun planNebulaClouds(
    nebulaPositions: List<IPoint>,
    smallImageSizes: List<IPoint>,
    largeImageSizes: List<IPoint>,
    random: Random = Random.Default,
): NebulaCloudLayout {
    val useLarge = nebulaPositions.size > NEBULA_LARGE_IMAGE_THRESHOLD
    val sizes = if (useLarge) largeImageSizes else smallImageSizes

    if (nebulaPositions.isEmpty() || sizes.isEmpty())
        return NebulaCloudLayout(useLarge, emptyList())

    val remaining = nebulaPositions.toMutableList()
    val clouds = ArrayList<NebulaCloudPlan>()

    var imageIndex = random.nextInt(sizes.size)
    var target = remaining[random.nextInt(remaining.size)]
    var size = sizes[imageIndex]
    var x = target.x - size.x / 2f
    var y = target.y - size.y / 2f
    var misses = 0

    while (remaining.isNotEmpty()) {
        val covered = remaining.filter { pos ->
            x + NEBULA_CLOUD_MARGIN < pos.x && pos.x < x + size.x - NEBULA_CLOUD_MARGIN &&
                y + NEBULA_CLOUD_MARGIN < pos.y && pos.y < y + size.y - NEBULA_CLOUD_MARGIN
        }

        if (covered.isEmpty()) {
            misses++
        } else {
            clouds += NebulaCloudPlan(imageIndex, x, y)
            remaining.removeAll(covered)

            if (remaining.isEmpty())
                break
        }

        if (misses < NEBULA_CLOUD_MAX_MISSES && clouds.isNotEmpty()) {
            // Grow the existing cluster: overlap a random cloud.
            val base = clouds[random.nextInt(clouds.size)]
            val baseSize = sizes[base.imageIndex]
            imageIndex = random.nextInt(sizes.size)
            size = sizes[imageIndex]
            x = base.x + random.nextInt(baseSize.x + size.x) - size.x
            y = base.y + random.nextInt(baseSize.y + size.y) - size.y
        } else {
            // Too many misses: start a new cluster at an uncovered beacon.
            misses = 0
            target = remaining[random.nextInt(remaining.size)]
            imageIndex = random.nextInt(sizes.size)
            size = sizes[imageIndex]
            x = target.x - size.x / 2f
            y = target.y - size.y / 2f
        }
    }

    return NebulaCloudLayout(useLarge, clouds)
}

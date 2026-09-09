package io.devkit.chartkit.three

/** Which side wall a frame draws. */
enum class Chart3DSideWall {

    /** Neither. */
    None,

    /**
     * Whichever one is *behind* the data, chosen from the camera's yaw.
     *
     * The only choice that works while a reader is rotating the chart. Drawing
     * both puts one wall between the reader and the columns; fixing one puts it
     * there as soon as the chart is turned the other way.
     */
    Auto,

    Left,
    Right,
}

/** Which surfaces carry projected value grid lines. */
enum class Chart3DFrameGrid {
    None,
    Back,
    Floor,
    Both,
}

/**
 * The floor and walls a 3D plot stands in.
 *
 * ### Restraint is the design
 *
 * Three surfaces at most, drawn faintly, with grid lines on one of them by
 * default. A frame's job is to give the eye a reference plane so a floating
 * column has somewhere to be measured from; every extra plane and every extra
 * line competes with the data for the same contrast budget, and a chart boxed
 * in on six sides is harder to read than one with no frame at all.
 *
 * @param floor the plane the columns stand on.
 * @param back the plane behind them, which the value grid is usually drawn on.
 * @param side which vertical side wall, if any.
 * @param grid which surfaces carry value grid lines.
 * @param opacity how strongly the panels are painted over the chart's
 *   background, `0..1`.
 */
data class Chart3DFrame(
    val floor: Boolean = true,
    val back: Boolean = true,
    val side: Chart3DSideWall = Chart3DSideWall.Auto,
    val grid: Chart3DFrameGrid = Chart3DFrameGrid.Back,
    val opacity: Float = DEFAULT_OPACITY,
) {
    init {
        if (!opacity.isFinite() || opacity !in 0f..1f) {
            throw Chart3DException("Frame opacity must be in [0, 1], was $opacity")
        }
    }

    val isVisible: Boolean get() = floor || back || side != Chart3DSideWall.None

    /**
     * The frame's panels as scene objects, given the volume and the view.
     *
     * Each panel is a [Cuboid3D] of zero thickness. That is not a trick to
     * avoid writing a quad primitive — it is what makes the panels go through
     * exactly the same culling, lighting and depth handling as the data, so a
     * floor seen from below disappears for the same reason a column's bottom
     * face does. Its four edge faces have no area, produce a zero normal, and
     * are dropped by the projector.
     */
    fun panelsFor(volume: Bounds3D, camera: Chart3DCamera): List<Chart3DObject> {
        if (!isVisible || !volume.isFinite) return emptyList()
        val panels = ArrayList<Chart3DObject>(3)
        if (floor) {
            panels += panel(
                Cuboid3D(
                    x = volume.minX, width = volume.width,
                    yStart = volume.minY, yEnd = volume.minY,
                    z = volume.minZ, depth = volume.depth,
                ),
            )
        }
        if (back) {
            panels += panel(
                Cuboid3D(
                    x = volume.minX, width = volume.width,
                    yStart = volume.minY, yEnd = volume.maxY,
                    z = volume.maxZ, depth = 0.0,
                ),
            )
        }
        when (resolveSide(camera)) {
            Chart3DSideWall.Left -> panels += panel(
                Cuboid3D(
                    x = volume.minX, width = 0.0,
                    yStart = volume.minY, yEnd = volume.maxY,
                    z = volume.minZ, depth = volume.depth,
                ),
            )
            Chart3DSideWall.Right -> panels += panel(
                Cuboid3D(
                    x = volume.maxX, width = 0.0,
                    yStart = volume.minY, yEnd = volume.maxY,
                    z = volume.minZ, depth = volume.depth,
                ),
            )
            else -> Unit
        }
        return panels
    }

    /**
     * Which side wall ends up behind the data.
     *
     * A positive yaw brings the scene's right-hand side toward the reader, so
     * the *left* wall is the far one — and the far one is the only one that can
     * be drawn without standing in front of the columns.
     */
    fun resolveSide(camera: Chart3DCamera): Chart3DSideWall = when (side) {
        Chart3DSideWall.Auto ->
            if (camera.rotationY >= 0.0) Chart3DSideWall.Left else Chart3DSideWall.Right
        else -> side
    }

    /**
     * Value grid lines, as world-space segments, at the given `0..1` heights.
     *
     * The heights come from the chart's own value axis ticks, so a grid line on
     * the back wall is at the same value as the label written beside it. A 3D
     * chart that generated its own tick positions would eventually disagree
     * with the axis it is measured against, and the disagreement would be a few
     * pixels — invisible, and wrong.
     */
    fun gridSegments(volume: Bounds3D, heightFractions: List<Double>): List<Pair<Point3D, Point3D>> {
        if (grid == Chart3DFrameGrid.None || !volume.isFinite) return emptyList()
        val lines = ArrayList<Pair<Point3D, Point3D>>(heightFractions.size + CATEGORY_LINE_SLACK)
        if (back && (grid == Chart3DFrameGrid.Back || grid == Chart3DFrameGrid.Both)) {
            heightFractions.forEach { fraction ->
                val y = volume.minY + volume.height * fraction
                lines += Point3D(volume.minX, y, volume.maxZ) to Point3D(volume.maxX, y, volume.maxZ)
            }
        }
        if (floor && (grid == Chart3DFrameGrid.Floor || grid == Chart3DFrameGrid.Both)) {
            // The floor carries the *depth* runs rather than a repeat of the
            // value lines, which would be drawn at one height and say nothing.
            lines += Point3D(volume.minX, volume.minY, volume.minZ) to
                Point3D(volume.minX, volume.minY, volume.maxZ)
            lines += Point3D(volume.maxX, volume.minY, volume.minZ) to
                Point3D(volume.maxX, volume.minY, volume.maxZ)
        }
        return lines
    }

    private fun panel(geometry: Cuboid3D): Chart3DObject = Chart3DObject(
        geometry = geometry,
        selectable = false,
        opacity = opacity,
        role = Chart3DRole.Frame,
    )

    companion object {

        /** Faint enough to sit behind the data and still read as a surface. */
        const val DEFAULT_OPACITY: Float = 0.5f

        /** No frame at all: columns float, and the axis labels carry the reference. */
        val None: Chart3DFrame = Chart3DFrame(
            floor = false,
            back = false,
            side = Chart3DSideWall.None,
            grid = Chart3DFrameGrid.None,
        )

        /** Floor, back wall and the far side wall, with the value grid behind. */
        val Auto: Chart3DFrame = Chart3DFrame()

        /** The same surfaces, more strongly drawn, with the floor's runs as well. */
        val Visible: Chart3DFrame = Chart3DFrame(
            grid = Chart3DFrameGrid.Both,
            opacity = 0.75f,
        )
    }
}

/** Two segments per floor, plus a little room. */
private const val CATEGORY_LINE_SLACK = 2

package io.devkit.chartkit.three

/**
 * A configuration a 3D chart cannot be drawn from.
 *
 * Thrown rather than clamped where the value expresses an intent that cannot be
 * honoured — a negative depth, a `NaN` rotation, a camera at distance zero.
 * ChartKit clamps what has an obvious nearest sensible answer and rejects what
 * does not, on the same reasoning as
 * [io.devkit.chartkit.gauge.GaugeException]: silently drawing something the
 * caller did not ask for is the failure nobody notices.
 */
class Chart3DException(message: String) : IllegalArgumentException(message)

/**
 * Where the reader is standing.
 *
 * ### The angles, in charting terms
 *
 * | ChartKit | Highcharts | What it does |
 * |---|---|---|
 * | [rotationX] | `alpha` | Lifts the reader, revealing the tops of the columns |
 * | [rotationY] | `beta` | Turns the scene, revealing one side of the columns |
 * | [sceneDepth] | `depth` | How far back the geometry extends (on the layer) |
 * | [distance] | `viewDistance` | How strong the perspective is |
 *
 * The names differ because `alpha` and `beta` say which Greek letter and not
 * which way the chart moves, and a reader configuring a chart is thinking about
 * the second. The mapping is given so the reference is followable.
 *
 * ### Both angles default to something, and neither defaults to zero
 *
 * A chart at `0, 0` is a 2D bar chart with its columns hidden behind each
 * other. The defaults tip it just enough for the top and one side to read as
 * surfaces without the height comparison — the thing the chart is actually for
 * — becoming hard.
 *
 * @param rotationX degrees of pitch. Positive lifts the reader above the scene,
 *   so the tops of the columns come into view. Negative looks up at them from
 *   underneath, which is legal geometry and rarely what anyone wants — the
 *   columns then hide the floor they are measured from.
 * @param rotationY degrees of yaw. Positive turns the scene so its right-hand
 *   side comes toward the reader.
 * @param rotationZ degrees of roll, in the plane of the screen. Present for
 *   completeness; a chart that uses it is asking its reader to tilt their head.
 * @param distance how far the camera is from the centre of the scene, in scene
 *   units where the scene is one unit across. Smaller is a wider angle and a
 *   stronger perspective. Ignored by [Chart3DProjection.Orthographic] except as
 *   the origin of camera space.
 * @param target what the camera looks at and rotates around, in world
 *   coordinates, or `null` to use the centre of the scene's own bounds — which
 *   is what keeps a chart of negative values centred on its data rather than
 *   pivoting about a baseline that sits at the top of the plot.
 */
data class Chart3DCamera(
    val rotationX: Double = DEFAULT_ROTATION_X,
    val rotationY: Double = DEFAULT_ROTATION_Y,
    val rotationZ: Double = 0.0,
    val distance: Double = DEFAULT_DISTANCE,
    val target: Point3D? = null,
) {
    init {
        if (!rotationX.isFinite() || !rotationY.isFinite() || !rotationZ.isFinite()) {
            throw Chart3DException(
                "Camera rotations must be real numbers, were " +
                    "($rotationX, $rotationY, $rotationZ). A NaN angle produces a matrix of " +
                    "NaNs and a chart that draws nothing at all.",
            )
        }
        if (!distance.isFinite() || distance <= 0.0) {
            throw Chart3DException(
                "Camera distance must be a positive, finite number, was $distance. The " +
                    "perspective divide is by this value.",
            )
        }
    }

    /**
     * The world-to-camera transform: centre on the target, turn, then step back.
     *
     * Yaw before pitch, which is what a turntable does — the scene spins about
     * its own vertical axis and is then tipped toward the reader. The reverse
     * order tips first and then spins the tipped scene, which rolls the horizon
     * as the reader turns it.
     *
     * @param sceneTarget the centre to use when the camera named none.
     */
    fun viewMatrix(sceneTarget: Point3D, sceneUnit: Double = 1.0): Matrix4 {
        val focus = target ?: sceneTarget
        val centre = Matrix4.translation(-focus.x, -focus.y, -focus.z)
        val yaw = Matrix4.rotationY(rotationY)
        // Negated, and deliberately. ChartKit's world has x right, y up and z
        // *away* from the reader, which is a left-handed frame; the matrices
        // are the ordinary right-handed ones. A pitch that means "the reader is
        // above the scene, looking down at the tops of the columns" is
        // therefore a negative rotation by the right-hand rule. Doing the flip
        // here, once, is what lets [rotationX] be documented in the terms a
        // chart is configured in rather than in the terms a matrix library
        // happens to use.
        val pitch = Matrix4.rotationX(-rotationX)
        val roll = if (rotationZ == 0.0) Matrix4.Identity else Matrix4.rotationZ(rotationZ)
        // Camera space puts the eye at the origin looking along +z, so the
        // scene is pushed forward by the camera distance. That makes a point's
        // z its distance in front of the eye, which is what both the projection
        // and the depth sort read.
        val step = Matrix4.translation(0.0, 0.0, distanceIn(sceneUnit))
        return step * roll * pitch * yaw * centre
    }

    /**
     * The camera distance in world units, given how big the scene is.
     *
     * [distance] is expressed in scene widths rather than in pixels, which is
     * what makes one camera work for a chart at 200dp and the same chart at
     * 800dp: a fixed pixel distance would give the small one a fish-eye and the
     * large one no perspective at all.
     */
    fun distanceIn(sceneUnit: Double): Double =
        distance * (if (sceneUnit.isFinite() && sceneUnit > 0.0) sceneUnit else 1.0)

    /** This camera with its angles held inside [limits]. */
    fun coerceIn(limits: Chart3DCameraLimits): Chart3DCamera = copy(
        rotationX = rotationX.coerceIn(limits.rotationX),
        rotationY = rotationY.coerceIn(limits.rotationY),
        distance = distance.coerceIn(limits.distance),
    )

    companion object {

        /** Enough pitch to read the tops of the columns without foreshortening them. */
        const val DEFAULT_ROTATION_X: Double = 14.0

        /** Enough yaw to show one side, and little enough to keep the front square-on. */
        const val DEFAULT_ROTATION_Y: Double = 18.0

        /** Perspective that reads as depth without bowing the near edge. */
        const val DEFAULT_DISTANCE: Double = 3.2

        /** The default view: a slight turn, a slight tip. */
        val Default: Chart3DCamera = Chart3DCamera()

        /**
         * Square on, with depth still visible.
         *
         * Not `0, 0` — that hides every column behind the one in front of it.
         * A small pitch keeps the arrangement readable while leaving the value
         * comparison as close to a 2D bar chart as a 3D chart gets.
         */
        val Front: Chart3DCamera = Chart3DCamera(rotationX = 6.0, rotationY = 0.0)

        /** Equal angles on both axes: the classic technical-drawing view. */
        val Isometric: Chart3DCamera = Chart3DCamera(rotationX = 30.0, rotationY = 30.0)

        /** A wider turn and a nearer camera, for a slide rather than a dashboard. */
        val Presentation: Chart3DCamera =
            Chart3DCamera(rotationX = 22.0, rotationY = 32.0, distance = 2.4)
    }
}

/**
 * The angles and distances a camera is allowed to reach.
 *
 * Interactive rotation without limits ends up upside down, and a chart read
 * from below is not a view of the data — the columns occlude the axis they are
 * measured against, and the reader has no way back short of a reset. The
 * defaults keep the reader above the floor and stop short of edge-on, where
 * every face collapses to a line.
 *
 * Loosen them deliberately if a use case needs it; they are a data class for
 * exactly that reason.
 */
data class Chart3DCameraLimits(
    val rotationX: ClosedFloatingPointRange<Double> = -5.0..75.0,
    val rotationY: ClosedFloatingPointRange<Double> = -60.0..60.0,
    val distance: ClosedFloatingPointRange<Double> = 1.4..12.0,
) {
    companion object {
        val Default: Chart3DCameraLimits = Chart3DCameraLimits()

        /** Anything the maths can represent. For a caller who means it. */
        val None: Chart3DCameraLimits = Chart3DCameraLimits(
            rotationX = -180.0..180.0,
            rotationY = -180.0..180.0,
            distance = 0.2..1000.0,
        )
    }
}

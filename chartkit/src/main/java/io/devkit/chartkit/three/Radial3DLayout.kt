package io.devkit.chartkit.three

import io.devkit.chartkit.geometry.PolarDirection
import io.devkit.chartkit.geometry.PolarGeometry
import io.devkit.chartkit.geometry.PolarSlice
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * One slice, as world geometry plus the identity it was built from.
 *
 * The value and the share are carried alongside the shape rather than derived
 * from it. That is the whole discipline of this file: nothing downstream ever
 * measures a projected area to find out what a slice is worth. See
 * [Radial3DLayoutEngine].
 */
internal class Radial3DSlice(
    val key: Chart3DKey,
    val sourceIndex: Int,
    val value: Double,
    val fraction: Double,
    val sector: RadialSector3D,
    /** How far out the slice is currently displaced, in world units. */
    val explode: Double,
)

/** Every slice's geometry, and the volume they occupy together. */
internal class Radial3DLayout(
    val slices: List<Radial3DSlice>,
    val outerRadius: Double,
    val innerRadius: Double,
    /** The hidden underside of the disc, and [topY] the surface the reader sees. */
    val baseY: Double,
    val topY: Double,
    /** Angular steps across the whole chart, for diagnostics. */
    val tessellationSegments: Int,
) {
    /** The points the scene fit should be measured against: every slice's own rim. */
    fun fitPoints(): List<Point3D> = slices.flatMap { it.sector.fitPoints() }

    val isEmpty: Boolean get() = slices.isEmpty()
}

/**
 * Turns slices the 2D pie engine already computed into extruded world geometry.
 *
 * ### What this does not do
 *
 * It does not normalise values, total them, reject negatives, compute shares,
 * apply slice gaps, or decide where a slice starts. All of that arrives already
 * done, in [PolarSlice], from
 * [io.devkit.chartkit.geometry.computePolarSlices] — the same call a flat
 * [io.devkit.chartkit.charts.PieChart] makes, with the same arguments. A 3D pie
 * and a 2D pie over the same data therefore have the same slices by
 * construction rather than by two implementations agreeing, and a fix to the
 * value policy reaches both.
 *
 * What is added here is the third dimension and nothing else: an extrusion, an
 * explode displacement, and a tessellation count.
 *
 * ### World units
 *
 * The outer radius is **1.0**. Everything else — depth, explode, the hole — is
 * expressed against it, which is what makes one camera and one depth setting
 * produce the same picture at 120dp and at 900dp. The projector's fit converts
 * to pixels once, at the end.
 */
internal object Radial3DLayoutEngine {

    /** The world outer radius. Every other length here is relative to it. */
    const val UNIT_RADIUS: Double = 1.0

    /**
     * What [Chart3DDepth.Auto] resolves to for a radial chart: a fraction of the
     * outer radius.
     *
     * Thick enough that the rim reads as a solid edge at the default pitch,
     * thin enough that the front slices do not hide the back of the ring. A
     * pie noticeably deeper than this stops being a chart with depth and starts
     * being a cylinder with a pattern on the end.
     */
    const val AUTO_DEPTH_FRACTION: Double = 0.25

    /** No sector is thinner than this, so a shape always has a front and a back. */
    private const val MIN_DEPTH = 1e-3

    @Suppress("LongParameterList")
    fun layout(
        slices: List<PolarSlice>,
        labels: List<String>,
        seriesId: String,
        direction: PolarDirection,
        chartStartAngle: Float,
        innerRadiusRatio: Double,
        depth: Chart3DDepth,
        quality: Chart3DQuality,
        /** The outer radius in screen pixels, for tessellation and absolute depth. */
        radiusPx: Double,
        /** How far each slice is displaced outward, in world units, by source index. */
        explodeOf: (Int) -> Double,
        reveal: Float,
    ): Radial3DLayout {
        val outer = UNIT_RADIUS
        val inner = (innerRadiusRatio.coerceIn(0.0, MAX_INNER_RATIO) * outer)
            .takeIf { it.isFinite() } ?: 0.0
        val thickness = resolveDepth(depth, radiusPx).coerceAtLeast(MIN_DEPTH)
        // Centred on y = 0 so the ring's own middle is the camera's pivot. An
        // extrusion that started at zero would make the chart rise and fall as
        // the reader tipped it, because the point being orbited would sit on
        // the underside rather than in the middle of the solid.
        val baseY = -thickness / 2.0
        val topY = thickness / 2.0
        val progress = reveal.coerceIn(0f, 1f).toDouble()

        var segmentTotal = 0
        val built = ArrayList<Radial3DSlice>(slices.size)
        slices.forEach { slice ->
            if (slice.sweepAngle <= 0f) return@forEach
            // The whole ring sweeps in together, each slice keeping its share —
            // the same reveal the flat slice layer uses, so a 2D and a 3D pie
            // animate identically.
            val fromStart =
                PolarGeometry.angleFrom(chartStartAngle, slice.startAngle, direction).toDouble()
            val animatedStart = chartStartAngle + direction.sign * fromStart * progress
            val animatedSweep = slice.sweepAngle * progress
            if (animatedSweep <= 0.0) return@forEach

            // The tessellation is derived from the slice's *settled* sweep, not
            // from the one being drawn. Deriving it from the animating sweep
            // would change the segment count on almost every frame, and a shape
            // whose topology churns cannot be interpolated — the faces would be
            // rebuilt rather than moved, which is both slower and visibly
            // unstable along the rim.
            val segments = ArcTessellator3D.segmentsFor(
                sweepDegrees = slice.sweepAngle.toDouble(),
                radius = radiusPx,
                quality = quality,
            )
            segmentTotal += segments

            val signedSweep = direction.sign * animatedSweep
            // Stated with a positive sweep and increasing angles, whichever way
            // the chart runs: the shape is the same either way, and normalising
            // here means the winding below is decided once rather than in two
            // mirror-image cases.
            val a0 = animatedStart.toDouble()
            val a1 = a0 + signedSweep
            val sweep = abs(signedSweep)
            val start = min(a0, a1)

            val explode = explodeOf(slice.sourceIndex).takeIf { it.isFinite() } ?: 0.0
            val mid = start + sweep / 2.0
            val radians = Math.toRadians(mid)

            val key = Chart3DKey(
                seriesId = seriesId,
                categoryIndex = slice.sourceIndex,
                category = labels.getOrElse(slice.sourceIndex) { "" },
                stackId = seriesId,
                pointIndex = slice.sourceIndex,
            )
            built += Radial3DSlice(
                key = key,
                sourceIndex = slice.sourceIndex,
                value = slice.value,
                fraction = slice.fraction,
                explode = explode,
                sector = RadialSector3D(
                    innerRadius = inner,
                    outerRadius = outer,
                    startAngle = start,
                    sweepAngle = sweep,
                    baseY = baseY,
                    topY = topY,
                    // The displacement runs along the slice's own mid-angle,
                    // *in the disc's own horizontal plane* — so an exploded
                    // slice slides across the table rather than lifting off it.
                    // Chart angles start at twelve o'clock, which on a
                    // horizontal disc is the far side, so the components are
                    // (sin, cos) onto (x, z) — the same mapping the tessellator
                    // uses.
                    offsetX = explode * sin(radians),
                    offsetZ = explode * cos(radians),
                    segments = segments,
                    key = key,
                ),
            )
        }

        return Radial3DLayout(
            slices = built,
            outerRadius = outer,
            innerRadius = inner,
            baseY = baseY,
            topY = topY,
            tessellationSegments = segmentTotal,
        )
    }

    /** A radial chart's natural unit is its own outer radius, which is [UNIT_RADIUS]. */
    private fun resolveDepth(depth: Chart3DDepth, radiusPx: Double): Double = when (depth) {
        Chart3DDepth.Auto -> UNIT_RADIUS * AUTO_DEPTH_FRACTION
        is Chart3DDepth.Relative -> UNIT_RADIUS * depth.fraction
        // Stated in pixels, converted once: the world is in radii, so a pixel
        // depth only means anything relative to the radius it will be drawn at.
        is Chart3DDepth.Absolute ->
            if (radiusPx.isFinite() && radiusPx > 0.0) depth.pixels / radiusPx else MIN_DEPTH
    }.let { if (it.isFinite() && it > 0.0) it else MIN_DEPTH }

    /** A hole may not swallow the ring. */
    private const val MAX_INNER_RATIO = 0.95
}

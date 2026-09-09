package io.devkit.chartkit.set

import io.devkit.chartkit.geometry.ChartOffset

/**
 * Chooses the layout engine for a requested strategy.
 *
 * The one place Venn and Euler diverge. Everything before it — the model, the
 * analysis, the validation — and everything after it — regions, labels, hit
 * testing, tooltips, animation, accessibility — is shared, which is what keeps
 * "two chart types" from becoming two implementations.
 */
internal fun SetDiagramLayout.engine(): SetDiagramLayoutEngine = when (this) {
    is SetDiagramLayout.Venn -> VennLayoutEngine(sizing)
    is SetDiagramLayout.Euler -> EulerLayoutEngine(sizing)
    is SetDiagramLayout.Custom -> CustomLayoutEngine(this)
}

/** Uses the caller's shapes, and falls back for any set they did not place. */
private class CustomLayoutEngine(
    private val layout: SetDiagramLayout.Custom,
) : SetDiagramLayoutEngine {

    override fun layout(data: SetDiagramData, config: SetLayoutConfig): SetLayout {
        val ids = data.sets.map { it.id }
        val missing = ids.filterNot { layout.shapes.containsKey(it) }
        if (missing.isEmpty()) {
            return SetLayout(
                shapes = ids.associateWith { layout.shapes.getValue(it) },
                order = ids,
                // No solver ran, so there is nothing to report but the truth:
                // the arrangement is exactly what the caller asked for, and how
                // well it matches the cardinalities is their business.
                quality = SetLayoutQuality(regionCoverage = 1.0),
            )
        }
        val fallback = layout.fallback.engine().layout(data, config)
        return SetLayout(
            shapes = ids.associateWith { id ->
                layout.shapes[id] ?: fallback.shape(id) ?: SetShape.Circle(0.0, 0.0, 0.5)
            },
            order = ids,
            quality = fallback.quality,
        )
    }
}

/**
 * Works out which logical region a pointer is in.
 *
 * ```text
 * pointer inside A ✓   inside B ✓   inside C ✗
 *                    ↓
 * membership = {A, B}  →  the region "A and B, and nothing else"
 * ```
 *
 * ### Not "the topmost circle"
 *
 * The obvious implementation — find the circle drawn last that contains the
 * point — is wrong in a way that is easy to miss and impossible to work around
 * as a user. Tapping the middle of a three-circle overlap would select whichever
 * set happened to be drawn last, and the reader could never select the triple
 * intersection at all, because there is nowhere to tap that is *only* in it by
 * drawing order.
 *
 * Membership is a property of the point, not of the paint. So every shape is
 * tested, the full membership is collected, and the region is looked up by it.
 * Draw order has no bearing on what gets selected.
 */
internal object SetHitTester {

    /**
     * The region under [point], or `null` when the pointer is outside every set.
     *
     * Outside is a real answer, not a fallback to the nearest thing: the space
     * around a Venn diagram belongs to no set, and selecting a region a finger
     * is not touching is how a diagram feels broken.
     */
    fun regionAt(
        point: ChartOffset,
        layout: SetLayout,
        data: SetDiagramData,
    ): SetRegion? {
        val membership = layout.membershipAt(point.x, point.y)
        if (membership.isEmpty()) return null
        return data.regionOrEmpty(membership)
    }

    /**
     * Where a tooltip for [region] should be anchored.
     *
     * The region's own deepest interior point when the geometry index knows one,
     * so the tooltip points at the shape the reader tapped rather than at the
     * finger — which for a thin crescent is the difference between a readable
     * anchor and one on the boundary. Falls back to the pointer.
     */
    fun anchorFor(
        region: SetRegion,
        regions: RegionGeometryIndex,
        fallback: ChartOffset,
    ): ChartOffset = regions.geometry(region.memberships)?.anchor ?: fallback
}

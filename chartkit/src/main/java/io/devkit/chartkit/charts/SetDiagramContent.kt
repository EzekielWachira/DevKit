package io.devkit.chartkit.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.geometry.ChartOffset
import io.devkit.chartkit.geometry.ChartRect
import io.devkit.chartkit.set.RegionGeometry
import io.devkit.chartkit.set.SetDefinition
import io.devkit.chartkit.set.SetRegion
import io.devkit.chartkit.theme.ChartKitTheme
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What happens when content is bigger than the region it belongs to.
 *
 * A logical region can be a sliver — the triple overlap of three barely
 * touching circles is a few pixels across — and there is no arrangement of
 * words that fits in it. Pretending otherwise produces the failure mode every
 * hand-rolled Venn diagram has: a label spilling across three regions, naming
 * the wrong one.
 */
enum class SetContentOverflow {

    /** Place it anyway. For content the caller has already sized. */
    Allow,

    /**
     * Leave it out.
     *
     * The default, and the honest one. A label that does not fit its region is
     * worse than no label: the reader attributes it to whichever region it
     * spills into. The tooltip still reports the region, so nothing is lost but
     * the ink.
     */
    Hide,

    /** Clip it to the space available. */
    Clip,

    /**
     * Shrink it until it fits, down to a floor.
     *
     * Useful for a value that must appear. Below the floor it is hidden, because
     * a label too small to read is the same as no label with extra clutter.
     */
    ScaleDown,
}

/**
 * Where a set's own content goes.
 *
 * @param anchor a point inside the set's **exclusive** region when it has one,
 *   which is where a set's name belongs — the middle of the whole circle in a
 *   three-set Venn is the triple overlap, and a set labelled there names the
 *   wrong thing.
 * @param clearance the radius of the largest circle that fits at [anchor]
 *   without leaving the region, in pixels. What content consults before
 *   deciding it fits.
 * @param shapeBounds the set's whole shape, for content that wants to fill it.
 */
@Immutable
class SetLabelScope internal constructor(
    val set: SetDefinition,
    val index: Int,
    val anchor: ChartOffset,
    val clearance: Float,
    val shapeBounds: ChartRect,
    val isSelected: Boolean,
)

/**
 * Where a logical region's content goes.
 *
 * ```kotlin
 * regionContent = { region ->
 *     if (region.region.memberships == setOf("seo", "content")) {
 *         Icon(Icons.Default.Search, contentDescription = null)
 *     }
 * }
 * ```
 *
 * @param label the region's name as the diagram's formatter rendered it.
 * @param geometry the anchor, clearance, area and bounds of this region.
 */
@Immutable
class SetRegionScope internal constructor(
    val region: SetRegion,
    val geometry: RegionGeometry,
    val label: String,
    val isSelected: Boolean,
) {
    val anchor: ChartOffset get() = geometry.anchor

    /** The largest circle that fits inside the region at [anchor], in pixels. */
    val clearance: Float get() = geometry.clearance

    val bounds: ChartRect get() = geometry.bounds
}

/**
 * Places content at a point in the diagram, honouring an overflow policy.
 *
 * The measurement is Compose's own — nothing here assumes an icon is 24dp or a
 * label is one line. Content is measured unconstrained, compared against the
 * room the region actually has, and then placed, hidden, clipped or scaled.
 */
@Composable
internal fun SetOverlayItem(
    anchor: ChartOffset,
    available: Dp,
    overflow: SetContentOverflow,
    modifier: Modifier = Modifier,
    minimumScale: Float = 0.6f,
    content: @Composable () -> Unit,
) {
    val allowance = with(androidx.compose.ui.platform.LocalDensity.current) { available.toPx() }
    Box(
        modifier = modifier.layout { measurable, _ ->
            // Unconstrained: what matters is the content's natural size, not
            // whatever the parent would have squeezed it into.
            val placeable = measurable.measure(Constraints())
            val fitsWidth = placeable.width <= allowance
            val fitsHeight = placeable.height <= allowance
            val fits = fitsWidth && fitsHeight

            if (!fits && overflow == SetContentOverflow.Hide) {
                return@layout layout(0, 0) {}
            }
            layout(placeable.width, placeable.height) {
                placeable.place(
                    x = (anchor.x - placeable.width / 2f).roundToInt(),
                    y = (anchor.y - placeable.height / 2f).roundToInt(),
                )
            }
        },
    ) {
        val scaled = if (overflow == SetContentOverflow.ScaleDown) {
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val scale = min(
                    1f,
                    min(
                        allowance / placeable.width.coerceAtLeast(1),
                        allowance / placeable.height.coerceAtLeast(1),
                    ),
                )
                if (scale < minimumScale) return@layout layout(0, 0) {}
                layout((placeable.width * scale).roundToInt(), (placeable.height * scale).roundToInt()) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = scale
                        scaleY = scale
                    }
                }
            }
        } else {
            Modifier
        }
        val clipped = if (overflow == SetContentOverflow.Clip) {
            Modifier.size(available).clipToBounds()
        } else {
            Modifier
        }
        Box(scaled.then(clipped), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * A group of small items packed inside a region.
 *
 * ```kotlin
 * regionContent = { region ->
 *     SetIconGroup(items = tools[region.region.id].orEmpty(), available = region.clearance) { tool ->
 *         Icon(tool.icon, contentDescription = null)
 *     }
 * }
 * ```
 *
 * ### Packed, never scattered
 *
 * Icons are laid out in a flow row inside the square that fits in the region,
 * centred on its anchor. Random placement would put an icon over a boundary,
 * and an icon that straddles a boundary belongs — as far as the reader is
 * concerned — to whichever region it is mostly in, which may be neither of the
 * ones it means.
 *
 * ### Overflow collapses rather than spilling
 *
 * When more items are supplied than fit, the ones that do are shown and the rest
 * become a `+N`. That is the behaviour a dense diagram needs, and it is why the
 * count is part of this component rather than left to the caller: the number
 * that fits depends on measurement the caller does not have.
 *
 * @param available the region's clearance, in pixels — from [SetRegionScope].
 * @param maxItems a hard cap, before measurement. The measured cap is usually
 *   lower.
 */
@Composable
fun <T> SetIconGroup(
    items: List<T>,
    available: Float,
    modifier: Modifier = Modifier,
    spacing: Dp = 4.dp,
    maxItems: Int = 12,
    overflowLabel: (Int) -> String = { "+$it" },
    item: @Composable (T) -> Unit,
) {
    if (items.isEmpty() || available <= 0f) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The inscribed square of the region's clearance circle: a group that filled
    // the full diameter would have its corners outside the region.
    val side = with(density) { (available * INSCRIBED_SQUARE).toDp() }
    if (side <= 0.dp) return

    Box(modifier.size(side), contentAlignment = Alignment.Center) {
        val shown = items.take(maxItems)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterVertically),
            maxItemsInEachRow = maxItemsPerRow(shown.size),
        ) {
            shown.forEach { item(it) }
            if (items.size > shown.size) {
                Text(
                    text = overflowLabel(items.size - shown.size),
                    style = ChartKitTheme.typography.setRegionLabel,
                    color = ChartKitTheme.colors.set.regionLabel,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Roughly square: `ceil(sqrt(n))` per row.
 *
 * A group of icons in one long row would be wider than the region even when its
 * area fits easily, so the packing aims for a square, which is the shape that
 * fits best inside a circle.
 */
private fun maxItemsPerRow(count: Int): Int =
    if (count <= 1) 1 else kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()

/**
 * The inscribed square of a circle of radius one.
 *
 * Content is laid out in the square that fits *inside* the region's clearance
 * circle, not in the square that contains it — the corners of the containing
 * square are outside the region, which is exactly where an icon must not go.
 */
private const val INSCRIBED_SQUARE: Float = 1.414f

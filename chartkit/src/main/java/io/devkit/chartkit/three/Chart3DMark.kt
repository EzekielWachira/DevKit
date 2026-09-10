package io.devkit.chartkit.three

import kotlin.math.sqrt

/**
 * How one observation is drawn as a mark in 3D.
 *
 * ### Why a point is not a tiny box
 *
 * The obvious implementation of a 3D scatter point — a small [Cuboid3D], or a
 * tessellated sphere — is wrong at the sizes a scatter actually uses. A marker
 * is six to twelve pixels across; a sphere resolved finely enough to look round
 * at that size costs on the order of a hundred triangles, and ten thousand of
 * them is a million faces to project, cull and sort for a picture in which no
 * individual facet is ever visible. Meanwhile a box seen at eight pixels is
 * three flat quads with an obvious silhouette that reads as a box and not as a
 * point.
 *
 * So a marker is a **screen-space glyph anchored at a world position**. The
 * position goes through exactly the same camera, projection, depth and lighting
 * as every face in the scene — that is what makes a marker correctly hidden
 * behind a frame wall, correctly ordered against another marker, and correctly
 * lit — and only the last step, turning a projected centre into pixels, differs
 * from a face.
 */
sealed interface Marker3D {

    /**
     * A flat disc that always faces the reader.
     *
     * The cheapest mark there is: one projected point, one radius, one filled
     * circle. Its centre comes from the real X/Y/Z projection, so a cloud of
     * billboards still reads as a volume — the depth cue is the *arrangement*
     * of the points and their size falloff, not the shading of any one of them.
     */
    data object BillboardCircle : Marker3D

    /**
     * A disc shaded as a sphere, from the scene's own light.
     *
     * Still one projected point and one radius; what is added is a radial
     * gradient whose bright spot sits where a sphere's surface normal would
     * point at [Chart3DLighting.direction]. Because the light is fixed to the
     * camera, every sphere in a scene shares one highlight direction, which is
     * computed once per frame rather than once per point.
     *
     * The result is not a rendered sphere and does not claim to be: there is no
     * self-shadowing and no specular term. It is the shading a reader needs to
     * see a point as a ball rather than a hole, at the cost of one gradient.
     */
    data object Sphere : Marker3D

    /**
     * A real box in the scene, built from [Cuboid3D].
     *
     * The one marker that is *not* a glyph: it is scene geometry, with six
     * faces that are projected, culled, lit and depth sorted individually. That
     * makes it the only marker whose orientation tells the reader something —
     * which faces of a cube are visible says where the camera is — and the only
     * one whose size is stated in **scene** units rather than screen pixels, so
     * a cube further from the camera is drawn smaller under perspective and the
     * scene's own fit scales it with everything else.
     *
     * Costs six faces per point. Right for tens of points, wrong for thousands.
     */
    data object Cube : Marker3D
}

/**
 * One point mark in a scene: a world position and a screen size.
 *
 * The two spaces in one object are deliberate and are what the type is *for*.
 * [position] is analytical — it came from three scales over three variables and
 * is transformed by the camera like anything else. [radius] is presentational —
 * a marker is a glyph a reader has to be able to see and hit, and a size stated
 * in scene units would make the same chart's points invisible on one dataset
 * and overlapping on another.
 *
 * @param radius the mark's radius in screen pixels, at the depth of the scene's
 *   centre. A perspective projection scales it with depth from there; an
 *   orthographic one does not. See [Chart3DProjector.sizeScaleAt].
 * @param paletteIndex the theme palette slot, resolved by the renderer. Not a
 *   colour: this package holds no Compose types.
 * @param colorOverride an explicit ARGB value, or `null`.
 * @param key what data this mark stands for. `null` marks are not selectable,
 *   on the same rule faces follow.
 */
class Chart3DMark(
    val position: Point3D,
    val radius: Double,
    val marker: Marker3D = Marker3D.Sphere,
    val paletteIndex: Int = 0,
    val colorOverride: Int? = null,
    val opacity: Float = 1f,
    val key: Chart3DKey? = null,
) {
    /** True when the mark cannot be placed or has no size worth drawing. */
    val isDegenerate: Boolean
        get() = !position.isFinite || !radius.isFinite() || radius <= 0.0
}

/**
 * A mark after the camera and the projection have been applied.
 *
 * @param depth the camera-space depth of the mark's centre — the **same**
 *   quantity a face reports, which is what lets marks and faces be sorted into
 *   one order and hit tested against one notion of "in front".
 * @param radius the final screen radius, perspective scaling already applied.
 * @param brightness the shading factor for a surface facing the reader, from
 *   the scene's lighting. A billboard uses it flat; a sphere uses it as the
 *   ceiling of its gradient.
 * @param markIndex the mark's position in [Chart3DScene.marks].
 */
data class ProjectedMark(
    val center: Projected2D,
    val radius: Double,
    override val depth: Double,
    val brightness: Double,
    val marker: Marker3D,
    val markIndex: Int,
    override val key: Chart3DKey?,
) : Projected3D {

    /** True when ([x], [y]) is within [slop] pixels of the mark's disc. */
    fun contains(x: Double, y: Double, slop: Double = 0.0): Boolean {
        val reach = radius + slop.coerceAtLeast(0.0)
        val dx = x - center.x
        val dy = y - center.y
        return dx * dx + dy * dy <= reach * reach
    }
}

/**
 * The shading of a sphere marker, derived from the scene's own light.
 *
 * Three numbers and a direction, computed once per frame because the light is
 * fixed to the camera and every sphere therefore shares them. A renderer turns
 * this into a radial gradient; nothing here knows what a gradient is.
 *
 * @param highlight the `0..1` brightness at the lit point.
 * @param terminator the `0..1` brightness at the limb turned away from the
 *   light. Never zero, because [Chart3DLighting.ambient] is not.
 * @param offsetX and [offsetY] the unit direction, **in screen coordinates**,
 *   from the mark's centre toward its highlight. `y` already carries the
 *   canvas' downward flip, so a light from above gives a negative `offsetY`.
 */
data class Marker3DShading(
    val highlight: Double,
    val terminator: Double,
    val offsetX: Double,
    val offsetY: Double,
) {
    companion object {

        /**
         * How far from the centre the bright spot sits, as a fraction of the
         * radius.
         *
         * Not on the limb. A highlight placed at the edge reads as a crescent
         * moon rather than as a ball, because the eye takes the sharp boundary
         * for the object's own edge; a little over half way is where a real
         * sphere lit from off-axis puts it.
         */
        const val HIGHLIGHT_OFFSET: Double = 0.42

        /** The shading a sphere gets under [lighting]. */
        fun of(lighting: Chart3DLighting): Marker3DShading {
            val direction = lighting.direction.normalized()
            // The surface point whose normal looks straight into the light is
            // the brightest one there is, and the one facing along the light's
            // travel is in shadow — so the two ends of the gradient are the
            // lighting model's own answers for those two normals rather than
            // numbers chosen to look right.
            val highlight = lighting.brightnessOf(-direction)
            val terminator = lighting.brightnessOf(direction)
            // Screen x follows camera x; screen y is flipped. The highlight is
            // toward where the light comes *from*, which is minus its travel.
            val screenX = -direction.x
            val screenY = direction.y
            val length = sqrt(screenX * screenX + screenY * screenY)
            return if (length < Vector3D.EPSILON) {
                // A light straight down the camera axis has no screen
                // direction at all, and a sphere lit from directly behind the
                // reader is uniformly bright. Centring the highlight is what
                // that actually looks like.
                Marker3DShading(highlight, terminator, 0.0, 0.0)
            } else {
                Marker3DShading(highlight, terminator, screenX / length, screenY / length)
            }
        }
    }
}

package io.devkit.chartkit.three

/**
 * What a piece of 3D geometry stands for in the data.
 *
 * ### Why identity is not optional
 *
 * A cuboid's position is a consequence of the data, so it is useless as a name
 * for it: a series that gains a category moves every box, and matching by
 * position would animate February into March. Selection, tooltips, animation
 * matching and legend toggling all need to say *which* segment, across frames
 * in which the geometry has changed, which is precisely what an identity is
 * for. The same argument [io.devkit.chartkit.model.ChartSeries] makes about
 * series ids, one level down.
 *
 * @param seriesId the series the segment belongs to.
 * @param categoryIndex which band, in the chart's merged category order.
 * @param category the band's label, so a tooltip need not resolve it again.
 * @param stackId the stack this segment is part of. Equal to [seriesId] when
 *   the chart is not stacked, which keeps the key total without a null.
 * @param pointIndex the index in the caller's own list, for handing their
 *   object back.
 */
data class Chart3DKey(
    val seriesId: String,
    val categoryIndex: Int,
    val category: String,
    val stackId: String,
    val pointIndex: Int,
)

/**
 * One thing in a 3D scene: geometry, a colour slot and an identity.
 *
 * The geometry is a [Chart3DGeometry] and not a concrete shape, which is what
 * lets a [Cuboid3D] column and a [RadialSector3D] pie slice sit in the same
 * scene, be projected by the same projector, culled by the same test, sorted
 * into one depth order and hit tested by the same code. Everything downstream —
 * [SceneProjector], [Chart3DHitTest], the depth sort, the lighting — works on
 * faces and never asks what produced them.
 *
 * @param paletteIndex the slot in the theme palette. Not a colour: this package
 *   holds no Compose types, and the renderer resolves the slot against the
 *   theme so a scene is the same in light and dark mode.
 * @param colorOverride an explicit ARGB value the caller stated, or `null`.
 * @param selectable whether pointer hit testing considers this object. False
 *   for frame walls, which are scenery — a reader tapping the back wall of a
 *   chart has not selected the back wall.
 * @param opacity `0..1`, applied on top of whatever colour is resolved. Frame
 *   panels are drawn faintly; data is not.
 */
class Chart3DObject(
    val geometry: Chart3DGeometry,
    val paletteIndex: Int = 0,
    val colorOverride: Int? = null,
    val selectable: Boolean = true,
    val opacity: Float = 1f,
    val role: Chart3DRole = Chart3DRole.Data,
) {
    val key: Chart3DKey? get() = geometry.key
}

/** What an object is for, which decides how it is coloured and whether it is selectable. */
enum class Chart3DRole {

    /** A data mark: a column segment, a pie sector, a scatter point. */
    Data,

    /** A wall or floor of the plot frame. */
    Frame,
}

/**
 * How a scene is lit.
 *
 * ### Restrained on purpose
 *
 * One directional light, an ambient term and a diffuse term. That is enough to
 * tell a column's three visible faces apart, which is the only thing shading
 * has to achieve here — a reader is estimating a height against an axis, not
 * admiring a material. Specular highlights, shadows and multiple lights would
 * each add a brightness gradient that carries no data and competes with the one
 * that does.
 *
 * @param ambient the floor brightness, so a face turned away from the light is
 *   still readable rather than black.
 * @param diffuse how much the light adds at full incidence. [ambient] plus
 *   [diffuse] is the brightest a face gets, and the defaults sum to just under
 *   one so the lit face is not blown out to the object's raw colour.
 * @param direction the direction light *travels*. The default comes from above,
 *   in front and to the left, which is where a reader's mental model of a light
 *   source sits. At the default camera it leaves the three visible faces of a
 *   column at roughly 0.97, 0.88 and 0.62 — separated enough to read as a solid
 *   without the darkest of them losing its hue. The top is the brightest, which
 *   is useful: the top is the face whose height the reader is judging.
 */
data class Chart3DLighting(
    val ambient: Double = 0.62,
    val diffuse: Double = 0.38,
    val direction: Vector3D = Vector3D(0.35, -0.85, 0.4),
) {
    init {
        require(ambient.isFinite() && ambient in 0.0..1.0) {
            "Ambient light must be in [0, 1], was $ambient"
        }
        require(diffuse.isFinite() && diffuse in 0.0..1.0) {
            "Diffuse light must be in [0, 1], was $diffuse"
        }
    }

    private val unitDirection: Vector3D = direction.normalized()

    /**
     * The `0..1` brightness of a face pointing [normal], in **camera space**.
     *
     * Camera space and not world space: a light fixed to the world would swing
     * across the columns as the reader rotated the chart, and the face that is
     * brightest would keep changing. Fixed to the camera, rotating the scene
     * turns the geometry under a steady light, which is what a reader expects
     * from a physical object on a desk.
     */
    fun brightnessOf(normal: Vector3D): Double {
        if (normal.isZero || !normal.isFinite) return ambient
        val incidence = (normal dot -unitDirection).coerceAtLeast(0.0)
        return (ambient + diffuse * incidence).coerceIn(0.0, 1.0)
    }

    companion object {
        /** The default: soft, top-left, and never fully dark. */
        val Default: Chart3DLighting = Chart3DLighting()

        /** No shading at all — every face at full colour. For flat presentation. */
        val Flat: Chart3DLighting = Chart3DLighting(ambient = 1.0, diffuse = 0.0)
    }
}

/**
 * A complete 3D picture, before any camera has been applied.
 *
 * Holds world geometry and nothing about the screen. That separation is what
 * lets a camera rotation reuse the whole scene — see [SceneProjector], which
 * takes a scene and a camera and produces screen faces without touching this.
 */
class Chart3DScene(
    val objects: List<Chart3DObject>,
    val lighting: Chart3DLighting = Chart3DLighting.Default,
    /**
     * Point marks: observations drawn as glyphs rather than as surfaces.
     *
     * A second list rather than a second kind of [Chart3DObject], because the
     * two are genuinely different downstream — a face is culled by its normal
     * and clipped as a polygon, a mark has neither — and pretending otherwise
     * would put an `if` inside the projection loop that runs once per face of
     * every column chart in the library for the benefit of a chart type that
     * has no faces. They rejoin at the only place it matters:
     * [Chart3DProjectionResult.items], where they are sorted together.
     */
    val marks: List<Chart3DMark> = emptyList(),
) {
    /**
     * The box the scene occupies, or `null` when it holds nothing.
     *
     * Marks contribute their **positions** and not their radii, because a
     * radius is in screen pixels and the bounds are in world units. The
     * consequence is that a fit measures the cloud rather than the drawn discs,
     * so an outermost marker is half clipped by the plot edge unless the
     * caller reserves room for it — which is exactly what
     * [Chart3DReserve] is, and what the scatter layer passes.
     */
    val bounds: Bounds3D? by lazy(LazyThreadSafetyMode.NONE) {
        val fromObjects = objects.map { it.geometry.bounds }.reduceOrNull { a, b -> a.union(b) }
        val fromMarks = Bounds3D.of(marks.filter { !it.isDegenerate }.map { it.position })
        when {
            fromObjects == null -> fromMarks
            fromMarks == null -> fromObjects
            else -> fromObjects.union(fromMarks)
        }
    }

    val isEmpty: Boolean get() = objects.isEmpty() && marks.isEmpty()
}

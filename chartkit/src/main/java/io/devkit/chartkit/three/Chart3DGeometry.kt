package io.devkit.chartkit.three

/**
 * A shape a 3D scene can draw: some faces, a box they live in, and an identity.
 *
 * ### Why this exists, and when it started to
 *
 * The scene originally held [Cuboid3D] directly, because a column was the only
 * thing in it. That was honest at the time and wrong the moment a second shape
 * arrived: an extruded pie sector is not a box, has a variable number of faces,
 * and is built from an arc rather than from a footprint — and the alternatives
 * to this interface were a second projector, a second culler, a second depth
 * sort and a second hit test, all of which would have drifted from these ones.
 *
 * Everything downstream of a scene — [Chart3DProjector], [Chart3DHitTest], the
 * depth order, the lighting — reads [faces] and asks nothing else. A shape is
 * therefore fully described by the polygons it is made of and the box it
 * occupies, which is exactly as much as a painter's-algorithm renderer can act
 * on.
 *
 * Implementations are expected to compute [faces] **once**, at construction,
 * and hold them: a projection reads them once per frame, and a hit test reads
 * the projection rather than the geometry.
 */
interface Chart3DGeometry {

    /**
     * What data this shape stands for, or `null` for scene furniture.
     *
     * A frame wall has none, and that absence is what
     * [Chart3DHitTest.faceAt] uses to refuse to select the scenery.
     */
    val key: Chart3DKey?

    /** The axis-aligned box the shape occupies, for framing and for fitting. */
    val bounds: Bounds3D

    /** Every face, each wound so its normal points out of the solid. */
    val faces: List<Face3D>

    /** True when the shape encloses no volume worth drawing. */
    val isDegenerate: Boolean
}

package io.devkit.chartkit.three

/**
 * Which data a pointer landed on, resolved geometrically.
 *
 * ### Not colour picking
 *
 * The usual shortcut — render every object in a unique flat colour to an
 * offscreen buffer and read the pixel under the finger — is rejected here. It
 * needs a second render pass every frame, it needs a readback that stalls the
 * GPU pipeline, it cannot answer "what is *behind* this", and it is wrong
 * wherever anti-aliasing blends two ids into a third that belongs to neither.
 * The geometry is already projected for drawing; testing against it costs a
 * point-in-polygon per candidate and gives an exact answer.
 */
object Chart3DHitTest {

    /**
     * The front-most data face under ([x], [y]), or `null`.
     *
     * [faces] must be in the projector's own back-to-front draw order, which is
     * why the search runs backwards: the last face drawn is the one on top, and
     * that is by definition the one the reader was pointing at. Resolving in
     * any other order would let a column hidden behind another win a tap on the
     * visible one — the failure that makes a 3D chart feel broken rather than
     * merely wrong.
     *
     * Faces without a [Chart3DKey] are scenery — frame walls and floors — and
     * are skipped rather than returned, so a tap on the back wall reports
     * nothing instead of selecting the wall.
     */
    fun faceAt(faces: List<ProjectedFace>, x: Double, y: Double): ProjectedFace? {
        for (index in faces.indices.reversed()) {
            val face = faces[index]
            if (face.key == null) continue
            if (contains(face.points, x, y)) return face
        }
        return null
    }

    /**
     * True when ([x], [y]) is inside the convex polygon [points].
     *
     * A sign test on the cross product of each edge with the vector to the
     * point: inside means the point is on the same side of every edge. Valid
     * because a projected cuboid face is always convex — a planar convex
     * quadrilateral stays convex under any projection that does not put it
     * behind the eye, and the projector has already dropped those.
     *
     * The comparison admits zero on either side, so a point exactly on a shared
     * edge belongs to both faces rather than to neither. Two adjacent faces of
     * one column resolve to the same data, so the ambiguity has no consequence
     * for selection — and a one-pixel dead line between them would.
     */
    fun contains(points: List<Projected2D>, x: Double, y: Double): Boolean {
        if (points.size < 3) return false
        var positive = false
        var negative = false
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            val cross = (next.x - current.x) * (y - current.y) -
                (next.y - current.y) * (x - current.x)
            if (cross > EDGE_EPSILON) positive = true
            if (cross < -EDGE_EPSILON) negative = true
            if (positive && negative) return false
        }
        return true
    }

    /** Half a pixel of slack, so a tap on a shared edge still hits something. */
    private const val EDGE_EPSILON = 1e-6
}

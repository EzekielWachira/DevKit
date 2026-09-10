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
     * The front-most data mark under ([x], [y]), or `null`.
     *
     * [marks] must be in the projector's back-to-front order, and the search
     * runs backwards for exactly the reason [faceAt] does: the last one drawn
     * is the one on top. That is what makes §67 hold — of two observations
     * overlapping on screen, the one the reader can see wins the tap — without
     * a second notion of depth, because the order was produced by the same
     * camera-space `z` the renderer drew from.
     *
     * @param slop extra pixels of reach around each disc. A marker is six to
     *   twelve pixels across and a fingertip is forty; requiring a pixel-exact
     *   tap on one would make a touch scatter chart unusable, and the whole
     *   cost of being generous is that a tap in the gap between two adjacent
     *   points resolves to the nearer-to-the-reader of them rather than to
     *   nothing.
     */
    fun markAt(
        marks: List<ProjectedMark>,
        x: Double,
        y: Double,
        slop: Double = 0.0,
    ): ProjectedMark? {
        for (index in marks.indices.reversed()) {
            val mark = marks[index]
            if (mark.key == null) continue
            if (mark.contains(x, y, slop)) return mark
        }
        return null
    }

    /**
     * The front-most data item under ([x], [y]) across faces **and** marks.
     *
     * What a chart holding both should ask. Searching one list and then the
     * other would answer with whichever kind was searched first rather than
     * with whichever is actually in front, and the mistake would only show
     * where a marker and a surface overlap — which on a scatter chart with a
     * frame is most of the plot.
     */
    fun itemAt(
        items: List<Projected3D>,
        x: Double,
        y: Double,
        slop: Double = 0.0,
    ): Projected3D? {
        for (index in items.indices.reversed()) {
            val item = items[index]
            if (item.key == null) continue
            val hit = when (item) {
                is ProjectedFace -> contains(item.points, x, y)
                is ProjectedMark -> item.contains(x, y, slop)
            }
            if (hit) return item
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

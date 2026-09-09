package io.devkit.chartkit.three

/**
 * Which side of a box a face is.
 *
 * Named rather than indexed because every interesting question about a face is
 * asked by name — is the top visible, is the front the one the label sits on,
 * which walls does the frame draw — and an index would make each of those a
 * comment rather than a compiler-checked value.
 */
enum class FaceSide {
    Front,
    Back,
    Left,
    Right,
    Top,
    Bottom,
}

/**
 * One flat, convex polygon in world space, with the direction it faces.
 *
 * ### Geometry, not a drawing
 *
 * A face is deliberately *not* a Compose `Path`. Everything that decides
 * whether and where it appears — the camera transform, the culling test, the
 * depth comparison, the lighting — is arithmetic on these vertices and this
 * normal, and none of it needs a renderer. Building a `Path` first would put
 * the whole pipeline behind an Android type and make it untestable on the JVM
 * for no gain: the path is created once, at the end, for the faces that
 * survived.
 *
 * @param vertices in an order whose right-handed cross product points
 *   *outwards*. [Cuboid3D] guarantees it; a caller building faces by hand must
 *   too, or the face will be culled from the side it should be seen from.
 * @param normal the outward direction, precomputed. Stored rather than derived
 *   on demand because culling asks for it once per face per frame and a
 *   degenerate face's normal is worth resolving once — see [Vector3D.normalized].
 * @param side which side of its object this is, for frames and for debugging.
 */
data class Face3D(
    val vertices: List<Point3D>,
    val normal: Vector3D,
    val side: FaceSide,
) {
    /** The average of the vertices: what depth sorting and lighting are measured at. */
    val centroid: Point3D
        get() {
            if (vertices.isEmpty()) return Point3D.Origin
            var x = 0.0
            var y = 0.0
            var z = 0.0
            vertices.forEach { x += it.x; y += it.y; z += it.z }
            val count = vertices.size.toDouble()
            return Point3D(x / count, y / count, z / count)
        }

    companion object {

        /**
         * A face through [vertices], with the normal derived from the first three.
         *
         * Returns a face whose normal is [Vector3D.Zero] when the vertices are
         * collinear or coincident — a column of zero height has four such faces
         * — rather than one carrying `NaN`. The projector skips those; see
         * [SceneProjector].
         */
        fun of(vertices: List<Point3D>, side: FaceSide): Face3D {
            val normal = if (vertices.size < 3) {
                Vector3D.Zero
            } else {
                ((vertices[1] - vertices[0]) cross (vertices[2] - vertices[0])).normalized()
            }
            return Face3D(vertices, normal, side)
        }
    }
}

/**
 * A face after the camera and the projection have been applied.
 *
 * Screen coordinates in `x`/`y`, in the plot's own pixel space with y growing
 * downward like every other ChartKit surface — and a [depth] that is still in
 * *camera* space, because that is the only quantity that orders faces
 * correctly. Sorting on projected `y`, or on a distance from the screen centre,
 * puts a tall column in front of a short one standing closer to the reader.
 *
 * @param depth the camera-space distance of the face's centroid. Larger is
 *   further away, so a back-to-front pass draws in descending order.
 * @param brightness the `0..1` shading factor from the scene's lighting, applied
 *   to the object's own colour by the renderer. Kept as a number rather than a
 *   colour so this whole file stays free of Compose.
 * @param objectIndex the position of the owning object in the scene, and
 *   [faceIndex] its position within that object. Together they are the
 *   tie-breaker that makes the sort total, and therefore stable frame to frame.
 */
data class ProjectedFace(
    val points: List<Projected2D>,
    val depth: Double,
    val brightness: Double,
    val side: FaceSide,
    val objectIndex: Int,
    val faceIndex: Int,
    val key: Chart3DKey?,
) {
    /** The signed area of the projected polygon, doubled. Negative when wound the other way. */
    fun doubleSignedArea(): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            sum += current.x * next.y - next.x * current.y
        }
        return sum
    }
}

/** A projected vertex, in plot pixels. */
data class Projected2D(val x: Double, val y: Double) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()
}

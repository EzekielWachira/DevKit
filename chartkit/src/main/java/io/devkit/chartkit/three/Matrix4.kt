package io.devkit.chartkit.three

import kotlin.math.cos
import kotlin.math.sin

/**
 * A 4×4 homogeneous transformation, row-major.
 *
 * ```
 * | m00 m01 m02 m03 |   | x |
 * | m10 m11 m12 m13 | · | y |
 * | m20 m21 m22 m23 |   | z |
 * | m30 m31 m32 m33 |   | 1 |
 * ```
 *
 * ### Why a matrix and not three angles
 *
 * The camera applies a yaw, then a pitch, then a translation, and the scene
 * applies a centring before all three. Written as successive trigonometric
 * expressions that is four places to get a sign wrong and no way to test the
 * composition; written as matrices it is one multiplication whose result can be
 * asserted against a known point. It also means [transformVector] can exist —
 * the *same* orientation applied to a normal with the translation dropped —
 * which is what stops a rotated scene culling the wrong faces.
 *
 * ### Allocation
 *
 * Backed by one 16-element `DoubleArray`, and the transform methods return
 * small immutable results rather than mutating shared state. The camera matrix
 * is composed **once per frame** and then applied to every vertex, so the cost
 * that would matter — a matrix multiply per point — does not arise. See
 * [io.devkit.chartkit.three.SceneProjector], which does exactly that.
 */
class Matrix4 private constructor(private val m: DoubleArray) {

    init {
        require(m.size == SIZE) { "A 4x4 matrix needs $SIZE values, got ${m.size}" }
    }

    /** The value at [row], [column], both `0..3`. */
    operator fun get(row: Int, column: Int): Double = m[row * 4 + column]

    /** `this` then [other]: the transform [other] applied to the result of this one. */
    fun then(other: Matrix4): Matrix4 = other * this

    /** Ordinary matrix product. `a * b` applies `b` first, then `a`. */
    operator fun times(other: Matrix4): Matrix4 {
        val result = DoubleArray(SIZE)
        for (row in 0 until 4) {
            val base = row * 4
            for (column in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) {
                    sum += m[base + k] * other.m[k * 4 + column]
                }
                result[base + column] = sum
            }
        }
        return Matrix4(result)
    }

    /**
     * [point] transformed, translation included.
     *
     * The `w` row is divided out, so a matrix carrying a projection still
     * produces a usable point. ChartKit's own camera matrices are affine and
     * leave `w` at one, but the division costs a comparison and removes a whole
     * class of "why is the far column enormous" question.
     */
    fun transformPoint(point: Point3D): Point3D {
        val x = m[0] * point.x + m[1] * point.y + m[2] * point.z + m[3]
        val y = m[4] * point.x + m[5] * point.y + m[6] * point.z + m[7]
        val z = m[8] * point.x + m[9] * point.y + m[10] * point.z + m[11]
        val w = m[12] * point.x + m[13] * point.y + m[14] * point.z + m[15]
        if (w == 1.0 || w == 0.0 || !w.isFinite()) return Point3D(x, y, z)
        return Point3D(x / w, y / w, z / w)
    }

    /**
     * [vector] transformed as a *direction*: rotated and scaled, never moved.
     *
     * A face normal is a direction. Passing one through [transformPoint] adds
     * the camera's translation to it, which for a scene pushed 900 units back
     * turns every normal into something pointing almost straight away from the
     * viewer — so every face passes the culling test and the chart draws its
     * own interior.
     */
    fun transformVector(vector: Vector3D): Vector3D = Vector3D(
        x = m[0] * vector.x + m[1] * vector.y + m[2] * vector.z,
        y = m[4] * vector.x + m[5] * vector.y + m[6] * vector.z,
        z = m[8] * vector.x + m[9] * vector.y + m[10] * vector.z,
    )

    override fun toString(): String = (0 until 4).joinToString(separator = "\n") { row ->
        (0 until 4).joinToString(prefix = "| ", postfix = " |") { column ->
            "%8.4f".format(this[row, column])
        }
    }

    companion object {

        private const val SIZE = 16

        val Identity: Matrix4 = Matrix4(
            doubleArrayOf(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )

        fun translation(dx: Double, dy: Double, dz: Double): Matrix4 = Matrix4(
            doubleArrayOf(
                1.0, 0.0, 0.0, dx,
                0.0, 1.0, 0.0, dy,
                0.0, 0.0, 1.0, dz,
                0.0, 0.0, 0.0, 1.0,
            ),
        )

        fun scale(sx: Double, sy: Double, sz: Double): Matrix4 = Matrix4(
            doubleArrayOf(
                sx, 0.0, 0.0, 0.0,
                0.0, sy, 0.0, 0.0,
                0.0, 0.0, sz, 0.0,
                0.0, 0.0, 0.0, 1.0,
            ),
        )

        /**
         * A rotation of [degrees] about the x axis, by the right-hand rule.
         *
         * The plain mathematical definition. What a *chart's* pitch means in
         * terms of it — and why the camera negates it — is set out on
         * [Chart3DCamera.rotationX].
         */
        fun rotationX(degrees: Double): Matrix4 {
            val radians = Math.toRadians(degrees)
            val c = cos(radians)
            val s = sin(radians)
            return Matrix4(
                doubleArrayOf(
                    1.0, 0.0, 0.0, 0.0,
                    0.0, c, -s, 0.0,
                    0.0, s, c, 0.0,
                    0.0, 0.0, 0.0, 1.0,
                ),
            )
        }

        /** A rotation of [degrees] about the y axis. Positive brings the right side forward. */
        fun rotationY(degrees: Double): Matrix4 {
            val radians = Math.toRadians(degrees)
            val c = cos(radians)
            val s = sin(radians)
            return Matrix4(
                doubleArrayOf(
                    c, 0.0, s, 0.0,
                    0.0, 1.0, 0.0, 0.0,
                    -s, 0.0, c, 0.0,
                    0.0, 0.0, 0.0, 1.0,
                ),
            )
        }

        /** A rotation of [degrees] about the z axis: a roll, in the screen plane. */
        fun rotationZ(degrees: Double): Matrix4 {
            val radians = Math.toRadians(degrees)
            val c = cos(radians)
            val s = sin(radians)
            return Matrix4(
                doubleArrayOf(
                    c, -s, 0.0, 0.0,
                    s, c, 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    0.0, 0.0, 0.0, 1.0,
                ),
            )
        }

        /** A matrix from sixteen row-major values, for tests and for interop. */
        fun ofRows(values: DoubleArray): Matrix4 = Matrix4(values.copyOf())
    }
}

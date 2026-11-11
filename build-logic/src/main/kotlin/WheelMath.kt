package eu.nordtal.season.build

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** JSON written once and spliced in as it is, so a picture that many turns share is serialised once. */
internal class Raw(
    val text: String,
)

/** 4 x 4 matrices, row-major, for the moves that the wheel's item definitions carry, and the compact JSON they are written in. */
internal object WheelMath {
    fun identity(): DoubleArray = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }

    fun translate(
        x: Double,
        y: Double,
        z: Double,
    ): DoubleArray = identity().also {
        it[3] = x
        it[7] = y
        it[11] = z
    }

    fun scale(
        x: Double,
        y: Double,
        z: Double,
    ): DoubleArray = DoubleArray(16).also {
        it[0] = x
        it[5] = y
        it[10] = z
        it[15] = 1.0
    }

    fun rotateX(a: Double): DoubleArray = identity().also {
        it[5] = cos(a)
        it[6] = -sin(a)
        it[9] = sin(a)
        it[10] = cos(a)
    }

    fun rotateY(a: Double): DoubleArray = identity().also {
        it[0] = cos(a)
        it[2] = sin(a)
        it[8] = -sin(a)
        it[10] = cos(a)
    }

    fun rotateZ(a: Double): DoubleArray = identity().also {
        it[0] = cos(a)
        it[1] = -sin(a)
        it[4] = sin(a)
        it[5] = cos(a)
    }

    fun times(vararg ms: DoubleArray): DoubleArray = ms.reduce { a, b -> multiply(a, b) }

    private fun multiply(
        a: DoubleArray,
        b: DoubleArray,
    ): DoubleArray {
        val out = DoubleArray(16)
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) s += a[r * 4 + k] * b[k * 4 + c]
                out[r * 4 + c] = s
            }
        }
        return out
    }

    /** The inverse by Gauss-Jordan elimination; the display transforms are invertible, their scales never zero. */
    fun inverse(m: DoubleArray): DoubleArray {
        val a = m.copyOf()
        val inv = identity()
        for (col in 0 until 4) {
            var pivot = col
            for (r in col + 1 until 4) if (Math.abs(a[r * 4 + col]) > Math.abs(a[pivot * 4 + col])) pivot = r
            if (pivot != col) {
                for (c in 0 until 4) {
                    a[col * 4 + c] = a[pivot * 4 + c].also { a[pivot * 4 + c] = a[col * 4 + c] }
                    inv[col * 4 + c] = inv[pivot * 4 + c].also { inv[pivot * 4 + c] = inv[col * 4 + c] }
                }
            }
            val p = a[col * 4 + col]
            require(p != 0.0) { "a display transform without an inverse" }
            for (c in 0 until 4) {
                a[col * 4 + c] /= p
                inv[col * 4 + c] /= p
            }
            for (r in 0 until 4) {
                if (r == col) continue
                val f = a[r * 4 + col]
                if (f == 0.0) continue
                for (c in 0 until 4) {
                    a[r * 4 + c] -= f * a[col * 4 + c]
                    inv[r * 4 + c] -= f * inv[col * 4 + c]
                }
            }
        }
        return inv
    }

    /** A quaternion, as `[x, y, z, w]` or `{angle, axis}`, as a rotation matrix. */
    fun quaternion(q: Any?): DoubleArray {
        val (x, y, z, w) =
            if (q is Map<*, *>) {
                val axis = (q["axis"] as List<*>).map { (it as Number).toDouble() }
                val n = sqrt(axis.sumOf { it * it })
                val h = (q["angle"] as Number).toDouble() / 2
                listOf(axis[0] / n * sin(h), axis[1] / n * sin(h), axis[2] / n * sin(h), cos(h))
            } else {
                (q as List<*>).map { (it as Number).toDouble() }
            }
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), 0.0,
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), 0.0,
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
    }

    /** A node's `transformation`: 16 numbers row-major, or translation, left rotation, scale and right rotation. */
    fun transformation(t: Any?): DoubleArray {
        if (t is List<*>) return DoubleArray(16) { (t[it] as Number).toDouble() }
        val m = t as Map<*, *>
        val tr = (m["translation"] as List<*>).map { (it as Number).toDouble() }
        val sc = (m["scale"] as List<*>).map { (it as Number).toDouble() }
        return times(translate(tr[0], tr[1], tr[2]), quaternion(m["left_rotation"]), scale(sc[0], sc[1], sc[2]), quaternion(m["right_rotation"]))
    }

    fun isIdentity(m: DoubleArray): Boolean = m.indices.all { Math.abs(m[it] - if (it % 5 == 0) 1.0 else 0.0) < 1e-6 }

    /** The matrix as the JSON list a definition carries, every number to 4 places. */
    fun list(m: DoubleArray): List<Any> = m.map { number(it) }

    fun number(v: Double): Any {
        val d = BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        if (d.signum() == 0) return 0
        return if (d.scale() <= 0) d.toBigInteger().toInt() else d
    }

    /** Compact JSON of maps, lists, strings, numbers and booleans, keys in insertion order. */
    fun json(
        value: Any?,
        out: StringBuilder = StringBuilder(),
    ): StringBuilder {
        when (value) {
            null -> out.append("null")
            is String -> string(value, out)
            is Raw -> out.append(value.text)
            is BigDecimal -> out.append(value.toPlainString())
            is Double -> out.append(number(value).let { if (it is BigDecimal) it.toPlainString() else it.toString() })
            is Number, is Boolean -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    string(k.toString(), out)
                    out.append(':')
                    json(v, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    json(v, out)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("no JSON for ${value::class}")
        }
        return out
    }

    private fun string(
        s: String,
        out: StringBuilder,
    ) {
        out.append('"')
        for (ch in s) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch < ' ' -> out.append(String.format("\\u%04x", ch.code))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }
}

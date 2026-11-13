package eu.nordtal.season.build

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * The wheel's turn, tick by tick. It starts at [START_SPEED] and slows down evenly, the same in every variant for the
 * first [SHARED] ticks, so the pack writes that part once. Each variant then eases into its own even slowing onto the
 * pin at the far edge of the winning field, losing a little speed at each of the last pegs, rests [HOLD] ticks against
 * it with the pointer bent, and springs back to the field's middle in [SETTLE] ticks, quick at first and easing in.
 * Angles are degrees clockwise from where the turn starts, so the turn of variant e rests at [distance], 3 whole turns
 * and e places on.
 */
object WheelTurn {
    const val TURNS = 3
    const val FRAMES_PER_TURN = 96
    const val PLACES = 12

    /** The ticks every variant shares, and the speed it starts with in degrees a tick: three frames, below a field's half. */
    const val SHARED = 70
    const val START_SPEED = 3 * 360.0 / FRAMES_PER_TURN

    /** How far past the field's middle the pin stops the wheel, the ticks it rests there, and the ticks it rocks back. */
    const val PIN = 15.0
    const val HOLD = 4
    const val SETTLE = 36

    /** The ticks between two pops when the dialog opens, and how far a popping prize is lifted on its tick and the next. */
    const val POP_STEP = 2
    val POP_HOP = listOf(2, 1)

    /** The ticks of the prizes' pop-in when the dialog opens. */
    val OPEN_TICKS = (PLACES - 1) * POP_STEP + POP_HOP.size

    private const val DIP_FROM = 120.0
    private const val DIP_B = 0.8
    private const val DIP_AT = -2.0
    private const val DIP_W = 3.5
    private const val RAMP = 30.0
    private const val DT = 0.02
    private const val STEP = 360.0 / FRAMES_PER_TURN

    /** The middle variant slows down at the shared rate all the way, so every variant's rate stays close to it. */
    private val DECELERATION = START_SPEED * START_SPEED / (2 * (distance(PLACES / 2) + PIN))

    /** What one tick shows: the disc at [angle], snapped to a frame, and the pointer. */
    data class Tick(
        val angle: Double,
        val pointer: Pointer,
    )

    /** The pointer's drawn textures: straight, and bent by a pin under it. */
    enum class Pointer(
        val texture: String,
    ) {
        REST("pointer"),
        TICK1("pointer_tick1"),
        TICK2("pointer_tick2"),
    }

    fun distance(e: Int): Double = 360.0 * TURNS + 30.0 * e

    /** The ticks of variant [e] until it rests, from tick 0; tick [length] would be the rest. */
    fun ticks(e: Int): List<Tick> =
        (0 until length(e)).map {
            val angle = snapped(angle(e, it.toDouble()))
            Tick(angle, pointerAt(angle))
        }

    /** The ticks variant [e] moves, from the start to the end of the rock back. */
    fun length(e: Int): Int = ceil(motion(e).end + HOLD + SETTLE).toInt()

    /** The ticks of the longest variant, which every turn's cooldown lasts so the variants can share their start. */
    val COOLDOWN: Int by lazy { (0 until PLACES).maxOf { length(it) } }

    /** The ticks at which a peg passes the pointer, and the one at which the far pin first bends it. */
    fun clicks(e: Int): List<Int> {
        val pin = distance(e) + PIN
        val a = DoubleArray(length(e) + 1) { minOf(angle(e, it.toDouble()), pin - 1e-6) }
        val passes = (1..length(e)).filter { n -> floor((a[n] - 15.0) / 30.0) != floor((a[n - 1] - 15.0) / 30.0) }
        val bend = (0 until length(e)).first { snapped(angle(e, it.toDouble())) >= pin - STEP - 1e-9 }
        return (passes + bend).distinct().sorted()
    }

    /** The disc frame of 16 that shows [angle]: the disc's art repeats every 60 degrees. */
    fun discFrame(angle: Double): Int = Math.floorMod(floor(angle / STEP + 0.5).toInt(), 16)

    private fun snapped(angle: Double): Double = floor(angle / STEP + 0.5) * STEP

    /** The pixel artist's rule: the frame k mod 8 is 3 for the first bend, 4 or 5 for the second. */
    private fun pointerAt(angle: Double): Pointer =
        when (discFrame(angle) % 8) {
            3 -> Pointer.TICK1
            4, 5 -> Pointer.TICK2
            else -> Pointer.REST
        }

    /** The angle variant [e] has turned at tick [t]. */
    fun angle(
        e: Int,
        t: Double,
    ): Double {
        val m = motion(e)
        val pin = distance(e) + PIN
        return when {
            t <= SHARED -> START_SPEED * t - DECELERATION * t * t / 2
            t <= m.end -> interpolate(m.times, m.angles, t)
            t <= m.end + HOLD -> pin
            t <= m.end + HOLD + SETTLE -> distance(e) + PIN * (1 - sin(Math.PI / 2 * (t - m.end - HOLD) / SETTLE))
            else -> distance(e)
        }
    }

    /** Variant [e]'s own part: times and angles from the end of the shared start onto the pin, and when it gets there. */
    private class Motion(
        val times: DoubleArray,
        val angles: DoubleArray,
    ) {
        val end = times[times.size - 1]
    }

    private val motions = HashMap<Int, Motion>()

    @Synchronized
    private fun motion(e: Int): Motion = motions.getOrPut(e) { compute(e) }

    /**
     * From the end of the shared start, the wheel slows down onto the pin. Its deceleration moves from the shared one to
     * the variant's own over [RAMP] ticks, so the slowing never jumps. Each peg over the last 120 degrees takes the disc to
     * a little over half its speed, which lengthens the approach rather than hurrying its start.
     */
    private fun compute(e: Int): Motion {
        val start = SHARED.toDouble()
        val from = START_SPEED * start - DECELERATION * start * start / 2
        val speed = START_SPEED - DECELERATION * start
        val pin = distance(e) + PIN
        val rest = pin - from
        var lo = DECELERATION / 4
        var hi = DECELERATION * 4
        repeat(60) {
            val mid = (lo + hi) / 2
            if (stop(speed, mid).second.last() > rest) lo = mid else hi = mid
        }
        val (ts, xs) = stop(speed, (lo + hi) / 2)
        val scale = rest / xs.last()
        val a = DoubleArray(ts.size) { from + xs[it] * scale }
        val warped = DoubleArray(ts.size)
        for (k in 1 until ts.size) {
            warped[k] = warped[k - 1] + (dip(a[k], pin) + dip(a[k - 1], pin)) / 2 * (ts[k] - ts[k - 1])
        }
        return Motion(DoubleArray(ts.size) { start + warped[it] }, a)
    }

    /** The times and distances of a wheel at [speed] that slows down until it stops, ramping to [deceleration]. */
    private fun stop(
        speed: Double,
        deceleration: Double,
    ): Pair<DoubleArray, DoubleArray> {
        val ts = ArrayList<Double>()
        val xs = ArrayList<Double>()
        var t = 0.0
        var x = 0.0
        var v = speed
        ts.add(t)
        xs.add(x)
        while (v > 0) {
            val d = DECELERATION + (deceleration - DECELERATION) * minOf((t + DT / 2) / RAMP, 1.0)
            if (v <= d * DT) {
                t += v / d
                x += v * v / (2 * d)
                v = 0.0
            } else {
                t += DT
                x += (v - d * DT / 2) * DT
                v -= d * DT
            }
            ts.add(t)
            xs.add(x)
        }
        return ts.toDoubleArray() to xs.toDoubleArray()
    }

    private fun dip(
        alpha: Double,
        pin: Double,
    ): Double {
        val delta = mod(alpha, 30.0) - 15.0
        val g = exp(-((delta - DIP_AT) / DIP_W).pow(2))
        val r = ((alpha - (pin - DIP_FROM)) / 10.0).coerceIn(0.0, 1.0)
        val w = r * r * (3 - 2 * r)
        return 1 + w * DIP_B * g
    }

    private fun mod(
        a: Double,
        m: Double,
    ): Double = ((a % m) + m) % m

    private fun interpolate(
        xs: DoubleArray,
        ys: DoubleArray,
        x: Double,
    ): Double {
        if (x <= xs[0]) return ys[0]
        if (x >= xs[xs.size - 1]) return ys[ys.size - 1]
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val t = (x - xs[lo]) / (xs[hi] - xs[lo])
        return ys[lo] + t * (ys[hi] - ys[lo])
    }

    /** The speed in degrees a tick that variant [e] has at tick [t], for the tests. */
    fun speed(
        e: Int,
        t: Double,
    ): Double = angle(e, t + 0.5) - angle(e, t - 0.5)
}

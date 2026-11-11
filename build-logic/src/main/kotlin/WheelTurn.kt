package eu.nordtal.season.build

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/**
 * The wheel's turn, tick by tick: a fast start under the blurred disc, a long run-out that loses a little speed at each
 * of the last pegs, one peg past the winner and back. Angles are degrees clockwise from where the turn starts, so the
 * turn of variant [e] ends at [distance], 3 whole turns and [e] places on.
 */
object WheelTurn {
    /** The ticks of the turn after the swap, its last [SETTLE] the roll back onto the winner. */
    const val TICKS = 150
    const val SETTLE = 10
    const val TURNS = 3
    const val FRAMES_PER_TURN = 96
    const val PLACES = 12

    /** The ticks between two pops of the swap, the pause after the last, and the swap's whole length. */
    const val SWAP_STEP = 3
    const val SWAP_PAUSE = 15
    const val SWAP_TICKS = (PLACES - 1) * SWAP_STEP + 1 + SWAP_PAUSE

    /** How far a popping prize is lifted, in whole GUI pixels, on its tick and the next. */
    val POP_HOP = listOf(2, 1)

    /** Faster than this many degrees a tick, the disc shows the blurred textures. */
    const val BLUR_ABOVE = 13.0
    const val BLUR_TEXTURES = 3

    /** Faster than this, one tick moves the streaks more than half their period, so each tick shows the next one. */
    private const val BLUR_STEP_ABOVE = 45.0

    private const val EASE = 4.0
    private const val TAIL = 0.5
    private const val OVERSHOOT = 21.0
    private const val END_SPEED = 2.0
    private const val DIP_FROM = 120.0
    private const val DIP_B = 0.8
    private const val DIP_AT = -2.0
    private const val DIP_W = 3.5
    private const val SAMPLES = 200_001
    private const val STEP = 360.0 / FRAMES_PER_TURN

    /** What one tick shows: the blurred disc with streak texture [blur], or the sharp disc at [angle]. */
    data class Tick(
        val blur: Int?,
        val angle: Double,
        val pointer: Pointer,
    )

    /** The pointer's drawn textures; the backward ones are mirrored about the pin for the roll back. */
    enum class Pointer(
        val texture: String,
        val mirrored: Boolean,
    ) {
        REST("pointer", false),
        TICK1("pointer_tick1", false),
        TICK2("pointer_tick2", false),
        TICK1_BACK("pointer_tick1", true),
        TICK2_BACK("pointer_tick2", true),
    }

    fun distance(e: Int): Double = 360.0 * TURNS + 30.0 * e

    /** The ticks 0 until [TICKS] of variant [e]; tick [TICKS] would be the rest. */
    fun ticks(e: Int): List<Tick> {
        val (times, angles) = curve(e)
        val start = angles[0]
        val true_ = DoubleArray(TICKS + 1) { interpolate(times, angles, it.toDouble()) - start }
        val peak = true_.indices.maxBy { true_[it] }
        val out = ArrayList<Tick>(TICKS)
        var lastBlur: Int? = null
        for (i in 0 until TICKS) {
            val speed = abs(true_[i + 1] - true_[i])
            if (speed > BLUR_ABOVE) {
                val byAngle = (floor(mod(true_[i], 90.0) / 30.0).toInt()) % BLUR_TEXTURES
                val blur =
                    if (lastBlur == null) {
                        byAngle
                    } else {
                        val ahead = Math.floorMod(byAngle - lastBlur, BLUR_TEXTURES)
                        // The streaks only ever move on: a step that reads as backwards, or stands while the disc races, shows the next.
                        if (ahead == BLUR_TEXTURES - 1 || (ahead == 0 && speed > BLUR_STEP_ABOVE)) (lastBlur + 1) % BLUR_TEXTURES else byAngle
                    }
                lastBlur = blur
                val flutter = listOf(Pointer.TICK2, Pointer.TICK1, Pointer.TICK2)[blur]
                out += Tick(blur, true_[i], flutter)
            } else {
                val angle = snapped(true_[i])
                out += Tick(null, angle, pointerAt(angle, i > peak))
            }
        }
        return out
    }

    /** The ticks of the turn at which a peg passes the pointer, forwards or back, each once. */
    fun clicks(e: Int): List<Int> {
        val (times, angles) = curve(e)
        val a = DoubleArray(TICKS + 1) { interpolate(times, angles, it.toDouble()) - angles[0] }
        return (1..TICKS).filter { n -> floor((a[n] - 15.0) / 30.0) != floor((a[n - 1] - 15.0) / 30.0) }
    }

    /** The disc frame of 16 that shows [angle]: the disc's art repeats every 60 degrees. */
    fun discFrame(angle: Double): Int = Math.floorMod(floor(angle / STEP + 0.5).toInt(), 16)

    private fun snapped(angle: Double): Double = floor(angle / STEP + 0.5) * STEP

    /** The pixel artist's rule: the frame k mod 8 is 3 for the first bend, 4 or 5 for the second. */
    private fun pointerAt(
        angle: Double,
        back: Boolean,
    ): Pointer =
        when (discFrame(angle) % 8) {
            3 -> if (back) Pointer.TICK1_BACK else Pointer.TICK1
            4, 5 -> if (back) Pointer.TICK2_BACK else Pointer.TICK2
            else -> Pointer.REST
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

    private val curves = HashMap<Int, Pair<DoubleArray, DoubleArray>>()

    /**
     * Times in ticks and angles of variant [e]: one speed curve v = v0 (1-u)^4 + c (1-u)^0.5 to rest 21 degrees past
     * the winner, passing it at 2 degrees a tick, slowed at each of the last four pegs, then a cosine back.
     */
    @Synchronized
    fun curve(e: Int): Pair<DoubleArray, DoubleArray> = curves.getOrPut(e) { compute(e) }

    private fun compute(e: Int): Pair<DoubleArray, DoubleArray> {
        val d = distance(e)
        val q = EASE
        val r = TAIL
        val t1 = (TICKS - SETTLE).toDouble()
        val whole = d + OVERSHOOT

        fun v0(c: Double) = (q + 1) * (whole / t1 - c / (r + 1))

        fun x(
            c: Double,
            u: Double,
        ) = t1 * (v0(c) * (1 - (1 - u).pow(q + 1)) / (q + 1) + c * (1 - (1 - u).pow(r + 1)) / (r + 1))

        fun v(
            c: Double,
            u: Double,
        ) = v0(c) * (1 - u).pow(q) + c * (1 - u).pow(r)
        var lo = 0.0
        var hi = (r + 1) * whole / t1
        var c = 0.0
        repeat(40) {
            c = (lo + hi) / 2
            var lu = 0.0
            var hu = 1.0
            var ua = 0.0
            repeat(40) {
                ua = (lu + hu) / 2
                if (x(c, ua) < d) lu = ua else hu = ua
            }
            if (v(c, ua) < END_SPEED) lo = c else hi = c
        }
        val u = DoubleArray(SAMPLES) { it.toDouble() / (SAMPLES - 1) }
        val xs = DoubleArray(SAMPLES) { x(c, u[it]) }
        var i = xs.indexOfFirst { it >= d }
        if (i < 0) i = SAMPLES - 1
        val ts = DoubleArray(SAMPLES) { u[it] * t1 }
        val a = DoubleArray(SAMPLES) { ANGLE_END - d + xs[it] }
        // Each peg over the last 120 degrees takes the disc to a little over half its speed; the approach keeps its length.
        val tn = DoubleArray(i + 1)
        for (k in 1..i) {
            tn[k] = tn[k - 1] + (dip(a[k]) + dip(a[k - 1])) / 2 * (ts[k] - ts[k - 1])
        }
        val scale = ts[i] / tn[i]
        for (k in 0..i) ts[k] = tn[k] * scale
        val settle = 2000
        val times = DoubleArray(SAMPLES + settle)
        val angles = DoubleArray(SAMPLES + settle)
        System.arraycopy(ts, 0, times, 0, SAMPLES)
        System.arraycopy(a, 0, angles, 0, SAMPLES)
        for (k in 1..settle) {
            val t = SETTLE.toDouble() * k / settle
            times[SAMPLES + k - 1] = t1 + t
            angles[SAMPLES + k - 1] = ANGLE_END + OVERSHOOT * (1 + cos(Math.PI * t / SETTLE)) / 2
        }
        return times to angles
    }

    private const val ANGLE_END = 720.0

    private fun dip(alpha: Double): Double {
        val delta = mod(alpha, 30.0) - 15.0
        val g = exp(-((delta - DIP_AT) / DIP_W).pow(2))
        val r = ((alpha - (ANGLE_END - DIP_FROM)) / 10.0).coerceIn(0.0, 1.0)
        val w = r * r * (3 - 2 * r)
        return 1 + w * DIP_B * g
    }
}

package eu.nordtal.season.packbuilder.wheel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The wheel's turn, tick by tick, in degrees clockwise from where the turn starts.
 * Every variant shares the first {@link #SHARED} ticks: from two frames a tick it speeds up to {@link #TOP_SPEED},
 * holds it and slows against a drag that grows with its speed. Then each eases onto its pin, rests and springs back.
 */
public final class WheelTurn {

    public static final int TURNS = 4;
    public static final int FRAMES_PER_TURN = 96;
    public static final int PLACES = 12;

    /** The speed the turn starts with in degrees a tick: two frames. */
    public static final double START_SPEED = 2 * 360.0 / FRAMES_PER_TURN;

    /**
     * The speed the turn speeds up to and holds, in degrees a tick.
     * The client shows one frame a tick, so a step past half the 30 degrees between two fields would make the sharp
     * wheel seem to stand or run back.
     */
    public static final double TOP_SPEED = 14.0;

    /** The ticks the wheel speeds up for. */
    public static final int ACCELERATION = 30;

    /** The ticks the wheel holds its top speed for. */
    public static final int CRUISE = 30;

    /** The ticks every variant shares: the acceleration, the top speed and the start of the slowing. */
    public static final int SHARED = ACCELERATION + CRUISE + 10;

    /** How far past the field's middle the far pin stops the wheel: three frames, against the bent pointer's side. */
    public static final double PIN = 3 * 360.0 / FRAMES_PER_TURN;

    public static final int HOLD = 4;
    public static final int SETTLE = 36;

    /** The ticks between two pops when the dialog opens. */
    public static final int POP_STEP = 2;

    /** How far a popping prize is lifted on its tick and the next. */
    public static final List<Integer> POP_HOP = List.of(2, 1);

    /** The ticks of the prizes' pop-in when the dialog opens. */
    public static final int OPEN_TICKS = (PLACES - 1) * POP_STEP + POP_HOP.size();

    private static final double DIP_FROM = 120.0;
    private static final double DIP_B = 0.8;
    private static final double DIP_AT = -2.0;
    private static final double DIP_W = 3.5;
    private static final double RAMP = 30.0;
    private static final double DT = 0.02;
    private static final double STEP = 360.0 / FRAMES_PER_TURN;

    /** The speed at which the drag doubles the slowing: below it the wheel slows almost evenly, above it fast. */
    private static final double DRAG_SPEED = 15.0;

    /** How far the acceleration takes the wheel. */
    private static final double SPED_UP =
            START_SPEED * ACCELERATION + (TOP_SPEED - START_SPEED) * ACCELERATION * 2.0 / 3.0;

    /** How far the wheel has turned when it starts to slow. */
    private static final double ACCELERATED = SPED_UP + TOP_SPEED * CRUISE;

    /** The slowing at speed 0, in degrees a tick squared, at which the middle variant slows all the way. */
    private static final double DECELERATION = DRAG_SPEED
            * DRAG_SPEED
            * -Math.log(Math.cos(Math.atan(TOP_SPEED / DRAG_SPEED)))
            / (distance(PLACES / 2) + PIN - ACCELERATED);

    private static final Map<Integer, Motion> MOTIONS = new ConcurrentHashMap<>();

    /** The ticks of the longest variant, which every turn's cooldown lasts so the variants can share their start. */
    public static final int COOLDOWN = cooldown();

    private WheelTurn() {}

    /** What one tick shows: the disc and its prizes at their angle, and the pointer. */
    public record Tick(double angle, Pointer pointer) {}

    /** The pointer's drawn textures: straight, and bent by a pin under it. */
    public enum Pointer {
        REST("pointer"),
        TICK1("pointer_tick1"),
        TICK2("pointer_tick2");

        private final String texture;

        Pointer(final String texture) {
            this.texture = texture;
        }

        public String texture() {
            return texture;
        }
    }

    public static double distance(final int e) {
        return 360.0 * TURNS + 30.0 * e;
    }

    /** The ticks of variant {@code e} until it rests, from tick 0; tick {@link #length} would be the rest. */
    public static List<Tick> ticks(final int e) {
        final int length = length(e);
        final List<Tick> out = new ArrayList<>(length);
        for (int t = 0; t < length; t++) {
            final double angle = snapped(angle(e, t));
            out.add(new Tick(angle, pointerAt(angle)));
        }
        return out;
    }

    /** The ticks variant {@code e} moves, from the start to the end of the rock back. */
    public static int length(final int e) {
        return (int) Math.ceil(motion(e).end() + HOLD + SETTLE);
    }

    private static int cooldown() {
        int max = 0;
        for (int e = 0; e < PLACES; e++) {
            max = Math.max(max, length(e));
        }
        return max;
    }

    /** The ticks at which a peg passes the pointer, and the one at which the far pin first bends it. */
    public static List<Integer> clicks(final int e) {
        final double pin = distance(e) + PIN;
        final int length = length(e);
        final double[] a = new double[length + 1];
        for (int t = 0; t <= length; t++) {
            a[t] = Math.min(angle(e, t), pin - 1e-6);
        }
        final TreeSet<Integer> out = new TreeSet<>();
        for (int n = 1; n <= length; n++) {
            if (Math.floor((a[n] - 15.0) / 30.0) != Math.floor((a[n - 1] - 15.0) / 30.0)) {
                out.add(n);
            }
        }
        for (int t = 0; t < length; t++) {
            if (snapped(angle(e, t)) >= pin - 1e-9) {
                out.add(t);
                return List.copyOf(out);
            }
        }
        throw new IllegalStateException("variant " + e + " never reaches its pin");
    }

    /** The disc frame of 16 that shows {@code angle}: the disc's art repeats every 60 degrees. */
    public static int discFrame(final double angle) {
        return Math.floorMod((int) Math.floor(angle / STEP + 0.5), 16);
    }

    private static double snapped(final double angle) {
        return Math.floor(angle / STEP + 0.5) * STEP;
    }

    /** The pixel artist's rule: the frame k mod 8 is 3 for the first bend, 4 or 5 for the second. */
    private static Pointer pointerAt(final double angle) {
        return switch (discFrame(angle) % 8) {
            case 3 -> Pointer.TICK1;
            case 4, 5 -> Pointer.TICK2;
            default -> Pointer.REST;
        };
    }

    /** The angle variant {@code e} has turned at tick {@code t}. */
    public static double angle(final int e, final double t) {
        final Motion m = motion(e);
        final double pin = distance(e) + PIN;
        if (t <= SHARED) {
            return shared(t);
        }
        if (t <= m.end()) {
            return interpolate(m.times(), m.angles(), t);
        }
        if (t <= m.end() + HOLD) {
            return pin;
        }
        if (t <= m.end() + HOLD + SETTLE) {
            return distance(e) + PIN * (1 - Math.sin(Math.PI / 2 * (t - m.end() - HOLD) / SETTLE));
        }
        return distance(e);
    }

    /** The shared start's angle at tick {@code t}: the acceleration, the top speed, then the shared slowing. */
    private static double shared(final double t) {
        if (t <= ACCELERATION) {
            final double u = t / ACCELERATION;
            return START_SPEED * t + (TOP_SPEED - START_SPEED) * ACCELERATION * (u * u - u * u * u / 3);
        }
        if (t <= ACCELERATION + CRUISE) {
            return SPED_UP + TOP_SPEED * (t - ACCELERATION);
        }
        final double k = DECELERATION / DRAG_SPEED;
        final double theta = Math.atan(TOP_SPEED / DRAG_SPEED);
        final double slowing = t - ACCELERATION - CRUISE;
        return ACCELERATED + DRAG_SPEED / k * Math.log(Math.cos(theta - k * slowing) / Math.cos(theta));
    }

    /** The shared start's speed at tick {@code t} in degrees a tick. */
    private static double sharedSpeed(final double t) {
        if (t <= ACCELERATION) {
            final double u = t / ACCELERATION;
            return START_SPEED + (TOP_SPEED - START_SPEED) * (2 * u - u * u);
        }
        if (t <= ACCELERATION + CRUISE) {
            return TOP_SPEED;
        }
        final double k = DECELERATION / DRAG_SPEED;
        return DRAG_SPEED * Math.tan(Math.atan(TOP_SPEED / DRAG_SPEED) - k * (t - ACCELERATION - CRUISE));
    }

    /** The speed in degrees a tick that variant {@code e} has at tick {@code t}. */
    public static double speed(final int e, final double t) {
        return angle(e, t + 0.5) - angle(e, t - 0.5);
    }

    /** Variant e's own part: times and angles from the end of the shared start onto the pin. */
    private record Motion(double[] times, double[] angles) {

        double end() {
            return times[times.length - 1];
        }
    }

    private static Motion motion(final int e) {
        return MOTIONS.computeIfAbsent(e, WheelTurn::compute);
    }

    /**
     * From the end of the shared start, the wheel slows down onto the pin.
     * Its slowing moves from the shared one to the variant's own multiple of it over {@link #RAMP} ticks. Each peg
     * over the last 120 degrees takes the disc to a little over half its speed, which lengthens the approach.
     */
    private static Motion compute(final int e) {
        final double start = SHARED;
        final double from = shared(start);
        final double speed = sharedSpeed(start);
        final double pin = distance(e) + PIN;
        final double rest = pin - from;
        double lo = 0.25;
        double hi = 4.0;
        for (int i = 0; i < 60; i++) {
            final double mid = (lo + hi) / 2;
            final double[] xs = stop(speed, mid)[1];
            if (xs[xs.length - 1] > rest) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        final double[][] stopped = stop(speed, (lo + hi) / 2);
        final double[] ts = stopped[0];
        final double[] xs = stopped[1];
        final double scale = rest / xs[xs.length - 1];
        final double[] a = new double[ts.length];
        for (int k = 0; k < ts.length; k++) {
            a[k] = from + xs[k] * scale;
        }
        final double[] warped = new double[ts.length];
        for (int k = 1; k < ts.length; k++) {
            warped[k] = warped[k - 1] + (dip(a[k], pin) + dip(a[k - 1], pin)) / 2 * (ts[k] - ts[k - 1]);
        }
        final double[] times = new double[ts.length];
        for (int k = 0; k < ts.length; k++) {
            times[k] = start + warped[k];
        }
        return new Motion(times, a);
    }

    /** The times and distances of a wheel at {@code speed} that slows, ramping to {@code scale} times the shared. */
    private static double[][] stop(final double speed, final double scale) {
        final List<Double> ts = new ArrayList<>();
        final List<Double> xs = new ArrayList<>();
        double t = 0.0;
        double x = 0.0;
        double v = speed;
        ts.add(t);
        xs.add(x);
        while (v > 0) {
            final double ramped = 1 + (scale - 1) * Math.min((t + DT / 2) / RAMP, 1.0);
            final double d = ramped * DECELERATION * (1 + v * v / (DRAG_SPEED * DRAG_SPEED));
            if (v <= d * DT) {
                t += v / d;
                x += v * v / (2 * d);
                v = 0.0;
            } else {
                t += DT;
                x += (v - d * DT / 2) * DT;
                v -= d * DT;
            }
            ts.add(t);
            xs.add(x);
        }
        return new double[][] {array(ts), array(xs)};
    }

    private static double[] array(final List<Double> list) {
        final double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static double dip(final double alpha, final double pin) {
        final double delta = mod(alpha, 30.0) - 15.0;
        final double g = Math.exp(-Math.pow((delta - DIP_AT) / DIP_W, 2));
        final double r = Math.clamp((alpha - (pin - DIP_FROM)) / 10.0, 0.0, 1.0);
        final double w = r * r * (3 - 2 * r);
        return 1 + w * DIP_B * g;
    }

    private static double mod(final double a, final double m) {
        return ((a % m) + m) % m;
    }

    private static double interpolate(final double[] xs, final double[] ys, final double x) {
        if (x <= xs[0]) {
            return ys[0];
        }
        if (x >= xs[xs.length - 1]) {
            return ys[ys.length - 1];
        }
        int lo = 0;
        int hi = xs.length - 1;
        while (hi - lo > 1) {
            final int mid = (lo + hi) >>> 1;
            if (xs[mid] <= x) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        final double t = (x - xs[lo]) / (xs[hi] - xs[lo]);
        return ys[lo] + t * (ys[hi] - ys[lo]);
    }
}

package eu.nordtal.season.smp.wall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoublePredicate;
import java.util.function.ToIntFunction;

/**
 * The part of the wall a player sees: the panels near them, as a band on the ground or up to the build limit.
 * The field's panels span the world's whole height, and the pack's item shader cuts the wall out of them with
 * these same numbers: a band on the ground under each panel, which rises to the build limit while the player flies.
 */
final class Band {

    /**
     * Where the wall has faded out, in blocks along it, at the camera's own height.
     *
     * The field's shader fades it by the camera's distance, and the pixel look's last three panels fade.
     */
    static final double RADIUS = 30;

    /** How far the camera can be from the eye, in blocks: a third-person camera stands up to 4 blocks away. */
    static final double CAMERA = 4;

    /** How far the band reaches below the feet or the ground, in blocks. */
    static final double BELOW = 3;

    /** The eye above the feet of a player standing, in blocks, as the shader assumes from the camera. */
    static final double EYE = 1.62;

    /** The field reaches this many blocks farther along the wall for each block it lies below the camera. */
    static final double SLOPE = 0.75;

    /** The field starts to reach farther this many blocks below the camera. */
    static final double LEVEL = 6;

    /** Below this many blocks under the camera, plus {@link #LEVEL}, the field reaches no farther. */
    static final double DEPTH = 96;

    /** How far a fast player moves before the server sends what changed, in blocks. */
    static final double LEAD = 4;

    /** How far above the world's highest block the field's panels end, in blocks, as the shader assumes. */
    static final double TOP = 64;

    /** The height of the field's panels while the wall is the band on the ground, in blocks, as the shader reads it. */
    static final double GROUNDED = 512;

    /** The height of the field's panels while the wall stands up to the build limit, in blocks. */
    static final double SKYWARD = 1024;

    /** The ticks the client takes to stretch a panel from one of the two heights to the other. */
    static final int STRETCH_TICKS = 40;

    /** The free blocks below a player from which the wall rises to the build limit. */
    static final double RISE = 4;

    /** The free blocks below a player up to which the risen wall stays, so it does not flicker at one height. */
    static final double SINK = 3;

    /** How far above the world's highest block the field's entities stand, where the client skips its section check. */
    static final double OVER = 16;

    /** A panel whose ground is not known: its chunk is not loaded. The shader then follows the feet. */
    static final int UNKNOWN = Integer.MIN_VALUE;

    /** The lowest and the highest ground the tint carries. */
    static final int LOWEST = -2048;

    static final int HIGHEST = LOWEST + (1 << 15) - 2;

    private Band() {}

    /**
     * The panels a player sees, each with its model and ground.
     *
     * The player's feet are at {@code x}, {@code eyeY} minus their eye height, {@code z}.
     */
    static Map<Panel, Seen> seen(
            final Wall wall,
            final Look look,
            final double x,
            final double z,
            final double eyeY,
            final ToIntFunction<Panel> ground) {
        final Map<Panel, Seen> seen = new HashMap<>();
        if (look != Look.FIELD) {
            for (final Panel panel : wall.near(x, z, RADIUS)) {
                seen.put(panel, new Seen(look.model(panel.distance(x, z)), UNKNOWN));
            }
            return seen;
        }
        final double farthest = fadeEnd(DEPTH + LEVEL) + CAMERA + LEAD;
        for (final Panel panel : wall.near(x, z, farthest)) {
            final int under = ground.applyAsInt(panel);
            if (panel.distance(x, z) <= reach(eyeY, under)) {
                seen.put(panel, new Seen(look.model(panel.distance(x, z)), under));
            }
        }
        return seen;
    }

    /** Where the field has faded out along the wall, in blocks from the camera, at {@code below} under it. */
    static double fadeEnd(final double below) {
        return RADIUS + SLOPE * Math.clamp(below - LEVEL, 0, DEPTH);
    }

    /**
     * How far from the player a panel is sent, in blocks.
     *
     * The field's reach at the band's foot, from a camera up to {@link #CAMERA} above the eye and beside it.
     */
    static double reach(final double eyeY, final int ground) {
        final double camera = eyeY + CAMERA;
        return fadeEnd(camera - foot(ground, eyeY - CAMERA - EYE)) + CAMERA + LEAD;
    }

    /** The band's lower end over this ground for feet at {@code feetY}. */
    static double foot(final int ground, final double feetY) {
        return (ground == UNKNOWN ? feetY : Math.min(ground, feetY)) - BELOW;
    }

    /**
     * The heights the field's panels cover at {@code stretched}, from 0 for the ground band to 1 for the whole wall.
     * The upper end stays and the lower end sinks, so the shader reads the build limit and the stretch from a panel.
     */
    static Range range(final int maxHeight, final double stretched) {
        final double top = maxHeight + TOP;
        return new Range(top - GROUNDED - (SKYWARD - GROUNDED) * stretched, top);
    }

    /**
     * Whether a player sees the wall up to the build limit.
     * One {@code flying} does, and one in the air whom nothing {@code held} (water, lava or a climb), from
     * {@link #RISE} free blocks below until {@link #SINK} are no longer free.
     */
    static boolean skyward(
            final boolean before, final boolean flying, final boolean held, final DoublePredicate freeBelow) {
        return flying || (!held && freeBelow.test(before ? SINK : RISE));
    }

    /**
     * The tint that carries a panel's ground to the shader, as RGB.
     * Ground plus 2048 uses 15 bits (five per channel, each stored as 130 + 4 times its bits).
     */
    static int tint(final int ground) {
        if (ground == UNKNOWN) {
            return 0xFFFFFF;
        }
        final int value = Math.clamp(ground, LOWEST, HIGHEST) - LOWEST;
        return channel(value >> 10) << 16 | channel(value >> 5) << 8 | channel(value);
    }

    private static int channel(final int bits) {
        return 130 + 4 * (bits & 31);
    }

    /** The height the pixel look's panels stand at for a player whose feet are at {@code feetY}. */
    static double base(final double feetY) {
        return Math.floor(feetY) - BELOW;
    }

    /** What turns the panels {@code shown} into the panels {@code seen}. */
    static Change change(final Map<Panel, Seen> shown, final Map<Panel, Seen> seen) {
        final Map<Panel, Seen> added = new HashMap<>();
        final List<Panel> removed = new ArrayList<>();
        final Map<Panel, Seen> remodelled = new HashMap<>();
        seen.forEach((panel, now) -> {
            final Seen before = shown.get(panel);
            if (before == null) {
                added.put(panel, now);
            } else if (!before.equals(now)) {
                remodelled.put(panel, now);
            }
        });
        for (final Panel panel : shown.keySet()) {
            if (!seen.containsKey(panel)) {
                removed.add(panel);
            }
        }
        return new Change(added, removed, remodelled);
    }

    /** A panel as the player sees it: its item model, in the namespace {@code nordtal}, and its ground. */
    record Seen(String model, int ground) {}

    /**
     * The height the field's entities stand at in a world whose blocks end below {@code maxHeight}.
     *
     * The client draws an entity inside the world's height only once the section around it is built and has faded in.
     */
    static double anchor(final int maxHeight) {
        return maxHeight + OVER;
    }

    /** The view range that reaches from the anchor to the farthest camera below it, at the lowest entity distance. */
    static float viewRange(final int minHeight, final int maxHeight) {
        final double down = anchor(maxHeight) - minHeight + CAMERA;
        final double along = fadeEnd(DEPTH + LEVEL) + CAMERA + LEAD;
        // The client draws an entity up to its view range times 64 blocks, times the entity distance of at least half.
        return (float) Math.ceil(Math.hypot(down, along) / 32);
    }

    /** The heights from {@code bottom} to {@code top} that the field's panels cover, in blocks. */
    record Range(double bottom, double top) {}

    /** The panels to add and to remodel, each as it is seen, and the panels to remove. */
    record Change(Map<Panel, Seen> added, List<Panel> removed, Map<Panel, Seen> remodelled) {}
}

package eu.nordtal.season.smp.wall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoublePredicate;
import java.util.function.Function;

/**
 * The part of the wall a player sees: the panels near them, as a band on the ground or up to the build limit.
 * The field's panels span the world's whole height, and the pack's item shader cuts the wall out of them with
 * these same numbers: a band on the ground under each panel, which rises to the build limit while the player flies.
 */
final class Band {

    /** Where the pixel look has faded out, in blocks along the wall: its last three panels fade. */
    static final double RADIUS = 30;

    /** Where the field has faded out, in blocks from the camera, as the shader fades it. */
    static final double FAR = 128;

    /** How far the camera can be from the eye, in blocks: a third-person camera stands up to 4 blocks away. */
    static final double CAMERA = 4;

    /** How far the band reaches below the feet or the ground, in blocks. */
    static final double BELOW = 3;

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

    /** The lowest and the highest foot the tint carries. */
    static final int LOWEST = -64;

    static final int HIGHEST = LOWEST + (1 << 9) - 1;

    /** The highest rise the tint carries: one more with the highest foot is the tint of an unknown ground. */
    static final int HIGHEST_RISE = (1 << 6) - 2;

    private Band() {}

    /** The panels a player at {@code x}, {@code z} sees, each with its model and ground. */
    static Map<Panel, Seen> seen(
            final Wall wall, final Look look, final double x, final double z, final Function<Panel, Ground> ground) {
        final Map<Panel, Seen> seen = new HashMap<>();
        for (final Panel panel : wall.near(x, z, reach(look))) {
            seen.put(
                    panel,
                    new Seen(
                            look.model(panel.distance(x, z)),
                            look == Look.FIELD ? ground.apply(panel) : Ground.UNKNOWN));
        }
        return seen;
    }

    /**
     * How far from the player a panel is sent, in blocks.
     *
     * The field's reach from a camera up to {@link #CAMERA} beside the eye, for a player {@link #LEAD} farther on.
     */
    static double reach(final Look look) {
        return look == Look.FIELD ? FAR + CAMERA + LEAD : RADIUS;
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
     * The foot above {@link #LOWEST} uses 9 bits and the rise 6 (five per channel, each stored as 130 + 4 times its
     * bits).
     */
    static int tint(final Ground ground) {
        if (ground.equals(Ground.UNKNOWN)) {
            return 0xFFFFFF;
        }
        final int value =
                (Math.clamp(ground.foot(), LOWEST, HIGHEST) - LOWEST) << 6 | Math.clamp(ground.rise(), 0, HIGHEST_RISE);
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
    record Seen(String model, Ground ground) {}

    /**
     * The ground under a panel, where the band's foot stands.
     * The band's upper end is {@code rise} blocks higher than over open ground.
     */
    record Ground(int foot, int rise) {

        /** A panel whose ground is not known: its chunk is not loaded. The shader then follows the feet. */
        static final Ground UNKNOWN = new Ground(Integer.MIN_VALUE, 0);
    }

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
        final double along = reach(Look.FIELD);
        // The client draws an entity up to its view range times 64 blocks, times the entity distance of at least half.
        return (float) Math.ceil(Math.hypot(down, along) / 32);
    }

    /** The heights from {@code bottom} to {@code top} that the field's panels cover, in blocks. */
    record Range(double bottom, double top) {}

    /** The panels to add and to remodel, each as it is seen, and the panels to remove. */
    record Change(Map<Panel, Seen> added, List<Panel> removed, Map<Panel, Seen> remodelled) {}
}

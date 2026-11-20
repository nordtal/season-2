package eu.nordtal.season.smp.wall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The part of the wall a player sees: the panels near them, from below their feet to above their head.
 * The field reaches from the ground under each panel up to above the feet, so a player high up sees the wall
 * stand on the ground; the pack's item shader cuts that band out of the panels with these same numbers.
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

    /** The room the panels keep above and below the band when they are reshaped, in blocks. */
    static final double ROOM = 24;

    /** The panels are reshaped once less room than this is left at either end, in blocks. */
    static final double LEFT = 12;

    /** The panels are shrunk once either end has this much more room than it needs, in blocks. */
    static final double SPARE = 64;

    /** A panel whose ground is not known: its chunk is not loaded. The shader then follows the feet. */
    static final int UNKNOWN = Integer.MIN_VALUE;

    /** The lowest and the highest ground the tint carries. */
    static final int LOWEST = -2048;

    static final int HIGHEST = LOWEST + (1 << 15) - 1;

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

    /** The band's upper end over this ground for feet at {@code feetY}. */
    static double head(final int ground, final double feetY, final Look look) {
        return (ground == UNKNOWN ? feetY : Math.max(ground, feetY)) - BELOW + look.height();
    }

    /** The heights the panels must cover for a player whose eye is at {@code eyeY}, wherever the camera is. */
    static Range need(final Collection<Seen> seen, final double eyeY, final Look look) {
        final double low = eyeY - CAMERA - EYE;
        final double high = eyeY + CAMERA - EYE;
        double bottom = foot(UNKNOWN, low);
        double top = head(UNKNOWN, high, look);
        for (final Seen panel : seen) {
            bottom = Math.min(bottom, foot(panel.ground(), low));
            top = Math.max(top, head(panel.ground(), high, look));
        }
        return new Range(bottom, top);
    }

    /** The heights the panels cover: {@code current} while it fits {@code need}, else {@code need} with room. */
    static Range fit(final Range current, final Range need) {
        final double below = need.bottom() - current.bottom();
        final double above = current.top() - need.top();
        if (below >= LEFT && above >= LEFT && below <= SPARE && above <= SPARE) {
            return current;
        }
        return new Range(Math.floor(need.bottom() - ROOM), Math.ceil(need.top() + ROOM));
    }

    /**
     * The tint that carries a panel's ground to the shader, as RGB, or white while it is not known.
     *
     * The ground plus 2048 in 15 bits, five a channel, each 130 + 4 times its bits, so one off still decodes.
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

    /** A panel as the player sees it: its item model, in the namespace {@code nordtal}, and the ground under it. */
    record Seen(String model, int ground) {}

    /** The heights from {@code bottom} to {@code top} that the field's panels cover, in blocks. */
    record Range(double bottom, double top) {}

    /** The panels to add and to remodel, each as it is seen, and the panels to remove. */
    record Change(Map<Panel, Seen> added, List<Panel> removed, Map<Panel, Seen> remodelled) {}
}

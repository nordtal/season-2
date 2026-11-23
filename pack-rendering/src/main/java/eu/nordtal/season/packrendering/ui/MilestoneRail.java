package eu.nordtal.season.packrendering.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays out the milestone rail across a fixed slot pitch without ever squeezing its 13 px nodes.
 * Focuses on the current milestone and the next two, fades past and far-ahead nodes when omitted, and always shows the
 * last milestone.
 */
public final class MilestoneRail {

    /** The maximum number of node slots across the rail width. */
    public static final int SLOTS = 7;

    /** The fixed width and height of every milestone node in pixels. */
    public static final int NODE = 13;

    /** The state of one milestone node relative to the active milestone. */
    public enum State {
        DONE,
        CURRENT,
        FUTURE
    }

    /** One visible node on the rail. */
    public record Node(int index, int slot, int x, State state, boolean faded) {}

    /** A connector segment between two adjacent visible nodes on the rail. */
    public record Segment(int startX, int endX, boolean dotted, boolean done, boolean faded) {}

    private MilestoneRail() {}

    /**
     * Computes the visible nodes for {@code total} milestones with 0-based {@code current} across {@code width} px.
     * Up to {@link #SLOTS} milestones all stand; beyond that the rail keeps the first, the current and next two, and
     * the last, with dotted gaps where milestones are omitted.
     */
    public static List<Node> nodes(final int total, final int current, final int width) {
        if (total <= 0) {
            return List.of();
        }
        final int cur = Math.max(0, Math.min(current, total - 1));
        final List<int[]> pairs = layoutPairs(total, cur);
        final int count = total > SLOTS ? SLOTS : total;
        final List<Node> out = new ArrayList<>(pairs.size());
        for (final int[] pair : pairs) {
            final int index = pair[0];
            final int slot = pair[1];
            final int x = nodeX(slot, count, width);
            final State state = index < cur ? State.DONE : (index == cur ? State.CURRENT : State.FUTURE);
            final boolean far = total > SLOTS && index > cur + 2 && index != total - 1;
            final boolean faded = index < cur || far;
            out.add(new Node(index, slot, x, state, faded));
        }
        return List.copyOf(out);
    }

    /** Computes the connector segments between consecutive visible {@code nodes}. */
    public static List<Segment> segments(final List<Node> nodes, final int total, final int current) {
        if (nodes.size() < 2) {
            return List.of();
        }
        final int cur = Math.max(0, Math.min(current, Math.max(0, total - 1)));
        final List<Segment> out = new ArrayList<>(nodes.size() - 1);
        for (int i = 0; i < nodes.size() - 1; i++) {
            final Node left = nodes.get(i);
            final Node right = nodes.get(i + 1);
            final boolean dotted = right.slot() - left.slot() > 1;
            final boolean done = dotted ? right.index() <= cur : left.index() < cur;
            final boolean faded = left.index() < cur || (left.faded() && right.index() != total - 1);
            out.add(new Segment(left.x() + NODE, right.x(), dotted, done, faded));
        }
        return List.copyOf(out);
    }

    private static List<int[]> layoutPairs(final int total, final int cur) {
        final List<int[]> pairs = new ArrayList<>(Math.min(total, SLOTS));
        if (total <= SLOTS) {
            for (int i = 0; i < total; i++) {
                pairs.add(new int[] {i, i});
            }
            return pairs;
        }
        if (cur <= 1) {
            for (int i = 0; i < 5; i++) {
                pairs.add(new int[] {i, i});
            }
            pairs.add(new int[] {total - 1, SLOTS - 1});
            return pairs;
        }
        if (cur + 3 >= total - 1) {
            pairs.add(new int[] {0, 0});
            for (int k = 0; k < 5; k++) {
                pairs.add(new int[] {total - 5 + k, 2 + k});
            }
            return pairs;
        }
        pairs.add(new int[] {0, 0});
        pairs.add(new int[] {cur, 2});
        pairs.add(new int[] {cur + 1, 3});
        pairs.add(new int[] {cur + 2, 4});
        pairs.add(new int[] {total - 1, SLOTS - 1});
        return pairs;
    }

    private static int nodeX(final int slot, final int count, final int width) {
        if (count <= 1) {
            return 0;
        }
        return Math.round((float) slot * (width - NODE) / (count - 1));
    }
}

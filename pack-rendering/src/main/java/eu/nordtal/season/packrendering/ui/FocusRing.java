package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;

/**
 * Frames a dialog body line so the client's white focus outline never shows: no menu draws a focus mark.
 * The outline comes first, so a cover row in the colours under it hides all of it; the line's art and the title's
 * ink under the cover are drawn again on top. The row advances 0, centred on the box; a move keeps the width.
 */
public final class FocusRing {

    /** A body line's box is this much wider than its art, so the ring stands clear of it. */
    public static final int MARGIN = 10;

    /** What the ring lies on. */
    public enum Ground {
        /** The panel's grey on every side: the line is narrower than the panel's inside. */
        PANEL(false, false),
        /**
         * The panel's bevel at left and right, for a line as wide as the panel's inside.
         * The left column lies on the white bevel, where the outline cannot be seen, the right on its dark.
         */
        BEVEL(true, false),
        /** As {@link #PANEL}, for a panel's last line, whose bottom row lies on the panel's black border. */
        PANEL_FOOT(false, true),
        /** As {@link #BEVEL}, for a panel's last line, whose bottom row lies on the panel's black border. */
        BEVEL_FOOT(true, true);

        private final boolean bevel;
        private final boolean foot;

        Ground(final boolean bevel, final boolean foot) {
            this.bevel = bevel;
            this.foot = foot;
        }
    }

    private static final int ROW = 9;
    private static final int BOX = 8;
    private static final int PADDING = 8;
    private static final int SIDE = 0;
    private static final int SHADE = 1;
    private static final int SHADE_FOOT = 2;
    private static final int TOP_RUNS = 3;
    private static final int BOTTOM_RUNS = 12;
    private static final int FOOT_RUNS = 21;
    private static final int RUN_COUNT = 9;

    private static final Map<Integer, List<String>> RINGS = Map.ofEntries(
            Map.entry(26, Glyphs.UI_RING_26),
            Map.entry(35, Glyphs.UI_RING_35),
            Map.entry(44, Glyphs.UI_RING_44),
            Map.entry(53, Glyphs.UI_RING_53),
            Map.entry(62, Glyphs.UI_RING_62),
            Map.entry(71, Glyphs.UI_RING_71),
            Map.entry(80, Glyphs.UI_RING_80),
            Map.entry(89, Glyphs.UI_RING_89),
            Map.entry(98, Glyphs.UI_RING_98),
            Map.entry(107, Glyphs.UI_RING_107),
            Map.entry(116, Glyphs.UI_RING_116),
            Map.entry(125, Glyphs.UI_RING_125),
            Map.entry(134, Glyphs.UI_RING_134));

    /** The fewest text rows a framed line has: its art and the row that keeps the box wide. */
    public static final int MIN_ROWS = 2;

    private FocusRing() {}

    /** The box width of a line whose art is {@code art} wide. */
    public static int box(final int art) {
        return art + MARGIN;
    }

    /**
     * Returns {@code line}, one text row that advances {@code art}, framed on {@code ground} in {@code rows} rows.
     * The box is {@code art} + {@link #MARGIN} wide.
     */
    public static Component frame(final Component line, final int art, final int rows, final Ground ground) {
        return frame(line, art, rows, ground, Component.empty());
    }

    /**
     * As {@link #frame(Component, int, int, Ground)}, with {@code over} drawn last, from the box's centre.
     * {@code over} advances 0, such as a title's header that the cover's top row lies on.
     */
    public static Component frame(
            final Component line, final int art, final int rows, final Ground ground, final Component over) {
        if (rows < MIN_ROWS) {
            throw new IllegalArgumentException("a framed line has at least " + MIN_ROWS + " rows, not " + rows);
        }
        final int width = box(art);
        final int height = ROW * rows + BOX;
        final List<String> ring = RINGS.get(height);
        if (ring == null) {
            throw new IllegalArgumentException("the pack has no focus ring " + height + " px high");
        }
        final int left = -(width / 2);
        final String font = Glyphs.FONT_UI_RING;
        final Pen pen = Pen.start();
        // The left column on the white bevel needs no cover; its corner on the black border does.
        final int from = ground.bevel ? left + 1 : left;
        final int to = ground.bevel ? left + width - 1 : left + width;
        if (!ground.bevel) {
            pen.moveTo(left).art(font, ring.get(SIDE), 1);
        }
        final int right = ground.bevel ? (ground.foot ? SHADE_FOOT : SHADE) : SIDE;
        pen.moveTo(left + width - 1).art(font, ring.get(right), 1);
        pen.moveTo(from).runs(font, ring.subList(TOP_RUNS, TOP_RUNS + RUN_COUNT), to - from);
        if (ground.foot) {
            pen.moveTo(left).runs(font, ring.subList(FOOT_RUNS, FOOT_RUNS + RUN_COUNT), to - left);
        } else {
            pen.moveTo(from).runs(font, ring.subList(BOTTOM_RUNS, BOTTOM_RUNS + RUN_COUNT), to - from);
        }
        pen.moveTo(left + (width - art) / 2).append(line, art);
        pen.moveTo(0).append(over, 0);
        Component framed = pen.build(0)
                .append(Component.newline())
                .append(Pen.start().move(width - PADDING).build());
        if (rows > MIN_ROWS) {
            framed = framed.append(Component.text("\n".repeat(rows - MIN_ROWS)));
        }
        return framed;
    }
}

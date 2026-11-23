package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;

/**
 * Frames a dialog body line so the client's white focus outline never shows: no menu draws a focus mark.
 * The outline comes first, so a cover row in the colours under it hides all of it; the line's art follows the cover,
 * and the title's ink under it is drawn again on top. The row advances 0, centred on the box; a move keeps the width.
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
    private static final int SIDE_FOOT = 30;
    private static final int BEVEL_FOOT = 31;

    private static final Map<Integer, List<String>> RINGS = Map.ofEntries(
            Map.entry(17, Glyphs.UI_RING_17),
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
            Map.entry(134, Glyphs.UI_RING_134),
            Map.entry(143, Glyphs.UI_RING_143),
            Map.entry(152, Glyphs.UI_RING_152));

    /** The fewest text rows a multi-row framed line has: its art and the row that keeps the box wide. */
    public static final int MIN_ROWS = 2;

    /** One text row, whose own advance sets the box width so the box is 17 px high. */
    public static final int SINGLE_ROW = 1;

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
        return frame(line, art, rows, ground, over, Map.of());
    }

    /**
     * As {@link #frame(Component, int, int, Ground, Component)}, with {@code below} drawn on the lower text rows.
     * Each entry maps a row from 1 to {@code rows - 1} to art that advances 0 from the left edge of {@code line}.
     */
    public static Component frame(
            final Component line,
            final int art,
            final int rows,
            final Ground ground,
            final Component over,
            final Map<Integer, Component> below) {
        if (rows < SINGLE_ROW) {
            throw new IllegalArgumentException("a framed line has at least " + SINGLE_ROW + " row, not " + rows);
        }
        final int width = box(art);
        final int height = ROW * rows + BOX;
        final List<String> ring = RINGS.get(height);
        if (ring == null) {
            throw new IllegalArgumentException("the pack has no focus ring " + height + " px high");
        }
        final boolean single = rows == SINGLE_ROW;
        final int left = single ? -(PADDING / 2) : -(width / 2);
        final String font = Glyphs.FONT_UI_RING;
        final Pen pen = Pen.start();
        // The bevel's white column needs a cover only at the rounded foot; a side over a foot keeps its shade.
        final int from = ground.bevel ? left + 1 : left;
        final int to = ground.bevel ? left + width - 1 : left + width;
        final int side = ground.foot ? SIDE_FOOT : SIDE;
        if (!ground.bevel) {
            pen.moveTo(left).art(font, ring.get(side), 1);
        } else if (ground.foot) {
            pen.moveTo(left).art(font, ring.get(BEVEL_FOOT), 1);
        }
        final int right = ground.bevel ? (ground.foot ? SHADE_FOOT : SHADE) : side;
        pen.moveTo(left + width - 1).art(font, ring.get(right), 1);
        pen.moveTo(from).runs(font, ring.subList(TOP_RUNS, TOP_RUNS + RUN_COUNT), to - from);
        final int bottom = ground.foot ? FOOT_RUNS : BOTTOM_RUNS;
        pen.moveTo(from).runs(font, ring.subList(bottom, bottom + RUN_COUNT), to - from);
        pen.moveTo(left + (width - art) / 2).append(line, art);
        pen.moveTo(left + width / 2).append(over, 0);
        for (final int row : below.keySet()) {
            if (row < 1 || row >= rows) {
                throw new IllegalArgumentException("a framed line of " + rows + " rows has no lower row " + row);
            }
        }
        if (single) {
            return pen.build(width - PADDING);
        }
        // A lower row advances the box's text width, so the client starts it at the text's left edge.
        final int lineLeft = (width - PADDING - art) / 2;
        Component framed = pen.build(0);
        for (int row = 1; row < rows; row++) {
            framed = framed.append(Component.newline());
            final Component drawn = below.get(row);
            if (drawn != null) {
                framed = framed.append(
                        Pen.start().move(lineLeft).append(drawn, 0).build(width - PADDING));
            } else if (row == 1) {
                framed = framed.append(Pen.start().move(width - PADDING).build());
            }
        }
        return framed;
    }
}

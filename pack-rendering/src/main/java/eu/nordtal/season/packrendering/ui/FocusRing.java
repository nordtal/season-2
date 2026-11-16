package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;

/**
 * Frames a dialog body line so the client's white focus outline shows only as corner brackets.
 * The outline comes before the text, so the first row covers it in the colour beneath but for 5 px at each corner.
 * That row advances 0 and is centred on the box; a second row, a move as wide as the box's text, keeps the width.
 */
public final class FocusRing {

    /** A body line's box is this much wider than its art, so the ring stands clear of it. */
    public static final int MARGIN = 10;

    /** What the ring lies on. */
    public enum Ground {
        /** The panel's grey on every side: the line is narrower than the panel's inside. */
        PANEL(false, false),
        /**
         * The panel's bevel at left and right, for a line as wide as the panel's inside: only the top and bottom arms.
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
    private static final int ARM = 5;
    private static final int SIDE = 0;
    private static final int SHADE = 1;
    private static final int SHADE_FOOT = 2;
    private static final int RUNS = 3;
    private static final int FOOT_RUNS = 12;
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
        final Pen pen = Pen.start().moveTo(left);
        if (!ground.bevel) {
            pen.art(font, ring.get(SIDE), 1);
        }
        final int runs = ground.foot ? FOOT_RUNS : RUNS;
        pen.moveTo(left + ARM).runs(font, ring.subList(runs, runs + RUN_COUNT), width - 2 * ARM);
        final int right = ground.bevel ? (ground.foot ? SHADE_FOOT : SHADE) : SIDE;
        pen.moveTo(left + width - 1).art(font, ring.get(right), 1);
        pen.moveTo(left + (width - art) / 2).append(line, art);
        Component framed = pen.build(0)
                .append(Component.newline())
                .append(Pen.start().move(width - PADDING).build());
        if (rows > MIN_ROWS) {
            framed = framed.append(Component.text("\n".repeat(rows - MIN_ROWS)));
        }
        return framed;
    }
}

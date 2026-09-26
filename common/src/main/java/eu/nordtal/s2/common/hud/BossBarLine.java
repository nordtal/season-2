package eu.nordtal.s2.common.hud;

import eu.nordtal.s2.common.Glyphs;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import org.jspecify.annotations.Nullable;

/**
 * One HUD line: a row of pills, each drawn as wide as what it holds, in {@code nordtal:bossbar}.
 *
 * <b>What a pill is</b>
 *
 * A rounded, dark, translucent background sized to its content, with a lighter rim - one per
 * piece of information, separated by a gap, in the manner of Origin Realms. The SMP's status line
 * is {@code [icon world] [milestone 42 %]}; the hunger games'
 * players line is {@code [icon 3 alive · 2 dead ↗]}. A bearing arrow rides inside the pill of the
 * text it belongs to, not in one of its own.
 *
 * <b>How one is drawn</b>
 *
 * The background first ({@link BossBarWidth#pill}), which leaves the cursor at the pill's right
 * edge; then the cursor walks back to the content column, draws the content, and walks forward
 * to the right edge again. That is only possible because every advance in this font is known
 * ({@link BossBarAdvances}) - the client centres a boss bar name by its total width, and a line
 * whose total width is exact is a line that sits where it should.
 *
 * <b>The component</b>
 *
 * The whole line is <b>one</b> text component naming {@link Glyphs#FONT_BOSSBAR}: the font carries
 * its own ascii sheet, so the readable text is drawn by it too rather than falling out of the
 * styling. And it carries <b>no shadow</b>: the client draws every glyph a second time one pixel
 * down and right, and on a background composed of tiles butted against each other that second
 * copy bleeds out of each tile into the next. The readable text loses its shadow with it, which is
 * the deliberate trade for keeping the line un-split.
 */
public final class BossBarLine {

    /** Pixels between a pill's cap and its content, on either side. */
    public static final int PADDING = 4;

    /** Pixels between two pills. */
    public static final int GAP = 4;

    /** Between an icon and the text after it - the ordinary space, which this font draws 3 wide. */
    public static final String ICON_GAP = Glyphs.BOSSBAR_SPACE_PLUS_3;

    private BossBarLine() {}

    /**
     * One pill's content: an optional icon glyph and the text after it.
     *
     * @param icon a {@code nordtal:bossbar} icon or arrow, or null for a text-only pill
     * @param text what the pill says, already translated; may be empty for an icon-only pill
     */
    public record Pill(@Nullable String icon, String text) {

        public Pill {
            Objects.requireNonNull(text, "text");
        }

        public static Pill of(final String text) {
            return new Pill(null, text);
        }

        public static Pill of(final String icon, final String text) {
            return new Pill(Objects.requireNonNull(icon, "icon"), text);
        }

        /** The glyphs inside the pill, icon first. */
        String content() {
            if (icon == null) {
                return text;
            }
            return text.isEmpty() ? icon : icon + ICON_GAP + text;
        }
    }

    /** @return the line as the component a {@code BossBar#name} takes */
    public static Component render(final List<Pill> pills) {
        return Component.text(compose(pills)).font(Key.key(Glyphs.FONT_BOSSBAR)).shadowColor(ShadowColor.none());
    }

    /** The same, as the raw glyph string - for a test to walk with a cursor. */
    public static String compose(final List<Pill> pills) {
        final StringBuilder out = new StringBuilder();
        for (int index = 0; index < pills.size(); index++) {
            final String content = pills.get(index).content();
            final int width = BossBarAdvances.width(content);
            // The content's advance ends one pixel past its last column; without the -1 the right padding grows.
            final int inner = PADDING + Math.max(0, width - 1) + PADDING;

            out.append(BossBarWidth.pill(inner));
            // The pill left the cursor at its right edge; the content column is CAP + PADDING in.
            out.append(left(inner + BossBarWidth.CAP - PADDING));
            out.append(content);
            // Forward to the pill's right edge, CAP + inner + CAP from its start.
            out.append(right(inner + BossBarWidth.CAP - PADDING - width));
            if (index < pills.size() - 1) {
                out.append(right(GAP));
            }
        }
        return out.toString();
    }

    /**
     * Moves the cursor {@code pixels} to the left, out of the font's eight negative advances.
     *
     * Unlike {@code MenuTitle.shift} this repeats the largest advance rather than refusing past
     * 255, because a pill is as wide as a translated milestone name and nothing bounds that.
     */
    public static String left(final int pixels) {
        return shift(pixels, LEFT_WIDTHS, LEFT_GLYPHS);
    }

    /** Moves the cursor {@code pixels} to the right. */
    public static String right(final int pixels) {
        return shift(pixels, RIGHT_WIDTHS, RIGHT_GLYPHS);
    }

    private static final int[] LEFT_WIDTHS = {128, 64, 32, 16, 8, 4, 2, 1};
    private static final String[] LEFT_GLYPHS = {
        Glyphs.BOSSBAR_SPACE_MINUS_128, Glyphs.BOSSBAR_SPACE_MINUS_64,
        Glyphs.BOSSBAR_SPACE_MINUS_32, Glyphs.BOSSBAR_SPACE_MINUS_16,
        Glyphs.BOSSBAR_SPACE_MINUS_8, Glyphs.BOSSBAR_SPACE_MINUS_4,
        Glyphs.BOSSBAR_SPACE_MINUS_2, Glyphs.BOSSBAR_SPACE_MINUS_1,
    };

    // No +64 or +128 exists in the font; nothing on a HUD line moves that far to the right.
    private static final int[] RIGHT_WIDTHS = {32, 16, 8, 4, 2, 1};
    private static final String[] RIGHT_GLYPHS = {
        Glyphs.BOSSBAR_SPACE_PLUS_32, Glyphs.BOSSBAR_SPACE_PLUS_16,
        Glyphs.BOSSBAR_SPACE_PLUS_8, Glyphs.BOSSBAR_SPACE_PLUS_4,
        Glyphs.BOSSBAR_SPACE_PLUS_2, Glyphs.BOSSBAR_SPACE_PLUS_1,
    };

    private static String shift(final int pixels, final int[] widths, final String[] glyphs) {
        if (pixels < 0) {
            throw new IllegalArgumentException("a shift is a distance, not a direction: " + pixels);
        }
        final StringBuilder out = new StringBuilder();
        int left = pixels;
        for (int index = 0; index < widths.length; index++) {
            while (left >= widths[index]) {
                out.append(glyphs[index]);
                left -= widths[index];
            }
        }
        return out.toString();
    }
}

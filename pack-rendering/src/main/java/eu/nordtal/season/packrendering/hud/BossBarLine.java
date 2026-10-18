package eu.nordtal.season.packrendering.hud;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import org.jspecify.annotations.Nullable;

/** A HUD line of pills in {@code nordtal:bossbar}. It is one component without shadow. */
public final class BossBarLine {

    /** Pixels between a pill's cap and its content. */
    public static final int PADDING = 4;

    /** Pixels between two pills. */
    public static final int GAP = 4;

    /** The width of the space between an icon and its text. */
    public static final String ICON_GAP = Glyphs.BOSSBAR_SPACE_PLUS_3;

    private BossBarLine() {}

    /** One pill's content: an optional icon and a text, which may be empty. */
    public record Pill(@Nullable String icon, String text) {

        public Pill {
            // A line break in the font draws as an unknown character.
            text = Objects.requireNonNull(text, "text").replace('\n', ' ');
        }

        public static Pill of(final String text) {
            return new Pill(null, text);
        }

        public static Pill of(final String icon, final String text) {
            return new Pill(Objects.requireNonNull(icon, "icon"), text);
        }

        String content() {
            if (icon == null) {
                return text;
            }
            return text.isEmpty() ? icon : icon + ICON_GAP + text;
        }
    }

    public static BossBar bar() {
        return BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
    }

    public static void show(final BossBar bar, final List<Pill> pills) {
        bar.name(render(pills));
    }

    public static Component render(final List<Pill> pills) {
        return Component.text(compose(pills)).font(Key.key(Glyphs.FONT_BOSSBAR)).shadowColor(ShadowColor.none());
    }

    public static String compose(final List<Pill> pills) {
        final StringBuilder out = new StringBuilder();
        for (int index = 0; index < pills.size(); index++) {
            final String content = pills.get(index).content();
            final int width = BossBarAdvances.width(content);
            // The advance of the content ends one pixel after its last column.
            final int inner = PADDING + Math.max(0, width - 1) + PADDING;

            out.append(BossBarWidth.pill(inner));
            out.append(left(inner + BossBarWidth.CAP - PADDING));
            out.append(content);
            out.append(right(inner + BossBarWidth.CAP - PADDING - width));
            if (index < pills.size() - 1) {
                out.append(right(GAP));
            }
        }
        return out.toString();
    }

    /** Moves the cursor left by {@code pixels}. */
    public static String left(final int pixels) {
        return shift(pixels, LEFT_WIDTHS, LEFT_GLYPHS);
    }

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

    // The font has no +64 or +128.
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

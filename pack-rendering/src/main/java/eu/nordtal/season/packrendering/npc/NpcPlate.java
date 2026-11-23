package eu.nordtal.season.packrendering.npc;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.ui.Letters;
import eu.nordtal.season.packrendering.ui.Pen;
import eu.nordtal.season.packrendering.ui.Symbol;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * Composes an NPC's name plate in the world: a pill in its colour, with its icon, if it has one, and its name.
 * It is 13 high and two lines of one advance that lie on each other: its panel, and its ink, which a display draws a
 * little before it. In one line no two pieces of different colours cover the same pixel.
 */
public final class NpcPlate {

    /** How a plate looks: its colour, and the icon before its name, if it has one. */
    public record Look(TextColor colour, @Nullable Symbol icon) {}

    /** The rows from the text row down to the plate's top. */
    private static final int TOP = 2;

    /** The rows from the text row to the top of its name and icon. */
    private static final int TEXT = TOP + 3;

    /** The columns from the plate's left edge to its icon or name, and from its name to its right edge. */
    private static final int PAD = 7;

    /** The columns an icon takes before the name: the symbol and 4 clear. */
    private static final int ICON = 11;

    /** The pill's ends, 3 columns each. */
    private static final int END = 3;

    /** The speech bubble's width, and its dots' width from its left edge. */
    static final int BUBBLE = 13;

    private static final int DOTS = 10;

    private static final String FONT = Glyphs.FONT_NPC_PLATE;

    private NpcPlate() {}

    /** Returns the plate's panel; with {@code bubble}, the speech bubble stands over it. */
    public static Component panel(final Look look, final String name, final boolean bubble) {
        final int width = width(look, name);
        final List<String> pill = Glyphs.NPC_PLATE_PILL;
        final Pen pen = Pen.start();
        pen.moveTo(0).art(FONT, pill.get(0), END, look.colour());
        span(pen, pill.subList(2, pill.size()), END - 1, width - END + 1, look.colour());
        pen.moveTo(width - END).art(FONT, pill.get(1), END, look.colour());
        if (bubble) {
            pen.moveTo(bubbleX(width)).art(FONT, Glyphs.NPC_SPEECH_BUBBLE, BUBBLE);
        }
        return pen.build(width);
    }

    /** Returns the plate's ink: its light row, its icon and its name, and the bubble's dots. */
    public static Component ink(final Look look, final String name, final boolean bubble) {
        final int width = width(look, name);
        final TextColor colour = look.colour();
        final Pen pen = Pen.start();
        span(pen, Glyphs.NPC_PLATE_LIGHT, 2, width - 2, DialogueScreen.lighter(colour, 0.25));
        int text = PAD;
        if (look.icon() != null) {
            pen.moveTo(text).symbol(TEXT, look.icon(), NamedTextColor.WHITE);
            text += ICON;
        }
        pen.moveTo(text).text(TEXT, name, NamedTextColor.WHITE);
        if (bubble) {
            pen.moveTo(bubbleX(width)).art(FONT, Glyphs.NPC_SPEECH_DOTS, DOTS, colour);
        }
        return pen.build(width);
    }

    /** Returns the plate's width, which is the advance of its panel and of its ink. */
    public static int width(final Look look, final String name) {
        return PAD + (look.icon() == null ? 0 : ICON) + Letters.visibleWidth(name) + PAD;
    }

    /** Where the bubble starts so it stands over the plate's middle. */
    static int bubbleX(final int width) {
        return (width - BUBBLE) / 2;
    }

    /**
     * Draws runs of alike columns from {@code from} up to {@code to}, each over the last column of the one before.
     * In the world a display places each glyph at fractional pixels, where two glyphs that only touch leave a hairline
     * of sky between them.
     */
    private static void span(
            final Pen pen, final List<String> runs, final int from, final int to, final TextColor colour) {
        int x = from;
        while (x < to) {
            final int length = Integer.highestOneBit(Math.min(to - x, 1 << (runs.size() - 1)));
            pen.moveTo(x).art(FONT, runs.get(Integer.numberOfTrailingZeros(length)), length, colour);
            if (x + length >= to) {
                return;
            }
            x += length - 1;
        }
    }
}

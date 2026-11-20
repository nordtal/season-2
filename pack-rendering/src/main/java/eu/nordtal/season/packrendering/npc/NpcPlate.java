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
 * Composes an NPC's name plate in the world in the style, colour and icon chosen for it.
 * It is 15 high and two lines of one advance that lie on each other: its panel, and its ink, which a display draws a
 * little before it. In one line no two pieces of different colours cover the same pixel.
 */
public final class NpcPlate {

    /** The plate's shape. */
    public enum Style {
        /** A rounded box in the NPC's colour. */
        PILL,
        /** A dark rounded box with a stripe in the NPC's colour. */
        DARK,
        /** A banner in the NPC's colour, its ends notched. */
        RIBBON,
        /** A round badge in the NPC's colour, holding the icon, before a dark box. */
        BADGE
    }

    /** How a plate looks: its style, its colour, and the icon before its name, if it has one. */
    public record Look(
            Style style, TextColor colour, @Nullable Symbol icon) {}

    /** The rows from the plate's top to the top of its name and icon. */
    private static final int TEXT = 4;

    /** The columns an icon takes before the name: the symbol and 4 clear. */
    private static final int ICON = 11;

    /** The width of the badge's circle. */
    private static final int CIRCLE = 15;

    /** The circle's light top, which ends 12 columns from the circle's left edge. */
    private static final int CAP = 12;

    /** The columns from a badge's left edge to its name. */
    private static final int BADGE_TEXT = 19;

    /** The dark plate's stripe, 3 columns 2 from its left edge. */
    private static final int STRIPE = 3;

    private static final int STRIPE_X = 2;

    /** The speech bubble's width, and its dots' width from its left edge. */
    static final int BUBBLE = 13;

    private static final int DOTS = 10;

    private static final String FONT = Glyphs.FONT_NPC_PLATE;

    private NpcPlate() {}

    /** Returns the plate's panel; with {@code bubble}, the speech bubble stands over it. */
    public static Component panel(final Look look, final String name, final boolean bubble) {
        final int width = width(look, name);
        final Pen pen = Pen.start();
        switch (look.style()) {
            case PILL -> frame(pen, Glyphs.NPC_PLATE_PILL, 3, 3, width, look.colour());
            case DARK -> frame(pen, Glyphs.NPC_PLATE_DARK, 3, 3, width, NamedTextColor.WHITE);
            case RIBBON -> frame(pen, Glyphs.NPC_PLATE_RIBBON, 5, 5, width, look.colour());
            case BADGE -> {
                pen.art(FONT, Glyphs.NPC_PLATE_CIRCLE, CIRCLE, look.colour()).moveTo(0);
                frame(pen, Glyphs.NPC_PLATE_BADGE, 16, 3, width, NamedTextColor.WHITE);
            }
        }
        if (bubble) {
            pen.moveTo(bubbleX(width)).art(FONT, Glyphs.NPC_SPEECH_BUBBLE, BUBBLE);
        }
        return pen.build(width);
    }

    /** Returns the plate's ink: its light row, its stripe, its icon and its name, and the bubble's dots. */
    public static Component ink(final Look look, final String name, final boolean bubble) {
        final int width = width(look, name);
        final TextColor colour = look.colour();
        final Pen pen = Pen.start();
        final int text = switch (look.style()) {
            case PILL -> {
                span(pen, Glyphs.NPC_PLATE_LIGHT, 2, width - 2, DialogueScreen.lighter(colour, 0.25));
                yield icon(pen, 7, look.icon(), NamedTextColor.WHITE);
            }
            case DARK -> {
                pen.moveTo(STRIPE_X).art(FONT, Glyphs.NPC_PLATE_STRIPE, STRIPE, colour);
                yield icon(pen, 11, look.icon(), DialogueScreen.lighter(colour, 0.55));
            }
            case RIBBON -> {
                span(pen, Glyphs.NPC_PLATE_LIGHT_LOW, 4, width - 4, DialogueScreen.lighter(colour, 0.25));
                yield icon(pen, 10, look.icon(), NamedTextColor.WHITE);
            }
            case BADGE -> {
                pen.moveTo(0).art(FONT, Glyphs.NPC_PLATE_CAP, CAP, DialogueScreen.lighter(colour, 0.2));
                final Symbol icon = look.icon() == null ? Symbol.STAR : look.icon();
                pen.moveTo(4).symbol(TEXT, icon, NamedTextColor.WHITE);
                yield BADGE_TEXT;
            }
        };
        pen.moveTo(text).bold(Pen.textFont(TEXT), name, NamedTextColor.WHITE);
        if (bubble) {
            pen.moveTo(bubbleX(width)).art(FONT, Glyphs.NPC_SPEECH_DOTS, DOTS, colour);
        }
        return pen.build(width);
    }

    /** Returns the plate's width, which is the advance of its panel and of its ink. */
    public static int width(final Look look, final String name) {
        final int icon = look.icon() == null ? 0 : ICON;
        final int text = Letters.boldVisibleWidth(name);
        return switch (look.style()) {
            case PILL -> 7 + icon + text + 7;
            case DARK -> 11 + icon + text + 7;
            case RIBBON -> 10 + icon + text + 10;
            case BADGE -> BADGE_TEXT + text + 7;
        };
    }

    /** Where the bubble starts so it stands over the plate's middle. */
    static int bubbleX(final int width) {
        return (width - BUBBLE) / 2;
    }

    private static int icon(final Pen pen, final int x, final @Nullable Symbol icon, final TextColor colour) {
        if (icon == null) {
            return x;
        }
        pen.moveTo(x).symbol(TEXT, icon, colour);
        return x + ICON;
    }

    /**
     * Draws a plate {@code width} wide from {@code glyphs}, with ends of {@code left} and {@code right} columns.
     * The middles between them repeat each end's inner column.
     */
    private static void frame(
            final Pen pen,
            final List<String> glyphs,
            final int left,
            final int right,
            final int width,
            final TextColor colour) {
        pen.moveTo(0).art(FONT, glyphs.get(0), left, colour);
        span(pen, glyphs.subList(2, glyphs.size()), left - 1, width - right + 1, colour);
        pen.moveTo(width - right).art(FONT, glyphs.get(1), right, colour);
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

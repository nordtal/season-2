package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.packrendering.Glyphs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * Composes one line of glyphs left to right, as the client advances its cursor: art, letters, symbols and moves.
 * Positions are pixels from where the component starts. Every piece names its font and colour and casts no shadow.
 */
public final class Pen {

    private static final int[] RUNS = {256, 128, 64, 32, 16, 8, 4, 2, 1};

    /** Which pieces a pen draws. A piece it leaves out is moved over, so the others keep their places. */
    public enum Layer {
        /** Everything. */
        ALL,
        /** Art alone, such as a pill's panel. */
        ART,
        /** Letters and symbols alone. */
        INK
    }

    private final Layer layer;

    private final List<Component> parts = new ArrayList<>();
    private final StringBuilder run = new StringBuilder();
    private @Nullable String runFont;
    private @Nullable TextColor runColour;
    private int x;
    private int pending;

    private Pen(final Layer layer) {
        this.layer = layer;
    }

    /** Returns a pen at x 0. */
    public static Pen start() {
        return new Pen(Layer.ALL);
    }

    /** Returns a pen at x 0 that draws only {@code layer}. */
    public static Pen start(final Layer layer) {
        return new Pen(layer);
    }

    /** Returns where the next piece starts. */
    public int x() {
        return x + pending;
    }

    /** Moves to {@code target}, without drawing. */
    public Pen moveTo(final int target) {
        return move(target - x());
    }

    /** Moves by {@code pixels}, forward or back, without drawing. */
    public Pen move(final int pixels) {
        pending += pixels;
        return this;
    }

    /** Draws {@code glyph} of {@code font}, whose ink is {@code width} columns, and moves {@code width} on. */
    public Pen art(final String font, final String glyph, final int width) {
        if (layer == Layer.INK) {
            return move(width);
        }
        put(font, Colours.ART, glyph, width + 1);
        return move(-1);
    }

    /** Draws {@code text} in the ui text font {@code shift} pixels below the text row, and moves by its advance. */
    public Pen text(final int shift, final String text, final TextColor colour) {
        if (layer == Layer.ART) {
            return move(Letters.width(text));
        }
        put(textFont(shift), colour, text, Letters.width(text));
        return this;
    }

    /** Draws {@code symbol} in the ui text font {@code shift} pixels below the text row, and moves 8 on. */
    public Pen symbol(final int shift, final Symbol symbol, final TextColor colour) {
        if (layer == Layer.ART) {
            return move(Symbol.ADVANCE);
        }
        put(textFont(shift), colour, symbol.glyph(), Symbol.ADVANCE);
        return this;
    }

    /** Draws a plate of {@code look}, {@code width} wide, in the ui art font of {@code shift}. */
    public Pen plate(final int shift, final Look look, final int width) {
        if (width < 2) {
            throw new IllegalArgumentException("a plate is at least 2 columns wide, not " + width);
        }
        final String font = artFont(shift);
        final List<String> glyphs = look.glyphs();
        art(font, glyphs.get(0), 1);
        runs(font, glyphs.subList(2, glyphs.size()), width - 2);
        return art(font, glyphs.get(1), 1);
    }

    /** Draws runs of 1, 2, 4 to 256 columns from {@code glyphs}, smallest first, that fill {@code width} columns. */
    Pen runs(final String font, final List<String> glyphs, final int width) {
        int rest = width;
        for (final int length : RUNS) {
            final int index = Integer.numberOfTrailingZeros(length);
            if (index >= glyphs.size()) {
                continue;
            }
            while (rest >= length) {
                art(font, glyphs.get(index), length);
                rest -= length;
            }
        }
        if (rest != 0) {
            throw new IllegalArgumentException("runs up to " + (1 << (glyphs.size() - 1)) + " cannot fill " + width);
        }
        return this;
    }

    /** Appends {@code component} as it is, which moves the cursor by {@code advance}. */
    public Pen append(final Component component, final int advance) {
        flushMove();
        flushRun();
        parts.add(component);
        x += advance;
        return this;
    }

    /** Returns the line, ending at x {@code end}, which is its net advance. */
    public Component build(final int end) {
        moveTo(end);
        return build();
    }

    /** Returns the line, ending where the cursor is. */
    public Component build() {
        flushMove();
        flushRun();
        return Component.empty().children(parts);
    }

    /** The ui text font {@code shift} pixels below the text row. */
    public static String textFont(final int shift) {
        return font(Glyphs.FONT_UI_TEXT, shift, "text");
    }

    /** The ui art font {@code shift} pixels below the text row. */
    public static String artFont(final int shift) {
        return font(Glyphs.FONT_UI_ART, shift, "art");
    }

    private static String font(final Map<Integer, String> fonts, final int shift, final String what) {
        final String font = fonts.get(shift);
        if (font == null) {
            throw new IllegalArgumentException(
                    "the pack has no ui " + what + " font " + shift + " px low; glyphs.json lists " + fonts.keySet());
        }
        return font;
    }

    private void put(final String font, final TextColor colour, final String glyphs, final int advance) {
        flushMove();
        if (!font.equals(runFont) || !colour.equals(runColour)) {
            flushRun();
            runFont = font;
            runColour = colour;
        }
        run.append(glyphs);
        x += advance;
    }

    private void flushMove() {
        if (pending == 0) {
            return;
        }
        final StringBuilder moves = new StringBuilder();
        int rest = Math.abs(pending);
        for (final int length : RUNS) {
            while (rest >= length) {
                moves.append(space(pending < 0, length));
                rest -= length;
            }
        }
        final int moved = pending;
        pending = 0;
        if (runFont != null) {
            // Every ui font references the moves, so they join the run that is open.
            run.append(moves);
            x += moved;
            return;
        }
        put(Glyphs.FONT_UI_SPACE, Colours.ART, moves.toString(), moved);
    }

    private void flushRun() {
        if (run.isEmpty() || runFont == null || runColour == null) {
            return;
        }
        parts.add(Component.text(run.toString())
                .font(Key.key(runFont))
                .color(runColour)
                .shadowColor(ShadowColor.none()));
        run.setLength(0);
        runFont = null;
        runColour = null;
    }

    private static String space(final boolean back, final int length) {
        return switch (length) {
            case 256 -> back ? Glyphs.UI_BACK_256 : Glyphs.UI_FORWARD_256;
            case 128 -> back ? Glyphs.UI_BACK_128 : Glyphs.UI_FORWARD_128;
            case 64 -> back ? Glyphs.UI_BACK_64 : Glyphs.UI_FORWARD_64;
            case 32 -> back ? Glyphs.UI_BACK_32 : Glyphs.UI_FORWARD_32;
            case 16 -> back ? Glyphs.UI_BACK_16 : Glyphs.UI_FORWARD_16;
            case 8 -> back ? Glyphs.UI_BACK_8 : Glyphs.UI_FORWARD_8;
            case 4 -> back ? Glyphs.UI_BACK_4 : Glyphs.UI_FORWARD_4;
            case 2 -> back ? Glyphs.UI_BACK_2 : Glyphs.UI_FORWARD_2;
            default -> back ? Glyphs.UI_BACK_1 : Glyphs.UI_FORWARD_1;
        };
    }
}

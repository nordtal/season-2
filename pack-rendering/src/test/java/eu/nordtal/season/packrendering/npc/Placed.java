package eu.nordtal.season.packrendering.npc;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.PackAdvances;
import eu.nordtal.season.packrendering.ui.Letters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * What a composed line draws and where, measured against the assembled pack as the client advances through it.
 * Each run of drawn characters between two moves is one {@link Ink}.
 */
final class Placed {

    private static final Map<String, Map<Integer, Integer>> TABLES = new HashMap<>();
    private static final Map<Integer, Integer> MOVES = table(Glyphs.FONT_UI_SPACE);

    /** Characters drawn in one font and colour from x on, with no move between them. */
    record Ink(String font, @Nullable TextColor colour, int x, String text) {}

    private final List<Ink> inks = new ArrayList<>();
    private int x;

    private Placed() {}

    /** Measures {@code component}. */
    static Placed of(final Component component) {
        final Placed placed = new Placed();
        placed.walk(component, null, null);
        return placed;
    }

    /** How far the line moves the cursor. */
    int advance() {
        return x;
    }

    List<Ink> inks() {
        return List.copyOf(inks);
    }

    /** The inks drawn in {@code font}. */
    List<Ink> in(final String font) {
        return inks.stream().filter(ink -> ink.font().equals(font)).toList();
    }

    /** The ink whose text is {@code text}; it must be drawn once. */
    Ink only(final String text) {
        final List<Ink> found =
                inks.stream().filter(ink -> ink.text().equals(text)).toList();
        if (found.size() != 1) {
            throw new AssertionError("'" + text + "' is drawn " + found.size() + " times in " + inks);
        }
        return found.getFirst();
    }

    private void walk(final Component component, final @Nullable String font, final @Nullable TextColor colour) {
        final Key key = component.style().font();
        final String own = key == null ? font : key.asString();
        final TextColor tint =
                component.style().color() == null ? colour : component.style().color();
        if (component instanceof final TextComponent text && !text.content().isEmpty()) {
            if (own == null) {
                throw new AssertionError("'" + text.content() + "' names no font");
            }
            StringBuilder run = new StringBuilder();
            int start = x;
            for (final int codePoint : text.content().codePoints().toArray()) {
                if (MOVES.containsKey(codePoint)) {
                    flush(own, tint, start, run);
                    run = new StringBuilder();
                    x += MOVES.get(codePoint);
                    start = x;
                    continue;
                }
                run.appendCodePoint(codePoint);
                x += advance(own, codePoint);
            }
            flush(own, tint, start, run);
        }
        for (final Component child : component.children()) {
            walk(child, own, tint);
        }
    }

    private void flush(final String font, final @Nullable TextColor colour, final int start, final StringBuilder run) {
        if (!run.isEmpty()) {
            inks.add(new Ink(font, colour, start, run.toString()));
        }
    }

    private static int advance(final String font, final int codePoint) {
        if (Glyphs.FONT_NPC_TEXT.containsValue(font)) {
            // Vanilla's letters, measured once by the build; ResourcePackTest holds the npc text fonts to them.
            return Letters.advance(codePoint);
        }
        final Integer advance = TABLES.computeIfAbsent(font, Placed::table).get(codePoint);
        if (advance == null) {
            throw new AssertionError("%s has no U+%X".formatted(font, codePoint));
        }
        return advance;
    }

    private static Map<Integer, Integer> table(final String font) {
        return PackAdvances.of(RepositoryRoot.packAssets() + "/" + font.replace(":", "/font/") + ".json");
    }
}

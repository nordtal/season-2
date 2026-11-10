package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.PackAdvances;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/** Measures a composed component against the assembled pack's fonts, as the client advances through it. */
final class Advances {

    private static final Map<String, Map<Integer, Integer>> TABLES = new HashMap<>();

    private Advances() {}

    /** Returns how far {@code component} moves the cursor. */
    static int of(final Component component) {
        int total = 0;
        if (component instanceof final TextComponent text) {
            final Key font = component.style().font();
            final String id = font == null ? "minecraft:default" : font.asString();
            for (final int codePoint : text.content().codePoints().toArray()) {
                total += advance(id, codePoint);
            }
        }
        for (final Component child : component.children()) {
            total += of(child);
        }
        return total;
    }

    private static int advance(final String font, final int codePoint) {
        if (Glyphs.FONT_UI_TEXT.containsValue(font) || "minecraft:default".equals(font)) {
            // Vanilla's letters, which the pack does not hold: the build's measured table.
            return Letters.advance(codePoint);
        }
        final Map<Integer, Integer> table = TABLES.computeIfAbsent(
                font, id -> PackAdvances.of(RepositoryRoot.packAssets() + "/" + id.replace(":", "/font/") + ".json"));
        final Integer advance = table.get(codePoint);
        if (advance == null) {
            throw new AssertionError("%s has no U+%X".formatted(font, codePoint));
        }
        return advance;
    }
}

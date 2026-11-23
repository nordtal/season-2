package eu.nordtal.season.packrendering.ui;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.PackAdvances;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;

/** Measures a composed component against the assembled pack's fonts, as the client advances through it. */
final class Advances {

    /** How far the client advances an object such as a sprite or a face. */
    static final int OBJECT = 8;

    private static final Map<String, Map<Integer, Integer>> TABLES = new HashMap<>();

    private Advances() {}

    /** An object's text row, counted from 0, and the cursor's advance from the row's start where it begins. */
    record Spot(int row, int x) {}

    /** Returns how far each text row of {@code component} moves the cursor, a row ending at each line break. */
    static List<Integer> rows(final Component component) {
        final List<Integer> rows = new ArrayList<>(List.of(0));
        walk(component, rows, new ArrayList<>());
        return rows;
    }

    /** Returns where each object of {@code component} begins, in the order the client draws them. */
    static List<Spot> objects(final Component component) {
        final List<Spot> spots = new ArrayList<>();
        walk(component, new ArrayList<>(List.of(0)), spots);
        return spots;
    }

    private static void walk(final Component component, final List<Integer> rows, final List<Spot> spots) {
        if (component instanceof ObjectComponent) {
            spots.add(new Spot(rows.size() - 1, rows.getLast()));
            rows.set(rows.size() - 1, rows.getLast() + OBJECT);
        }
        if (component instanceof final TextComponent text) {
            final Key font = component.style().font();
            final String id = font == null ? "minecraft:default" : font.asString();
            for (final int codePoint : text.content().codePoints().toArray()) {
                if (codePoint == '\n') {
                    rows.add(0);
                } else {
                    rows.set(rows.size() - 1, rows.getLast() + advance(id, codePoint) + bold(component));
                }
            }
        }
        for (final Component child : component.children()) {
            walk(child, rows, spots);
        }
    }

    /** Returns where the first text component whose content equals {@code target} begins on row 0. */
    static int firstTextX(final Component component, final String target) {
        final int[] found = {Integer.MIN_VALUE};
        findText(component, target, new int[] {0}, found);
        if (found[0] == Integer.MIN_VALUE) {
            throw new AssertionError("no text component equals '" + target + "'");
        }
        return found[0];
    }

    private static void findText(
            final Component component, final String target, final int[] cursor, final int[] found) {
        if (found[0] != Integer.MIN_VALUE) {
            return;
        }
        if (component instanceof ObjectComponent) {
            cursor[0] += OBJECT;
        }
        if (component instanceof final TextComponent text) {
            if (target.equals(text.content())) {
                found[0] = cursor[0];
                return;
            }
            final Key font = component.style().font();
            final String id = font == null ? "minecraft:default" : font.asString();
            for (final int codePoint : text.content().codePoints().toArray()) {
                if (codePoint == '\n') {
                    return;
                }
                cursor[0] += advance(id, codePoint) + bold(component);
            }
        }
        for (final Component child : component.children()) {
            findText(child, target, cursor, found);
        }
    }

    /** Returns how far {@code component} moves the cursor. */
    static int of(final Component component) {
        int total = component instanceof ObjectComponent ? OBJECT : 0;
        if (component instanceof final TextComponent text) {
            final Key font = component.style().font();
            final String id = font == null ? "minecraft:default" : font.asString();
            for (final int codePoint : text.content().codePoints().toArray()) {
                total += advance(id, codePoint) + bold(component);
            }
        }
        for (final Component child : component.children()) {
            total += of(child);
        }
        return total;
    }

    /** The client's extra column for a bold letter. */
    private static int bold(final Component component) {
        return component.style().decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE ? 1 : 0;
    }

    private static int advance(final String font, final int codePoint) {
        final boolean letters = Glyphs.FONT_UI_TEXT.containsValue(font) || "minecraft:default".equals(font);
        if (letters && !Character.isSupplementaryCodePoint(codePoint)) {
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

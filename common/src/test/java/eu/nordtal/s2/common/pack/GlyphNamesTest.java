package eu.nordtal.s2.common.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.nordtal.s2.common.Glyphs;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds the glyph names - what a message writes as {@code <glyph:name>}, and what Steward's editor
 * offers - to {@link Glyphs} and to {@code minecraft:default}. A name that points nowhere renders
 * as its own tag text in game; a glyph without a name is one the editor cannot offer.
 */
class GlyphNamesTest {

    private static final FontFile DEFAULT_FONT =
            FontFile.load("minecraft:default", "resource-pack/src/assets/minecraft/font/default.json");

    @Test
    @DisplayName("every name is a Glyphs constant the default font declares")
    void everyNameResolves() {
        final Set<String> constants = constants();
        final List<String> wrong = new ArrayList<>();
        Glyphs.named().forEach((name, glyph) -> {
            if (!constants.contains(glyph)) {
                wrong.add(name + " is no Glyphs constant");
            }
            if (!DEFAULT_FONT.covers(glyph.codePointAt(0))) {
                wrong.add(name + " is not declared in minecraft:default");
            }
            if (!name.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
                wrong.add(name + " is not lowercase and hyphenated");
            }
        });
        assertEquals(List.of(), wrong);
    }

    @Test
    @DisplayName("every glyph of the default font has exactly one name")
    void everyGlyphIsNamed() {
        final Set<Integer> declared = new TreeSet<>();
        DEFAULT_FONT.bitmaps().forEach(bitmap -> bitmap.characters().forEach(declared::add));
        final Map<String, String> named = Glyphs.named();
        final List<Integer> codePoints =
                named.values().stream().map(glyph -> glyph.codePointAt(0)).toList();
        assertEquals(Set.copyOf(codePoints).size(), codePoints.size(), "two names for one glyph");
        final Set<Integer> unnamed = new TreeSet<>(declared);
        codePoints.forEach(unnamed::remove);
        assertEquals(Set.of(), unnamed.stream().map(Integer::toHexString).collect(java.util.stream.Collectors.toSet()));
        assertFalse(named.isEmpty());
    }

    private static Set<String> constants() {
        final Set<String> out = new java.util.HashSet<>();
        for (final Field field : Glyphs.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && Modifier.isPublic(field.getModifiers())
                    && field.getType() == String.class) {
                try {
                    out.add((String) field.get(null));
                } catch (final IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return out;
    }
}

package eu.nordtal.s2.common.hud;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.pack.PackAdvances;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the shipped advance table against the pack it was exported from.
 *
 * <p>{@code nordtal/hud/bossbar-advances.properties} is a generated resource - the plugins size a
 * HUD pill from it, and they cannot read the pack themselves. The one way it goes wrong is
 * silently: an icon is redrawn, its rightmost column moves, nobody re-runs the export, and every
 * pill holding that icon is a pixel off. This test derives the table again from
 * {@code bossbar.json} and its PNGs with the client's own rule and fails if the resource is stale,
 * which turns "remember to re-run the script" into a red build.</p>
 */
class BossBarAdvancesTest {

    private static final String FONT = "resource-pack/src/assets/nordtal/font/bossbar.json";

    @Test
    @DisplayName("the shipped table is what the pack says today")
    void theResourceMatchesThePack() {
        final Map<Integer, Integer> pack = PackAdvances.of(FONT);
        final Map<Integer, Integer> shipped = BossBarAdvances.table();

        final List<String> differences = new ArrayList<>();
        pack.forEach((codePoint, advance) -> {
            final Integer have = shipped.get(codePoint);
            if (have == null) {
                differences.add("U+%X advances %d in the pack and is missing from the resource"
                        .formatted(codePoint, advance));
            } else if (!have.equals(advance)) {
                differences.add("U+%X advances %d in the pack and %d in the resource"
                        .formatted(codePoint, advance, have));
            }
        });
        shipped.keySet().stream().filter(codePoint -> !pack.containsKey(codePoint)).forEach(codePoint ->
                differences.add("U+%X is in the resource and not in the font".formatted(codePoint)));

        assertEquals(List.of(), differences,
                "common/src/main/resources/nordtal/hud/bossbar-advances.properties is stale - run"
                        + " resource-pack/tools/export_bossbar_advances.py");
    }

    @Test
    @DisplayName("every glyph a HUD line composes with has a known advance")
    void theCompositionGlyphsAreCovered() {
        for (final String glyph : List.of(Glyphs.BOSSBAR_BG_START, Glyphs.BOSSBAR_BG_END,
                Glyphs.BOSSBAR_BG_1, Glyphs.BOSSBAR_BG_128, Glyphs.BOSSBAR_SPACE_MINUS_1,
                Glyphs.BOSSBAR_SPACE_PLUS_3, Glyphs.BOSSBAR_ICON_DIM_OVERWORLD, Glyphs.BOSSBAR_ARROW_090_0)) {
            assertTrue(BossBarAdvances.covers(glyph.codePointAt(0)),
                    "U+%X has no advance, so a pill around it is sized as if it were a missing glyph"
                            .formatted(glyph.codePointAt(0)));
        }
        assertEquals(3, BossBarAdvances.advance(' '),
                "the space provider's +3 has to win over the ascii sheet's empty cell - the first"
                        + " provider to declare a code point is the one the client uses");
    }

    @Test
    @DisplayName("a character the font lacks is sized as the missing-glyph box")
    void anUnknownCharacterIsTheMissingGlyphBox() {
        assertEquals(BossBarAdvances.MISSING, BossBarAdvances.advance(0x1F600),
                "the client draws an undeclared code point as a five-pixel box advancing six; a"
                        + " pill around one has to be that wide, or the box pokes out of it");
        assertEquals(BossBarAdvances.MISSING, BossBarAdvances.width(new String(Character.toChars(0x1F600))));
    }

    @Test
    @DisplayName("a caps and segments advance exactly their drawn width plus one")
    void theBackgroundsAdvanceTheirWidthPlusOne() {
        assertEquals(BossBarWidth.CAP + 1, BossBarAdvances.advance(Glyphs.BOSSBAR_BG_START.codePointAt(0)));
        assertEquals(BossBarWidth.CAP + 1, BossBarAdvances.advance(Glyphs.BOSSBAR_BG_END.codePointAt(0)));
        assertEquals(129, BossBarAdvances.advance(Glyphs.BOSSBAR_BG_128.codePointAt(0)),
                "a 128px segment advances 129: that trailing pixel is why BossBarWidth steps back"
                        + " after every tile, and why the old bar had a seam at every boundary");
        assertEquals(-1, BossBarAdvances.advance(Glyphs.BOSSBAR_SPACE_MINUS_1.codePointAt(0)));
    }
}

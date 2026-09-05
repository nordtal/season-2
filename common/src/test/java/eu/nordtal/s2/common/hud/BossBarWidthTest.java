package eu.nordtal.s2.common.hud;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.pack.PackAdvances;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pill background, walked with the pack's own advances.
 *
 * <p>The old assertions restated the composition ("128 then 64 then 8 then the end cap"), which is
 * exactly the kind of test that stays green while the bar has a seam at every boundary. What is
 * worth asserting is the cursor: after {@code pill(inner)} it has to sit exactly
 * {@code CAP + inner + CAP} to the right, with every tile's left edge on the previous tile's right
 * edge. That is only checkable with the real advances, so this reads them from the pack.</p>
 */
class BossBarWidthTest {

    private static final Map<Integer, Integer> ADVANCES =
            PackAdvances.of("resource-pack/src/assets/nordtal/font/bossbar.json");

    @Test
    @DisplayName("a pill advances exactly cap + inner + cap, for every inner width")
    void thePillIsExactlyAsWideAsItSays() {
        for (int inner = 0; inner < 400; inner++) {
            assertEquals(BossBarWidth.CAP + inner + BossBarWidth.CAP, displacement(BossBarWidth.pill(inner)),
                    "pill(" + inner + ") leaves the cursor somewhere other than its right edge, so"
                            + " the content drawn next lands off the pill");
        }
    }

    @Test
    @DisplayName("the tiles butt up: no tile starts before the previous one ended, or after")
    void theTilesAreSeamless() {
        // Walk the composition tile by tile. A drawn glyph (positive advance) has to start exactly
        // where the last drawn glyph's pixels ended, which is one less than its advance.
        for (final int inner : new int[]{0, 1, 3, 7, 50, 182, 255, 300}) {
            int cursor = 0;
            int drawnEnd = 0;
            boolean first = true;
            for (final int codePoint : BossBarWidth.pill(inner).codePoints().toArray()) {
                final int advance = ADVANCES.get(codePoint);
                if (advance > 0) {
                    assertTrue(first || cursor == drawnEnd,
                            "in pill(" + inner + ") U+%X starts at %d but the previous tile ended at %d"
                                    .formatted(codePoint, cursor, drawnEnd));
                    drawnEnd = cursor + advance - 1;
                    first = false;
                }
                cursor += advance;
            }
        }
    }

    @Test
    @DisplayName("the body is the largest segments first and nothing after the last")
    void theBodyIsBinary() {
        // 200 = 128 + 64 + 8, each followed by its one-pixel step back.
        assertEquals(
                Glyphs.BOSSBAR_BG_128 + Glyphs.BOSSBAR_SPACE_MINUS_1
                        + Glyphs.BOSSBAR_BG_64 + Glyphs.BOSSBAR_SPACE_MINUS_1
                        + Glyphs.BOSSBAR_BG_8 + Glyphs.BOSSBAR_SPACE_MINUS_1,
                BossBarWidth.body(200));
        assertEquals("", BossBarWidth.body(0));
        assertEquals(200, displacement(BossBarWidth.body(200)));
    }

    @Test
    @DisplayName("a pill starts with the left cap and ends with the right one")
    void theCapsAreWhereTheyBelong() {
        final String pill = BossBarWidth.pill(10);
        assertTrue(pill.startsWith(Glyphs.BOSSBAR_BG_START));
        assertTrue(pill.endsWith(Glyphs.BOSSBAR_BG_END + Glyphs.BOSSBAR_SPACE_MINUS_1));
    }

    @Test
    void refusesNegativeWidth() {
        assertThrows(IllegalArgumentException.class, () -> BossBarWidth.body(-1));
        assertThrows(IllegalArgumentException.class, () -> BossBarWidth.pill(-1));
    }

    private static int displacement(final String composed) {
        return composed.codePoints().map(codePoint -> {
            final Integer advance = ADVANCES.get(codePoint);
            if (advance == null) {
                throw new AssertionError("U+%X is composed into a pill and bossbar.json does not"
                        .formatted(codePoint) + " declare it");
            }
            return advance;
        }).sum();
    }
}

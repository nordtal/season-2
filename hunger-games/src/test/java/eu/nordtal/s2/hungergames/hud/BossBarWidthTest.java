package eu.nordtal.s2.hungergames.hud;

import eu.nordtal.s2.common.Glyphs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossBarWidthTest {

    @Test
    void zeroWidthIsJustTheEndCap() {
        assertEquals(Glyphs.BOSSBAR_BG_END, BossBarWidth.compose(0));
    }

    @Test
    void composesFromTheLargestSegmentsFirst() {
        // 200 = 128 + 64 + 8
        final String composed = BossBarWidth.compose(200);
        assertEquals(Glyphs.BOSSBAR_BG_128 + Glyphs.BOSSBAR_BG_64 + Glyphs.BOSSBAR_BG_8 + Glyphs.BOSSBAR_BG_END,
                composed);
    }

    @Test
    void oddWidthUsesTheSmallestSegment() {
        // 1 = 1
        assertEquals(Glyphs.BOSSBAR_BG_1 + Glyphs.BOSSBAR_BG_END, BossBarWidth.compose(1));
    }

    @Test
    void alwaysEndsWithTheEndCap() {
        for (int width = 0; width < 300; width += 17) {
            assertTrue(BossBarWidth.compose(width).endsWith(Glyphs.BOSSBAR_BG_END));
        }
    }

    @Test
    void refusesNegativeWidth() {
        assertThrows(IllegalArgumentException.class, () -> BossBarWidth.compose(-1));
    }
}

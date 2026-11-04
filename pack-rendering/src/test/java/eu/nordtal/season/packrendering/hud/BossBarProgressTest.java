package eu.nordtal.season.packrendering.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.PackAdvances;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Walks the progress bar with the pack's own advances. */
class BossBarProgressTest {

    private static final Map<Integer, Integer> ADVANCES =
            PackAdvances.of(RepositoryRoot.packAssets() + "/nordtal/font/bossbar.json");

    @Test
    void theBarAdvancesLikeAGlyphOfTheTracksWidthAtEveryProgress() {
        for (int step = 0; step <= 200; step++) {
            final double progress = step / 200.0;
            assertEquals(
                    BossBarProgress.TRACK + 1,
                    displacement(BossBarProgress.bar(progress)),
                    "bar(" + progress + ") leaves the cursor off the track's end, so the distance beside it moves");
        }
    }

    @Test
    void theFillStartsInsideTheOutlineAndItsSegmentsButtUp() {
        for (final double progress : new double[] {0.01, 0.3, 0.5, 0.77, 1}) {
            int cursor = 0;
            int fillEnd = 1;
            for (final int codePoint :
                    BossBarProgress.bar(progress).codePoints().toArray()) {
                final int advance = ADVANCES.get(codePoint);
                if (isFill(codePoint)) {
                    assertEquals(fillEnd, cursor, "a fill segment of bar(" + progress + ") starts off the fill's end");
                    // A segment draws one column less than its advance.
                    fillEnd = cursor + advance - 1;
                }
                cursor += advance;
            }
            assertEquals(1 + BossBarProgress.filled(progress), fillEnd, "bar(" + progress + ") fills the wrong width");
        }
    }

    @Test
    void theFilledPixelsFollowTheProgressAndStayInsideTheTrack() {
        assertEquals(0, BossBarProgress.filled(0));
        assertEquals(0, BossBarProgress.filled(-0.5));
        assertEquals(0, BossBarProgress.filled(Double.NaN));
        assertEquals(49, BossBarProgress.filled(0.5));
        assertEquals(BossBarProgress.INNER, BossBarProgress.filled(1));
        assertEquals(BossBarProgress.INNER, BossBarProgress.filled(1.5));
        int last = 0;
        for (int step = 0; step <= 1000; step++) {
            final int filled = BossBarProgress.filled(step / 1000.0);
            assertTrue(filled >= last, "the fill shrinks as the progress grows at " + step);
            last = filled;
        }
    }

    @Test
    void anEmptyBarDrawsTheTrackAlone() {
        final String bar = BossBarProgress.bar(0);
        assertTrue(bar.codePoints().noneMatch(BossBarProgressTest::isFill), "an empty bar draws a fill");
    }

    private static boolean isFill(final int codePoint) {
        return Glyphs.BOSSBAR_RIDE_FILL_1.codePointAt(0) <= codePoint
                && codePoint <= Glyphs.BOSSBAR_RIDE_FILL_64.codePointAt(0);
    }

    private static int displacement(final String composed) {
        return composed.codePoints()
                .map(codePoint -> {
                    final Integer advance = ADVANCES.get(codePoint);
                    if (advance == null) {
                        throw new AssertionError("U+%X is composed into the bar and bossbar.json does not declare it"
                                .formatted(codePoint));
                    }
                    return advance;
                })
                .sum();
    }
}

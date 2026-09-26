package eu.nordtal.s2.common.hud;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.RepositoryRoot;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * Checks that every class composing a boss bar hands its name to {@link BossBarLine}, which names the font.
 *
 * A text test, since those classes need a running server. A missing font key does not fail to draw: it
 * draws the {@code minecraft:default} glyph at the same code point.
 */
class BossBarFontTest {

    /** Every source file in this repository that composes a boss bar name. */
    private static final String[] BOSS_BAR_SOURCES = {
        "smp/src/main/java/eu/nordtal/s2/smp/hud/SmpHud.java",
        "hunger-games/src/main/java/eu/nordtal/s2/hungergames/hud/HudRenderer.java",
    };

    @Test
    void everyBossBarNameTheTwoRenderersSetComesFromBossbarline() {
        for (final String source : BOSS_BAR_SOURCES) {
            final String text = read(source);
            // ".name(" with an argument sets a boss bar's name, unlike an enum's name().
            final java.util.regex.Matcher calls =
                    java.util.regex.Pattern.compile("\\.name\\((?!\\))").matcher(text);
            int seen = 0;
            while (calls.find()) {
                final int at = calls.start();
                seen++;
                assertTrue(
                        text.startsWith(".name(BossBarLine.render(", at),
                        source + " sets a boss bar name with something other than"
                                + " BossBarLine.render(...) - which is the one place the bossbar"
                                + " font is named and the shadow is turned off. A bare"
                                + " Component.text resolves the segments against minecraft:default,"
                                + " where U+E004 is the admin tag and not a background tile");
            }
            assertTrue(
                    seen > 0,
                    source + " sets no boss bar name at all - either the renderer"
                            + " moved or this test is scanning the wrong file");
        }
    }

    @Test
    void noBossBarRendererComposesABackgroundOfItsOwn() {
        for (final String source : BOSS_BAR_SOURCES) {
            final String text = read(source);
            assertTrue(
                    !text.contains("BOSSBAR_BG_") && !text.contains("BossBarWidth"),
                    source + " reaches for the background tiles itself; the pill is BossBarLine's"
                            + " and a second composition is the one that drifts");
        }
    }

    @Test
    void theFontKeysAreTheNamespacedIdsThePacksFontFilesActuallyLiveAt() {
        assertTrue(
                Files.isRegularFile(RepositoryRoot.resolve("resource-pack/src/assets/nordtal/font/bossbar.json")),
                "Glyphs.FONT_BOSSBAR is " + Glyphs.FONT_BOSSBAR + " but no font file exists at that id");
        assertTrue(
                Files.isRegularFile(RepositoryRoot.resolve("resource-pack/src/assets/nordtal/font/board.json")),
                "Glyphs.FONT_BOARD is " + Glyphs.FONT_BOARD + " but no font file exists at that id");
    }

    private static String read(final String relative) {
        return RepositoryRoot.read(relative);
    }
}

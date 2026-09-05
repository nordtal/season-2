package eu.nordtal.s2.common.hud;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.RepositoryRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that every class composing a boss bar hands its name to {@link BossBarLine}, the one
 * place that names {@link Glyphs#FONT_BOSSBAR} on the component it produces.
 *
 * <p><b>Why this is a text test rather than a component test.</b> The classes it guards are
 * Bukkit-facing - they take a {@code Player} and hand a {@code BossBar} to a running server - so
 * there is no JVM in this module that can construct one. What can be checked from here is the thing
 * that actually went wrong, and it went wrong in the source: on 2026-09-04 a real client showed the
 * SMP's boss bar drawing the admin nametag in the middle of its background, because
 * {@code Component.text(...)} named no font at all and {@link Glyphs#BOSSBAR_BG_4} and
 * {@link Glyphs#TAG_ADMIN} are both {@code U+E004} in their own fonts. A missing font key is not a
 * glyph that fails to draw; it is a glyph that draws something else, which is why nobody spotted it
 * in a screenshot for four days.</p>
 *
 * <p>The same collision exists for every other segment, and it will exist again for the board frame
 * the moment something starts drawing it - see {@link Glyphs#FONT_BOARD}.</p>
 */
class BossBarFontTest {

    /** Every source file in this repository that composes a boss bar name. */
    private static final String[] BOSS_BAR_SOURCES = {
            "smp/src/main/java/eu/nordtal/s2/smp/hud/SmpHud.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/hud/HudRenderer.java",
    };

    @Test
    @DisplayName("every boss bar name the two renderers set comes from BossBarLine")
    void renderersSetNamesThroughBossBarLine() {
        for (final String source : BOSS_BAR_SOURCES) {
            final String text = read(source);
            // ".name(" with an argument: a boss bar's name being set, as opposed to an enum's
            // name() being read, which the same two files also do.
            final java.util.regex.Matcher calls = java.util.regex.Pattern.compile("\\.name\\((?!\\))").matcher(text);
            int seen = 0;
            while (calls.find()) {
                final int at = calls.start();
                seen++;
                assertTrue(text.startsWith(".name(BossBarLine.render(", at),
                        source + " sets a boss bar name with something other than"
                                + " BossBarLine.render(...) - which is the one place the bossbar"
                                + " font is named and the shadow is turned off. A bare"
                                + " Component.text resolves the segments against minecraft:default,"
                                + " where U+E004 is the admin tag and not a background tile");
            }
            assertTrue(seen > 0, source + " sets no boss bar name at all - either the renderer"
                    + " moved or this test is scanning the wrong file");
        }
    }

    @Test
    @DisplayName("no boss bar renderer composes a background of its own")
    void renderersComposeNoBackground() {
        for (final String source : BOSS_BAR_SOURCES) {
            final String text = read(source);
            assertTrue(!text.contains("BOSSBAR_BG_") && !text.contains("BossBarWidth"),
                    source + " reaches for the background tiles itself; the pill is BossBarLine's"
                            + " and a second composition is the one that drifts");
        }
    }

    @Test
    @DisplayName("the font keys are the namespaced ids the pack's font files actually live at")
    void fontKeysMatchThePack() {
        assertTrue(Files.isRegularFile(RepositoryRoot.resolve("resource-pack/src/assets/nordtal/font/bossbar.json")),
                "Glyphs.FONT_BOSSBAR is " + Glyphs.FONT_BOSSBAR
                        + " but no font file exists at that id");
        assertTrue(Files.isRegularFile(RepositoryRoot.resolve("resource-pack/src/assets/nordtal/font/board.json")),
                "Glyphs.FONT_BOARD is " + Glyphs.FONT_BOARD
                        + " but no font file exists at that id");
    }

    private static String read(final String relative) {
        return RepositoryRoot.read(relative);
    }
}

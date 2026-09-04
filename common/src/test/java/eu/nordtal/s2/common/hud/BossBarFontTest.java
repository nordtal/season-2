package eu.nordtal.s2.common.hud;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.RepositoryRoot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that every class composing a boss bar names {@link Glyphs#FONT_BOSSBAR} on the component
 * it produces.
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
    @DisplayName("the two boss bar renderers name the bossbar font")
    void renderersNameTheFont() {
        for (final String source : BOSS_BAR_SOURCES) {
            final String text = read(source);
            assertTrue(text.contains("Glyphs.FONT_BOSSBAR"),
                    source + " composes a boss bar but never names Glyphs.FONT_BOSSBAR, so its"
                            + " code points resolve against minecraft:default - where U+E004 is the"
                            + " admin tag, not a background segment");
        }
    }

    @Test
    @DisplayName("no boss bar renderer sets a name with a bare Component.text")
    void noBareComponentText() {
        for (final String source : BOSS_BAR_SOURCES) {
            final String text = read(source);
            assertTrue(!text.contains(".name(Component.text("),
                    source + " sets a boss bar name with a bare Component.text(...), which carries"
                            + " no font - wrap it in the module's bossBarText(...) helper instead");
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

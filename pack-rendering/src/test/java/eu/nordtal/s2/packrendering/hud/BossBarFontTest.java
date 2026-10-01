package eu.nordtal.s2.packrendering.hud;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.RepositoryRoot;
import eu.nordtal.s2.packrendering.Glyphs;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * Checks that the fonts {@link BossBarLine} names exist in the pack.
 *
 * A missing font key does not fail to draw: it draws the {@code minecraft:default} glyph at the same code point.
 * That every boss bar is named through {@link BossBarLine} is {@code :architecture}'s rule.
 */
class BossBarFontTest {

    @Test
    void theFontKeysAreTheNamespacedIdsThePacksFontFilesActuallyLiveAt() {
        assertTrue(
                Files.isRegularFile(RepositoryRoot.resolve(RepositoryRoot.packAssets() + "/nordtal/font/bossbar.json")),
                "Glyphs.FONT_BOSSBAR is " + Glyphs.FONT_BOSSBAR + " but no font file exists at that id");
        assertTrue(
                Files.isRegularFile(RepositoryRoot.resolve(RepositoryRoot.packAssets() + "/nordtal/font/board.json")),
                "Glyphs.FONT_BOARD is " + Glyphs.FONT_BOARD + " but no font file exists at that id");
    }
}

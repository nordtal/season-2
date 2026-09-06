package eu.nordtal.s2.smp.farm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where the swap looks for a farm world that is not loaded.
 *
 * <p>One assertion, and it is here because the version without it was wrong for the whole season
 * and said so only in a warning line nobody would have read: the daily reset unloads the staged
 * world and then looks for its folder, so the unloaded case <b>is</b> the ordinary case. The old
 * path was built from the primary world's folder, which on Paper 26.2 already ends in
 * {@code dimensions/minecraft/overworld} - so the answer had that segment twice, matched nothing,
 * and every reset aborted with "today's world is untouched" (finding 125).</p>
 */
class FarmWorldSwapPathTest {

    @Test
    @DisplayName("an unloaded world sits under the level folder, not under the overworld's")
    void theStagingFolderIsUnderTheLevel() {
        assertEquals(Path.of("/data/nordtal/dimensions/minecraft/farm-next"),
                FarmWorldSwap.folderOf(Path.of("/data"), "nordtal", "farm-next"),
                "the staged world's folder is <container>/<level-name>/dimensions/minecraft/<world>");
        assertEquals(Path.of("/data/nordtal/dimensions/minecraft/farm"),
                FarmWorldSwap.folderOf(Path.of("/data"), "nordtal", "farm"));
    }
}

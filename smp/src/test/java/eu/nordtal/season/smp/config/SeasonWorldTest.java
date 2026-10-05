package eu.nordtal.season.smp.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.ComposeFile;
import eu.nordtal.season.common.RepositoryRoot;
import org.junit.jupiter.api.Test;

/**
 * What only the SMP's season world needs beyond the world name: a pinned seed and datapacks that land in it.
 *
 * A mismatch leaves the datapacks unloaded and the season world vanilla for good.
 */
class SeasonWorldTest {

    /** Nordtal is generated from the seed once and kept for the season, so the seed cannot be corrected later. */
    @Test
    void theSeasonWorldsSeedIsPinned() {
        final String seed = ComposeFile.get().service("smp").defaultedEnvironment("LEVEL_SEED");
        assertTrue(seed.matches("-?\\d+"), "LEVEL_SEED should default to a literal seed, not to '" + seed + "'");
    }

    /** The datapacks have to land in the world Paper actually generates, not beside it. */
    @Test
    void theDatapacksGoIntoThatSameWorld() {
        final String entrypoint = "deploy/minecraft/entrypoint.sh";
        final String script = RepositoryRoot.read(entrypoint);

        assertTrue(
                script.contains("set_property \"$file\" level-name \"$LEVEL_NAME\""),
                entrypoint + " no longer writes level-name into server.properties. Without it Paper"
                        + " keeps its own default and the datapacks below go into a folder nothing"
                        + " reads.");
        assertTrue(
                script.contains("fetch_datapacks \"$DATA/${LEVEL_NAME}/datapacks\""),
                entrypoint + " no longer fetches the datapacks into the level-name world.");
    }
}

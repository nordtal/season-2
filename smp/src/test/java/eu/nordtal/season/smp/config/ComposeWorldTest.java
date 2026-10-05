package eu.nordtal.season.smp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.ComposeFile;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.MemorySettingStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * That the world {@code compose.yml} tells Paper to generate is the world this plugin looks for.
 *
 * A mismatch leaves the datapacks unloaded and the season world vanilla for good.
 */
class ComposeWorldTest {

    @Test
    void composeGeneratesTheWorldTheSpecNames() throws Exception {
        final String composed = ComposeFile.get().service("smp").defaultedEnvironment("LEVEL_NAME");
        final String named = new MemorySettingStore()
                .checked("smp", Group.of("config", SmpSpec.class).checkedBy(SmpSettings::check), Map.of())
                .worldNordtal();

        assertEquals(
                named,
                composed,
                "compose.yml starts the SMP on level-name '" + composed + "' while config.yml's"
                        + " world-nordtal defaults to '" + named + "'. Paper would generate one"
                        + " world, put the datapacks in it, and the plugin would look for another.");
    }
}

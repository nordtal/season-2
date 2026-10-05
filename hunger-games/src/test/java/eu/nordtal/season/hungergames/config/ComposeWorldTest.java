package eu.nordtal.season.hungergames.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.ComposeFile;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.MemorySettingStore;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Checks that the world {@code compose.yml} tells Paper to generate is the world this plugin runs in.
 *
 * The plugin never creates its world and disables itself when the configured one is not loaded.
 */
class ComposeWorldTest {

    @Test
    void composeGeneratesTheWorldTheSpecNames() throws Exception {
        final String composed = ComposeFile.get().service("hunger-games").defaultedEnvironment("LEVEL_NAME");
        final String named = new MemorySettingStore()
                .checked(
                        "hunger-games",
                        Group.of("config", HungerGamesSpec.class).checkedBy(HungerGamesCheck::check),
                        Map.of())
                .worldName();

        assertEquals(
                named,
                composed,
                "compose.yml starts the event server on level-name '" + composed + "' while"
                        + " config.yml's world-name defaults to '" + named + "'. The plugin does not"
                        + " load a world of its own - it disables itself when that one is missing.");
    }
}

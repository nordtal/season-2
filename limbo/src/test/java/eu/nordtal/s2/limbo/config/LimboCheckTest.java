package eu.nordtal.s2.limbo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.settings.Group;
import eu.nordtal.s2.settings.MemorySettingStore;
import eu.nordtal.s2.settings.SettingsException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The fail-fast for {@code limbo}'s config group.
 *
 * A quietly wrong value here shows up as a black screen, which looks like a crash.
 */
class LimboCheckTest {

    private static final Group<LimboSpec> CONFIG =
            Group.of("config", LimboSpec.class).checkedBy(LimboCheck::check);

    private final MemorySettingStore store = new MemorySettingStore();

    private LimboSpec load(final Map<String, ?> values) throws SettingsException {
        return store.checked("limbo", CONFIG, values);
    }

    @Test
    void theDefaultsWork() throws Exception {
        final LimboSpec config = load(Map.of());

        assertEquals("limbo", config.worldName());
        assertEquals(64, config.spawnY());
        assertEquals(4, config.titleRefreshSeconds());
        assertTrue(config.blindness(), "blindness on is what makes the screen actually black");
    }

    @Test
    void aZeroTitleRefreshIsRejected() {
        // Zero means a task that never fires, and a title that expires into a blank black screen.
        final SettingsException error =
                assertThrows(SettingsException.class, () -> load(Map.of("title-refresh-seconds", 0)));
        assertTrue(error.getMessage().contains("title-refresh-seconds"), error.getMessage());
    }

    @Test
    void aBlankWorldNameIsRejected() {
        assertThrows(SettingsException.class, () -> load(Map.of("world-name", "")));
    }

    @Test
    void aSpawnHeightOutsideAnyBuildLimitIsRejected() {
        // Not physics, since the world is empty, but a height the server will not keep a player at.
        final SettingsException error = assertThrows(SettingsException.class, () -> load(Map.of("spawn-y", 5000)));
        assertTrue(error.getMessage().contains("spawn-y"), error.getMessage());
    }
}

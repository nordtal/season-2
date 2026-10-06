package eu.nordtal.season.stewardagent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.SettingsException;
import org.junit.jupiter.api.Test;

/** What the runs group refuses, and what it starts with; a refused change keeps the values in use. */
class AgentSettingsTest {

    private final MemorySettingStore store = new MemorySettingStore();

    private Setting<RunSpec> runs() throws SettingsException {
        return AgentSettings.runs(store.settings(AgentSettings.SERVICE));
    }

    @Test
    void aBackupWaitsHalfAnHourForTheServersByDefault() throws Exception {
        // A run ending FAILED raises an alert, so a half-hour outage is not silent.
        assertEquals(30, runs().get().backup().patienceMinutes());
    }

    @Test
    void aRefusedChangeKeepsTheValuesInUseAndSaysWhyOnTheGroup() throws Exception {
        final Setting<RunSpec> runs = runs();
        store.set(AgentSettings.SERVICE, "runs", "backup.patience-minutes", 0);

        assertThrows(SettingsException.class, runs::reload);

        assertEquals(30, runs.get().backup().patienceMinutes());
        assertTrue(store.group(AgentSettings.SERVICE, "runs").orElseThrow().problem() != null);
    }

    @Test
    void aDiskBudgetOfNothingIsRefusedRatherThanDeletingEveryArchive() throws Exception {
        final Setting<RunSpec> runs = runs();
        store.set(AgentSettings.SERVICE, "runs", "backup.budget-percent", 0);

        assertThrows(SettingsException.class, runs::reload);

        assertEquals(30, runs.get().backup().budgetPercent());
        assertEquals(10, runs.get().backup().keepFreePercent());
    }

    @Test
    void aSlugWhereAModrinthIdBelongsIsRefused() throws Exception {
        final Setting<RunSpec> runs = runs();
        store.set(AgentSettings.SERVICE, "runs", "packetevents-project", "packetevents");

        final SettingsException refused = assertThrows(SettingsException.class, runs::reload);

        final String message = String.valueOf(refused.getMessage()) + refused.getCause();
        assertTrue(message.contains("Modrinth project id"), message);
    }
}

package eu.nordtal.jcore.config.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SettingLabelsTest {

    @Test
    void aChangeOfCaseIsAWordBoundary() {
        // A change of case is a word boundary even without a separator, e.g. "serverUuid".
        assertEquals("Log failed requests", SettingLabels.of("logFailedRequests"));
        assertEquals("Background profiler", SettingLabels.of("backgroundProfiler"));
    }

    @Test
    void aKnownAcronymStaysUpperCase() {
        assertEquals("Server UUID", SettingLabels.of("serverUuid"));
        assertEquals("Base URL", SettingLabels.of("base-url"));
        assertEquals("Guild ID", SettingLabels.of("guild-id"));
        assertEquals("HTTP server", SettingLabels.of("HTTPServer"));
        assertEquals("Discord SRV", SettingLabels.of("discordSRV"));
    }

    @Test
    void anOrdinaryWordIsNotMistakenForAnAcronym() {
        assertEquals("Identity", SettingLabels.of("identity"));
        assertEquals("Stop services", SettingLabels.of("stop_services"));
        assertEquals("Enabled", SettingLabels.of("ENABLED"));
    }
}

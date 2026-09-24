package eu.nordtal.jcore.config.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingLabelsTest {

    @Test
    void aChangeOfCaseIsAWordBoundary() {
        // bStats and spark write camelCase keys and ship no schema; without this they read as
        // "Serveruuid" and "Logfailedrequests".
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

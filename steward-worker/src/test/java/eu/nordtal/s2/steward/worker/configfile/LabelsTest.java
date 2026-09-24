package eu.nordtal.s2.steward.worker.configfile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LabelsTest {

    @Test
    void aChangeOfCaseIsAWordBoundary() {
        // bStats and spark write camelCase keys and ship no schema; without this they read as
        // "Serveruuid" and "Logfailedrequests".
        assertEquals("Log failed requests", Labels.of("logFailedRequests"));
        assertEquals("Background profiler", Labels.of("backgroundProfiler"));
    }

    @Test
    void aKnownAcronymStaysUpperCase() {
        assertEquals("Server UUID", Labels.of("serverUuid"));
        assertEquals("Base URL", Labels.of("base-url"));
        assertEquals("Guild ID", Labels.of("guild-id"));
        assertEquals("HTTP server", Labels.of("HTTPServer"));
        assertEquals("Discord SRV", Labels.of("discordSRV"));
    }

    @Test
    void anOrdinaryWordIsNotMistakenForAnAcronym() {
        assertEquals("Identity", Labels.of("identity"));
        assertEquals("Stop services", Labels.of("stop_services"));
        assertEquals("Enabled", Labels.of("ENABLED"));
    }
}

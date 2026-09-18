package eu.nordtal.s2.steward.worker.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AlertLevel#of} against the same shapes {@code WorkerApi} actually builds - see that
 * class's javadoc for why it is a subset of {@code health.ts}'s {@code summarise} and not a port.
 */
class AlertLevelTest {

    @Test
    @DisplayName("an empty service list is a warning, not a green light")
    void emptyTableIsWarn() {
        final AlertLevel.Reading reading = AlertLevel.of(table(List.of()), List.of());
        assertEquals(AlertLevel.Level.WARN, reading.level());
        assertEquals("services", reading.subject());
    }

    @Test
    @DisplayName("a stopped service is red and links to that service")
    void stoppedServiceIsDown() {
        final Map<String, Object> table = table(List.of(
                service("smp", "exited", null, "UP_TO_DATE"),
                service("caddy", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()));

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("smp", reading.subject());
        assertEquals("/services/smp", reading.path());
    }

    @Test
    @DisplayName("running but unhealthy is red too")
    void unhealthyServiceIsDown() {
        final Map<String, Object> table = table(List.of(
                service("postgres", "running", "unhealthy", "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()));

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("postgres", reading.subject());
    }

    @Test
    @DisplayName("no backup at all is red, ranked below a stopped service")
    void noBackupAtAllIsDown() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of());

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("backups", reading.subject());
        assertEquals("/operations", reading.path());
    }

    @Test
    @DisplayName("a dump with no volume archive is its own sentence")
    void missingVolumeArchiveIsDown() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump()));

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("backups", reading.subject());
    }

    @Test
    @DisplayName("a missing database dump names itself, not the general word")
    void missingDatabaseDumpIsNamed() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(volume()));

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("database dump", reading.subject());
    }

    @Test
    @DisplayName("a .partial backup does not count as one that is there")
    void partialBackupsAreIgnored() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));
        final Map<String, Object> partialDump = dump();
        partialDump.put("partial", true);

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(partialDump, volume()));

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("database dump", reading.subject());
    }

    @Test
    @DisplayName("an outdated image is yellow when nothing else is wrong")
    void outdatedImageIsWarn() {
        final Map<String, Object> table = table(List.of(
                service("smp", "running", null, "OUTDATED"),
                service("caddy", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()));

        assertEquals(AlertLevel.Level.WARN, reading.level());
        assertEquals("smp", reading.subject());
        assertEquals("/operations", reading.path());
    }

    @Test
    @DisplayName("the registry not answering is yellow too")
    void driftNotReachedIsWarn() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UNKNOWN")));
        ((Map<String, Object>) table.get("drift")).put("reached", false);

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()));

        assertEquals(AlertLevel.Level.WARN, reading.level());
        assertEquals("registry", reading.subject());
    }

    @Test
    @DisplayName("nothing wrong is the OK reading, and nothing else answers it")
    void allClearIsOk() {
        final Map<String, Object> table = table(List.of(
                service("smp", "running", null, "UP_TO_DATE"),
                service("caddy", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()));

        assertEquals(AlertLevel.Reading.OK, reading);
    }

    @Test
    @DisplayName("a stopped service outranks a missing backup, exactly like a stopped service and a missing backup both being true")
    void downOutranksWarnEvenWhenBothArePresent() {
        final Map<String, Object> table = table(List.of(service("smp", "exited", null, "OUTDATED")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of());

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("smp", reading.subject());
    }

    private static Map<String, Object> table(final List<Map<String, Object>> services) {
        final Map<String, Object> table = new LinkedHashMap<>();
        table.put("services", new ArrayList<>(services));
        final Map<String, Object> drift = new LinkedHashMap<>();
        drift.put("reached", true);
        table.put("drift", drift);
        return table;
    }

    private static Map<String, Object> service(final String name, final String state,
                                               final String health, final String drift) {
        final Map<String, Object> service = new LinkedHashMap<>();
        service.put("service", name);
        service.put("state", state);
        if (health != null) {
            service.put("health", health);
        }
        service.put("drift", drift);
        return service;
    }

    private static Map<String, Object> dump() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "nordtal-20260918T041500Z.dump");
        row.put("partial", false);
        return row;
    }

    private static Map<String, Object> volume() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "mc-smp-data-20260918T041500Z.tar.zst");
        row.put("partial", false);
        return row;
    }
}

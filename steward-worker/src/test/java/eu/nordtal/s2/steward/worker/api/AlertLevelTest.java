package eu.nordtal.s2.steward.worker.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    @DisplayName("every trigger is listed, not only the worst one")
    void everyTriggerIsListed() {
        final Map<String, Object> table = table(List.of(
                service("smp", "exited", null, "OUTDATED"),
                service("caddy", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of());

        // Before steward/98's review this returned the first branch that fired and stopped, which
        // is why this assertion exists: a push is now per type and switchable per account, so an
        // image drift hidden behind a stopped service is a notification nobody can ever receive.
        assertEquals(List.of(AlertLevel.Kind.SERVICE, AlertLevel.Kind.BACKUP, AlertLevel.Kind.DRIFT),
                reading.triggers().stream().map(AlertLevel.Trigger::kind).toList(),
                "a reading with three different things wrong did not report all three");
        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("smp", reading.subject(), "the summary is no longer the worst trigger");
    }

    @Test
    @DisplayName("the two percentages come out of the host's own numbers, unjudged")
    void percentagesAreMeasuredAndNotJudged() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));
        final Map<String, Object> host = new LinkedHashMap<>();
        host.put("diskUsedBytes", 90L);
        host.put("diskTotalBytes", 200L);
        host.put("memoryTotalBytes", 1000L);
        host.put("memoryAvailableBytes", 250L);

        final AlertLevel.Reading reading =
                AlertLevel.of(table, List.of(dump(), volume()), host, Instant.now());

        assertEquals(45.0, reading.diskPercent(), 0.0001);
        // Used is total minus AVAILABLE, the same arithmetic health.ts does on the same two fields.
        assertEquals(75.0, reading.memoryPercent(), 0.0001);
        assertEquals(AlertLevel.Level.OK, reading.level(),
                "a disk at 45 % was turned into an alert in the process that has no threshold");
    }

    @Test
    @DisplayName("a host that could not be read leaves both percentages absent, never zero")
    void anUnreadableHostIsNotZeroPercent() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));
        final Map<String, Object> host = new LinkedHashMap<>();
        host.put("unreadable", "could not read /proc: no such file");

        final AlertLevel.Reading reading =
                AlertLevel.of(table, List.of(dump(), volume()), host, Instant.now());

        assertNull(reading.diskPercent(), "an unreadable disk was reported as a measured 0 %");
        assertNull(reading.memoryPercent(), "unreadable memory was reported as a measured 0 %");
    }

    @Test
    @DisplayName("the backup age is the MOST NEGLECTED series, not the newest file in the directory")
    void backupAgeIsPerSeries() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));
        final Instant now = Instant.parse("2026-09-19T12:00:00Z");
        final List<Map<String, Object>> archives = List.of(
                at(dump(), now.minus(Duration.ofHours(2))),
                at(volume(), now.minus(Duration.ofHours(50))),
                at(named("mc-limbo-data-20260919T100000Z.tar.zst"), now.minus(Duration.ofMinutes(30))));

        final AlertLevel.Reading reading = AlertLevel.of(table, archives, Map.of(), now);

        // health.ts's own argument, held to one number: sixteen archives from tonight and a world
        // from three weeks ago make "the newest backup" minutes old, and the per-volume failure
        // that a missing mount produces hides behind the small volumes that succeeded.
        assertEquals(50.0, reading.backupAgeHours(), 0.0001,
                "the age answered was the newest file in the directory, not the oldest series");
    }

    @Test
    @DisplayName("no finished backup at all has no age - it is already a trigger in words")
    void noBackupHasNoAge() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading =
                AlertLevel.of(table, List.of(), Map.of(), Instant.now());

        assertNull(reading.backupAgeHours(),
                "an age was invented for a directory with nothing finished in it");
        assertEquals(AlertLevel.Kind.BACKUP, reading.triggers().getFirst().kind());
    }

    private static Map<String, Object> at(final Map<String, Object> row, final Instant modified) {
        row.put("modified", modified.toString());
        return row;
    }

    private static Map<String, Object> named(final String name) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("partial", false);
        return row;
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

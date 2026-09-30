package eu.nordtal.s2.steward.worker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link AlertLevel#of} against the same shapes {@code WorkerApi} actually builds.
 *
 * See that class's javadoc for why it is a subset of {@code health.ts}'s {@code summarise} and not a port.
 */
class AlertLevelTest {

    @Test
    void anEmptyServiceListIsAWarningNotAGreenLight() {
        final AlertLevel.Reading reading = AlertLevel.of(table(List.of()), List.of(), Map.of(), Instant.now());
        assertEquals(AlertLevel.Level.WARN, reading.level());
        assertEquals("services", reading.subject());
    }

    @Test
    void aStoppedServiceIsRedAndLinksToThatService() {
        final Map<String, Object> table = table(
                List.of(service("smp", "exited", null, "UP_TO_DATE"), service("caddy", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()), Map.of(), Instant.now());

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("smp", reading.subject());
        assertEquals("/services/smp", reading.path());
    }

    @Test
    void runningButUnhealthyIsRedToo() {
        final Map<String, Object> table = table(List.of(service("postgres", "running", "unhealthy", "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()), Map.of(), Instant.now());

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("postgres", reading.subject());
    }

    @Test
    void noBackupAtAllIsRedRankedBelowAStoppedService() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(), Map.of(), Instant.now());

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("backups", reading.subject());
        assertEquals("/operations/backups", reading.path());
    }

    @Test
    void aDumpWithNoVolumeArchiveIsItsOwnSentence() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump()), Map.of(), Instant.now());

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("backups", reading.subject());
    }

    @Test
    void aMissingDatabaseDumpNamesItselfNotTheGeneralWord() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(volume()), Map.of(), Instant.now());

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("database dump", reading.subject());
    }

    @Test
    void aPartialBackupDoesNotCountAsOneThatIsThere() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));
        final Map<String, Object> partialDump = dump();
        partialDump.put("partial", true);

        final AlertLevel.Reading reading =
                AlertLevel.of(table, List.of(partialDump, volume()), Map.of(), Instant.now());

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("database dump", reading.subject());
    }

    @Test
    void anOutdatedImageIsYellowWhenNothingElseIsWrong() {
        final Map<String, Object> table = table(
                List.of(service("smp", "running", null, "OUTDATED"), service("caddy", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()), Map.of(), Instant.now());

        assertEquals(AlertLevel.Level.WARN, reading.level());
        assertEquals("smp", reading.subject());
        assertEquals("/operations/updates", reading.path());
    }

    @Test
    void theRegistryNotAnsweringIsYellowToo() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UNKNOWN")));
        ((Map<String, Object>) table.get("drift")).put("reached", false);

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()), Map.of(), Instant.now());

        assertEquals(AlertLevel.Level.WARN, reading.level());
        assertEquals("registry", reading.subject());
    }

    @Test
    void nothingWrongIsTheOkReadingAndNothingElseAnswersIt() {
        final Map<String, Object> table = table(List.of(
                service("smp", "running", null, "UP_TO_DATE"), service("caddy", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()), Map.of(), Instant.now());

        assertEquals(AlertLevel.Reading.OK, reading);
    }

    @Test
    void downOutranksWarnEvenWhenBothArePresent() {
        final Map<String, Object> table = table(List.of(service("smp", "exited", null, "OUTDATED")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(), Map.of(), Instant.now());

        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("smp", reading.subject());
    }

    @Test
    void everyTriggerIsListedNotOnlyTheWorstOne() {
        final Map<String, Object> table = table(
                List.of(service("smp", "exited", null, "OUTDATED"), service("caddy", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(), Map.of(), Instant.now());

        // A push is per type and switchable per account, so a drift hidden behind a stopped service must still surface.
        assertEquals(
                List.of(AlertLevel.Kind.SERVICE, AlertLevel.Kind.BACKUP, AlertLevel.Kind.DRIFT),
                reading.triggers().stream().map(AlertLevel.Trigger::kind).toList(),
                "a reading with three different things wrong did not report all three");
        assertEquals(AlertLevel.Level.DOWN, reading.level());
        assertEquals("smp", reading.subject(), "the summary is no longer the worst trigger");
    }

    @Test
    void theTwoPercentagesComeOutOfTheHostsOwnNumbersUnjudged() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));
        final Map<String, Object> host = new LinkedHashMap<>();
        host.put("diskUsedBytes", 90L);
        host.put("diskTotalBytes", 200L);
        host.put("memoryTotalBytes", 1000L);
        host.put("memoryAvailableBytes", 250L);

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()), host, Instant.now());

        assertEquals(45.0, reading.diskPercent(), 0.0001);
        // Used is total minus AVAILABLE, the same arithmetic health.ts does on the same two fields.
        assertEquals(75.0, reading.memoryPercent(), 0.0001);
        assertEquals(
                AlertLevel.Level.OK,
                reading.level(),
                "a disk at 45 % was turned into an alert in the process that has no threshold");
    }

    @Test
    void aHostThatCouldNotBeReadLeavesBothPercentagesAbsentNeverZero() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));
        final Map<String, Object> host = new LinkedHashMap<>();
        host.put("unreadable", "could not read /proc: no such file");

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(dump(), volume()), host, Instant.now());

        assertNull(reading.diskPercent(), "an unreadable disk was reported as a measured 0 %");
        assertNull(reading.memoryPercent(), "unreadable memory was reported as a measured 0 %");
    }

    @Test
    void theBackupAgeIsTheMostNeglectedSeriesNotTheNewestFileInTheDirectory() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));
        final Instant now = Instant.parse("2026-09-19T12:00:00Z");
        final List<Map<String, Object>> archives = List.of(
                at(dump(), now.minus(Duration.ofHours(2))),
                at(volume(), now.minus(Duration.ofHours(50))),
                at(named("mc-limbo-data-20260919T100000Z.tar.zst"), now.minus(Duration.ofMinutes(30))));

        final AlertLevel.Reading reading = AlertLevel.of(table, archives, Map.of(), now);

        // A recent small volume must not mask a stale large one: "the newest backup" alone hides a missing mount.
        assertEquals(
                50.0,
                reading.backupAgeHours(),
                0.0001,
                "the age answered was the newest file in the directory, not the oldest series");
    }

    @Test
    void noFinishedBackupAtAllHasNoAgeItIsAlreadyATriggerInWords() {
        final Map<String, Object> table = table(List.of(service("smp", "running", null, "UP_TO_DATE")));

        final AlertLevel.Reading reading = AlertLevel.of(table, List.of(), Map.of(), Instant.now());

        assertNull(reading.backupAgeHours(), "an age was invented for a directory with nothing finished in it");
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

    private static Map<String, Object> service(
            final String name, final String state, final String health, final String drift) {
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

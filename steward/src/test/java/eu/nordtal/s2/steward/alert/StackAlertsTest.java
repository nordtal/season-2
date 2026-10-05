package eu.nordtal.s2.steward.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.steward.AdminPlain;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The one judgement of the stack: what is wrong, how badly, and what a meant stop is not. */
class StackAlertsTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Thresholds THRESHOLDS = new Thresholds(85, 90, 36);
    private static final long GIB = 1L << 30;

    private static StackReading.Service service(final String name, final String state) {
        return new StackReading.Service(name, state, null, false, false);
    }

    private static List<StackReading.Archive> freshBackups() {
        return List.of(archive("db-20261002T110000Z.dump", 1), archive("smp-world-20261002T110000Z.tar.zst", 1));
    }

    /** An archive the offsite repository also has. */
    private static StackReading.Archive archive(final String name, final long hoursOld) {
        return new StackReading.Archive(name, NOW.minus(Duration.ofHours(hoursOld)), false, true);
    }

    /** An archive only this host's disk has. */
    private static StackReading.Archive local(final String name, final long hoursOld) {
        return new StackReading.Archive(name, NOW.minus(Duration.ofHours(hoursOld)), false, false);
    }

    private static StackReading.Host host(final long diskUsedGib, final long memoryAvailableGib) {
        return new StackReading.Host(diskUsedGib * GIB, 100 * GIB, memoryAvailableGib * GIB, 100 * GIB);
    }

    private static List<Alert> of(
            final List<StackReading.Service> services,
            final @Nullable String registryProblem,
            final List<StackReading.Archive> archives,
            final StackReading.@Nullable Host host) {
        return StackAlerts.of(new StackReading(services, registryProblem, archives, host), THRESHOLDS, NOW);
    }

    private static List<Alert> withServices(final StackReading.Service... services) {
        return of(List.of(services), null, freshBackups(), host(10, 50));
    }

    private static List<String> titles(final List<Alert> alerts) {
        return alerts.stream().map(alert -> AdminPlain.of(alert.title())).toList();
    }

    @Test
    void aHealthyStackHasNothingToSay() {
        assertEquals(List.of(), withServices(service("smp", "running")));
    }

    @Test
    void aStoppedServiceIsRedAndAMeantStopIsNot() {
        assertEquals(
                List.of("smp is not running"),
                titles(withServices(
                        service("smp", "exited"),
                        new StackReading.Service("hunger-games", "exited", null, true, false))));
        final Alert down = withServices(service("smp", "exited")).getFirst();
        assertEquals(AlertType.SERVICE, down.type());
        assertEquals(Alert.Level.DOWN, down.level());
        assertEquals("/services/smp", down.path());
    }

    @Test
    void anUnhealthyServiceIsRedEvenWhenItsStopWouldBeMeant() {
        assertEquals(
                List.of("smp is unhealthy"),
                titles(withServices(new StackReading.Service("smp", "running", "unhealthy", true, false))));
    }

    @Test
    void noServiceAtAllIsYellow() {
        final Alert none = withServices().getFirst();
        assertEquals(Alert.Level.WARN, none.level());
        assertEquals("services", none.subject());
    }

    @Test
    void anOlderImageAndAnUnansweredRegistryAreYellow() {
        final List<Alert> alerts = of(
                List.of(
                        new StackReading.Service("smp", "running", null, false, true),
                        new StackReading.Service("proxy", "running", null, false, true)),
                "timed out",
                freshBackups(),
                null);
        assertEquals(
                List.of("smp and proxy run an older image than the registry has", "The images were not compared"),
                titles(alerts));
        assertTrue(alerts.stream().allMatch(alert -> alert.type() == AlertType.DRIFT));
        assertEquals(List.of("timed out"), AdminPlain.of(alerts.get(1).detail()));
    }

    @Test
    void bothKindsOfBackupMustExist() {
        final StackReading.Service smp = service("smp", "running");
        assertEquals(List.of("There is no finished backup"), titles(of(List.of(smp), null, List.of(), null)));
        assertEquals(
                List.of("There is no finished backup"),
                titles(of(
                        List.of(smp),
                        null,
                        List.of(new StackReading.Archive("db-20261002T110000Z.dump.partial", NOW, true, false)),
                        null)));
        assertEquals(
                List.of("There is no database dump"),
                titles(of(List.of(smp), null, List.of(archive("smp-world-20261002T110000Z.tar.zst", 1)), null)));
        assertEquals(
                List.of("There is no volume archive"),
                titles(of(List.of(smp), null, List.of(archive("db-20261002T110000Z.dump", 1)), null)));
    }

    @Test
    void oneStaleSeriesIsNamedEvenWhenTheOthersAreFresh() {
        final List<StackReading.Archive> archives = new ArrayList<>(freshBackups());
        archives.add(archive("limbo-world-20260930T110000Z.tar.zst", 37));
        archives.add(archive("limbo-world-20260929T110000Z.tar.zst", 61));
        final List<Alert> alerts = of(List.of(service("smp", "running")), null, archives, null);
        assertEquals(List.of("The newest archive of limbo-world is 37 hours old"), titles(alerts));
        assertEquals("limbo-world", alerts.getFirst().subject());
    }

    @Test
    void backupsNeverCopiedOffTheHostAreYellow() {
        final List<Alert> alerts = of(
                List.of(service("smp", "running")),
                null,
                List.of(local("db-20261002T110000Z.dump", 1), local("smp-world-20261002T110000Z.tar.zst", 1)),
                null);
        assertEquals(List.of("No backup has been copied off this host"), titles(alerts));
        assertEquals(Alert.Level.WARN, alerts.getFirst().level());
        assertEquals(AlertType.BACKUP, alerts.getFirst().type());
    }

    @Test
    void anOldCopyOffTheHostIsRedWhileTheArchivesOnTheDiskAreFresh() {
        final List<Alert> alerts = of(
                List.of(service("smp", "running")),
                null,
                List.of(
                        local("db-20261002T110000Z.dump", 1),
                        local("smp-world-20261002T110000Z.tar.zst", 1),
                        archive("db-20261001T020000Z.dump", 40)),
                null);
        assertEquals(List.of("The newest backup copied off this host is 40 hours old"), titles(alerts));
        assertEquals(Alert.Level.DOWN, alerts.getFirst().level());
    }

    @Test
    void aBackupExactlyAtThePermittedAgeIsStillFine() {
        final List<StackReading.Archive> archives =
                List.of(archive("db-20261002T110000Z.dump", 36), archive("smp-world-20261002T110000Z.tar.zst", 36));
        assertEquals(List.of(), of(List.of(service("smp", "running")), null, archives, null));
    }

    @Test
    void diskAndMemoryAreYellowFromTheirThresholdOn() {
        assertEquals(
                List.of("The disk is 85 % full", "Memory is 90 % used"),
                titles(of(List.of(service("smp", "running")), null, freshBackups(), host(85, 10))));
        assertEquals(List.of(), of(List.of(service("smp", "running")), null, freshBackups(), host(84, 11)));
    }

    @Test
    void redComesBeforeYellow() {
        final List<Alert> alerts = of(
                List.of(new StackReading.Service("smp", "running", null, false, true), service("proxy", "exited")),
                null,
                freshBackups(),
                host(95, 50));
        assertEquals(
                List.of(Alert.Level.DOWN, Alert.Level.WARN, Alert.Level.WARN),
                alerts.stream().map(Alert::level).toList());
    }
}

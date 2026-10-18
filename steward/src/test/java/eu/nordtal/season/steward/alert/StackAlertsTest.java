package eu.nordtal.season.steward.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.Measure;
import eu.nordtal.season.database.alert.Measure.Gauge;
import eu.nordtal.season.steward.AdminPlain;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Tests the judgement of the stack: what is wrong, how badly, and that an intended stop raises nothing. */
class StackAlertsTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Thresholds THRESHOLDS = new Thresholds(85, 90, 36);
    private static final long GIB = 1L << 30;
    private static final Map<String, List<String>> MOUNTS =
            Map.of("nordtal-s2_mc-limbo", List.of("limbo"), "limbo-world", List.of("limbo"));

    private static StackReading.Service service(final String name, final String state) {
        return new StackReading.Service(name, state, null, StackReading.Purpose.SERVES, false);
    }

    private static List<StackReading.Archive> freshBackups() {
        return List.of(archive("db-20261002T110000Z.dump", 1), archive("smp-world-20261002T110000Z.tar.zst", 1));
    }

    /** Builds an archive that the offsite repository also has. */
    private static StackReading.Archive archive(final String name, final long hoursOld) {
        return new StackReading.Archive(name, NOW.minus(Duration.ofHours(hoursOld)), false, true);
    }

    /** Builds an archive that only the disk of this host has. */
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
        return StackAlerts.of(
                new StackReading(services, registryProblem, archives, host, volumesOf(archives), MOUNTS, null),
                THRESHOLDS,
                NOW);
    }

    /** Builds the backup set that contains every volume with an archive. */
    private static List<String> volumesOf(final List<StackReading.Archive> archives) {
        return archives.stream()
                .map(StackReading.Archive::name)
                .filter(name -> name.endsWith(".tar.zst"))
                .map(name -> name.substring(0, name.lastIndexOf('-')))
                .distinct()
                .toList();
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
                        new StackReading.Service("hunger-games", "exited", null, StackReading.Purpose.RESTS, false))));
        final Alert down = withServices(service("smp", "exited")).getFirst();
        assertEquals(AlertType.SERVICE, down.type());
        assertEquals(Alert.Level.DOWN, down.level());
        assertEquals("/services/smp", down.path());
    }

    @Test
    void aOneShotThatExitedCleanlyIsFineAndOneThatFailedIsRedWithItsCode() {
        final StackReading.Service clean =
                new StackReading.Service("migrate", "exited", null, StackReading.Purpose.ONCE, 0, false);
        final StackReading.Service failed =
                new StackReading.Service("migrate", "exited", null, StackReading.Purpose.ONCE, 1, false);

        assertEquals(List.of(), withServices(clean));
        final Alert down = withServices(failed).getFirst();
        assertEquals(Alert.Level.DOWN, down.level());
        assertEquals("migrate exited with code 1", AdminPlain.of(down.title()));
    }

    @Test
    void aOneShotWhoseExitNobodyReadIsNotCalledFailed() {
        assertEquals(
                List.of(),
                withServices(new StackReading.Service("migrate", "created", null, StackReading.Purpose.ONCE, false)));
    }

    /** An update run stops the services it replaces. Nothing is raised while it moves them. */
    @Test
    void aServiceARunIsMovingIsNeverRedStoppedOrUnhealthy() {
        assertEquals(
                List.of(),
                withServices(
                        new StackReading.Service("proxy", "exited", null, StackReading.Purpose.MOVING, false),
                        new StackReading.Service("limbo", "running", "unhealthy", StackReading.Purpose.MOVING, false)));
    }

    @Test
    void anUnhealthyServiceIsRedEvenWhenItsStopWouldBeMeant() {
        assertEquals(
                List.of("smp is unhealthy"),
                titles(withServices(
                        new StackReading.Service("smp", "running", "unhealthy", StackReading.Purpose.RESTS, false))));
    }

    @Test
    void aServiceDockerRestartedWithinTheHourIsYellowUntilItHasRunAnHour() {
        final List<Alert> looping = withServices(new StackReading.Service(
                "smp", "running", "starting", StackReading.Purpose.SERVES, null, false, 3, NOW.minusSeconds(40)));
        assertEquals(List.of("smp restarted 3 times since it was started"), titles(looping));
        assertEquals(Alert.Level.WARN, looping.getFirst().level());
        assertEquals(
                List.of(Measure.of(Gauge.RESTARTS, "smp", 3)),
                looping.getFirst().measured());

        assertEquals(
                List.of(),
                withServices(new StackReading.Service(
                        "smp",
                        "running",
                        "healthy",
                        StackReading.Purpose.SERVES,
                        null,
                        false,
                        1,
                        NOW.minus(Duration.ofHours(2)))),
                "one crash two hours ago that Docker recovered from is history, not a loop");
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
                        new StackReading.Service("smp", "running", null, StackReading.Purpose.SERVES, true),
                        new StackReading.Service("proxy", "running", null, StackReading.Purpose.SERVES, true)),
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
    void aPackThatDoesNotAnswerIsRedAndNamesItsUrl() {
        final String url = "https://github.com/nordtal/season-2/releases/download/v0.17.0/pack.zip";
        final List<Alert> alerts = StackAlerts.of(
                new StackReading(
                        List.of(service("proxy", "running")),
                        null,
                        freshBackups(),
                        null,
                        volumesOf(freshBackups()),
                        MOUNTS,
                        url),
                THRESHOLDS,
                NOW);
        assertEquals(List.of("The resource pack does not answer"), titles(alerts));
        assertEquals(AlertType.PACK, alerts.getFirst().type());
        assertEquals(Alert.Level.DOWN, alerts.getFirst().level());
        assertTrue(AdminPlain.of(alerts.getFirst().detail()).getFirst().contains(url));
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
        final List<Alert> noArchive = StackAlerts.of(
                new StackReading(
                        List.of(smp),
                        null,
                        List.of(archive("db-20261002T110000Z.dump", 1)),
                        null,
                        List.of("nordtal-s2_mc-smp", "nordtal-s2_mc-limbo"),
                        MOUNTS,
                        null),
                THRESHOLDS,
                NOW);
        assertEquals(
                List.of("mc-smp: there is no archive yet", "mc-limbo: there is no archive yet"), titles(noArchive));
        assertEquals(Alert.Level.DOWN, noArchive.getFirst().level());
    }

    @Test
    void aSeriesOfAVolumeThatLeftTheBackupRaisesNoAlertHoweverOldItsNewestArchiveIs() {
        final List<StackReading.Archive> archives = new ArrayList<>(freshBackups());
        archives.add(archive("nordtal-s2_mc-proxy-20260915T110000Z.tar.zst", 400));
        final List<Alert> alerts = StackAlerts.of(
                new StackReading(
                        List.of(service("smp", "running")), null, archives, null, List.of("smp-world"), MOUNTS, null),
                THRESHOLDS,
                NOW);
        assertEquals(List.of(), alerts);
    }

    @Test
    void oneStaleSeriesIsNamedEvenWhenTheOthersAreFresh() {
        final List<StackReading.Archive> archives = new ArrayList<>(freshBackups());
        archives.add(archive("limbo-world-20260930T110000Z.tar.zst", 37));
        archives.add(archive("limbo-world-20260929T110000Z.tar.zst", 61));
        final List<Alert> alerts = of(List.of(service("smp", "running")), null, archives, null);
        assertEquals(List.of("limbo-world: the newest archive is 37 hours old"), titles(alerts));
        assertEquals("limbo-world", alerts.getFirst().subject());
    }

    @Test
    void aStaleArchiveIsYellowWhileItsServiceRunsAndNamedWithoutTheProjectPrefix() {
        final List<StackReading.Archive> archives = new ArrayList<>(freshBackups());
        archives.add(archive("nordtal-s2_mc-limbo-20260930T110000Z.tar.zst", 37));
        final List<Alert> alerts = of(List.of(service("limbo", "running")), null, archives, null);
        assertEquals(List.of("mc-limbo: the newest archive is 37 hours old"), titles(alerts));
        assertEquals("mc-limbo", alerts.getFirst().subject());
        assertEquals(Alert.Level.WARN, alerts.getFirst().level());
    }

    @Test
    void aStaleArchiveIsRedOnceAServiceOnItsVolumeIsDownButNotForAMeantStop() {
        final List<StackReading.Archive> archives = new ArrayList<>(freshBackups());
        archives.add(archive("nordtal-s2_mc-limbo-20260930T110000Z.tar.zst", 37));
        final List<Alert> down = of(List.of(service("limbo", "exited")), null, archives, null);
        assertEquals(
                List.of(Alert.Level.DOWN, Alert.Level.DOWN),
                down.stream().map(Alert::level).toList());
        assertEquals("mc-limbo", down.get(1).subject());
        final List<Alert> held = of(
                List.of(new StackReading.Service("limbo", "exited", null, StackReading.Purpose.RESTS, false)),
                null,
                archives,
                null);
        assertEquals(List.of(Alert.Level.WARN), held.stream().map(Alert::level).toList());
    }

    @Test
    void aStaleDatabaseDumpStaysRed() {
        final List<Alert> alerts = of(
                List.of(service("smp", "running")),
                null,
                List.of(archive("db-20260930T110000Z.dump", 37), archive("smp-world-20261002T110000Z.tar.zst", 1)),
                null);
        assertEquals(List.of("The newest database dump is 37 hours old"), titles(alerts));
        assertEquals(Alert.Level.DOWN, alerts.getFirst().level());
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
                List.of(
                        new StackReading.Service("smp", "running", null, StackReading.Purpose.SERVES, true),
                        service("proxy", "exited")),
                null,
                freshBackups(),
                host(95, 50));
        assertEquals(
                List.of(Alert.Level.DOWN, Alert.Level.WARN, Alert.Level.WARN),
                alerts.stream().map(Alert::level).toList());
    }

    @Test
    void everyAlertKeepsWhatItMeasuredAsTypedValues() {
        final List<Alert> alerts = of(
                List.of(
                        service("smp", "exited"),
                        new StackReading.Service("proxy", "running", "unhealthy", StackReading.Purpose.SERVES, false),
                        new StackReading.Service("migrate", "exited", null, StackReading.Purpose.ONCE, 3, false),
                        new StackReading.Service("limbo", "running", null, StackReading.Purpose.SERVES, true)),
                null,
                List.of(
                        archive("db-20260930T110000Z.dump", 40),
                        archive("nordtal-s2_smp-world-20261002T110000Z.tar.zst", 37)),
                host(90, 4));

        assertEquals(
                List.of(
                        List.of(Measure.state(Gauge.CONTAINER_STATE, "smp", "exited")),
                        List.of(Measure.state(Gauge.HEALTH, "proxy", "unhealthy")),
                        List.of(Measure.of(Gauge.EXIT_CODE, "migrate", 3)),
                        List.of(Measure.against(Gauge.BACKUP_AGE_HOURS, "offsite copy", 37, 36)),
                        List.of(Measure.against(Gauge.BACKUP_AGE_HOURS, "database dump", 40, 36)),
                        List.of(Measure.state(Gauge.OLDER_IMAGE, "limbo", null)),
                        List.of(Measure.against(Gauge.BACKUP_AGE_HOURS, "smp-world", 37, 36)),
                        List.of(Measure.against(Gauge.DISK_PERCENT, "disk", 90, 85)),
                        List.of(Measure.against(Gauge.MEMORY_PERCENT, "memory", 96, 90))),
                alerts.stream().map(Alert::measured).toList());
    }

    @Test
    void anAlertAboutSomethingMissingMeasuredNothing() {
        assertEquals(
                List.of(List.of(), List.of()),
                of(List.of(service("smp", "running")), "timed out", List.of(), null).stream()
                        .map(Alert::measured)
                        .toList());
    }
}

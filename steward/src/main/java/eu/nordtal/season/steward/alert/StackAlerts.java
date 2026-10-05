package eu.nordtal.season.steward.alert;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.messages.MessageRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Holds one reading of the stack against the thresholds: every measured alert there is right now, red first.
 *
 * This is the only place that decides whether a service, a backup, the disk or the memory is in trouble.
 */
public final class StackAlerts {

    /** {@code TarSnapshots}' names, as {@code backup-name.ts} reads them. */
    private static final Pattern VOLUME_ARCHIVE = Pattern.compile("^(.+)-(\\d{8}T\\d{6}Z)\\.tar\\.zst(\\.partial)?$");

    /** {@code DatabaseDump}'s names, as {@code backup-name.ts} reads them. */
    private static final Pattern DATABASE_DUMP = Pattern.compile("^(.+)-(\\d{8}T\\d{6}Z)\\.dump(\\.partial)?$");

    private static final String DUMP = "database dump";
    private static final String OFFSITE = "offsite copy";
    private static final String BACKUPS_PAGE = "/operations/backups";
    private static final String UPDATES_PAGE = "/operations/updates";
    private static final double MILLIS_PER_HOUR = 3_600_000d;

    private StackAlerts() {}

    /** Returns every alert the reading gives, red ones first, otherwise in the order the checks run. */
    public static List<Alert> of(final StackReading reading, final Thresholds thresholds, final Instant now) {
        final List<Alert> alerts = new ArrayList<>();
        services(reading.services(), alerts);
        drift(reading, alerts);
        backups(reading.archives(), thresholds, now, alerts);
        host(reading.host(), thresholds, alerts);
        alerts.sort(Comparator.comparing(alert -> alert.level() != Alert.Level.DOWN));
        return List.copyOf(alerts);
    }

    /** A service stopped or unhealthy is red unless its stop is meant; no service at all is yellow. */
    private static void services(final List<StackReading.Service> services, final List<Alert> alerts) {
        if (services.isEmpty()) {
            alerts.add(new Alert(
                    AlertType.SERVICE,
                    Alert.Level.WARN,
                    "services",
                    TEXTS.alert().noServices(),
                    UPDATES_PAGE));
            return;
        }
        for (final StackReading.Service service : services) {
            if (!down(service)) {
                continue;
            }
            final boolean running = "running".equals(service.state());
            alerts.add(new Alert(
                    AlertType.SERVICE,
                    Alert.Level.DOWN,
                    service.name(),
                    running
                            ? TEXTS.alert().unhealthy(service.name())
                            : TEXTS.alert().notRunning(service.name()),
                    List.of(
                            running
                                    ? TEXTS.alert().healthFails()
                                    : TEXTS.alert().dockerState(String.valueOf(service.state()))),
                    "/services/" + service.name()));
        }
    }

    /** Whether a service is red: stopped without meaning to be, or running with a failing healthcheck. */
    public static boolean down(final StackReading.Service service) {
        final boolean running = "running".equals(service.state());
        return running ? "unhealthy".equals(service.health()) : !service.quiet();
    }

    /** An older image, or a registry that did not answer, is yellow: nothing is broken yet. */
    private static void drift(final StackReading reading, final List<Alert> alerts) {
        final List<String> outdated = reading.services().stream()
                .filter(StackReading.Service::outdated)
                .map(StackReading.Service::name)
                .toList();
        if (!outdated.isEmpty()) {
            alerts.add(new Alert(
                    AlertType.DRIFT,
                    Alert.Level.WARN,
                    String.join(", ", outdated),
                    TEXTS.alert().olderImage(outdated, outdated.size()),
                    UPDATES_PAGE));
        }
        final String problem = reading.registryProblem();
        if (problem != null) {
            alerts.add(alert(
                    AlertType.DRIFT,
                    Alert.Level.WARN,
                    "registry",
                    TEXTS.alert().notCompared(),
                    TEXTS.alert().words(problem),
                    UPDATES_PAGE));
        }
    }

    /** Both kinds of backup must exist, and the newest file of every series must be young enough. */
    private static void backups(
            final List<StackReading.Archive> archives,
            final Thresholds thresholds,
            final Instant now,
            final List<Alert> alerts) {
        final List<StackReading.Archive> finished =
                archives.stream().filter(archive -> !archive.partial()).toList();
        if (finished.isEmpty()) {
            alerts.add(new Alert(
                    AlertType.BACKUP,
                    Alert.Level.DOWN,
                    "backups",
                    TEXTS.alert().noBackup(),
                    archives.isEmpty() ? List.of() : List.of(TEXTS.alert().onlyStarted()),
                    BACKUPS_PAGE));
            return;
        }
        final Map<String, Instant> newest = new LinkedHashMap<>();
        for (final StackReading.Archive archive : finished) {
            final String series = seriesOf(archive.name());
            if (series != null) {
                newest.merge(series, archive.modified(), (one, two) -> one.isAfter(two) ? one : two);
            }
        }
        if (!newest.containsKey(DUMP)) {
            alerts.add(alert(
                    AlertType.BACKUP,
                    Alert.Level.DOWN,
                    DUMP,
                    TEXTS.alert().noDump(),
                    TEXTS.alert().dumpMatters(),
                    BACKUPS_PAGE));
        }
        if (newest.size() == (newest.containsKey(DUMP) ? 1 : 0)) {
            alerts.add(new Alert(
                    AlertType.BACKUP, Alert.Level.DOWN, "backups", TEXTS.alert().noArchive(), BACKUPS_PAGE));
        }
        offsite(finished, thresholds, now, alerts);
        newest.forEach((series, at) -> {
            final double hours = Duration.between(at, now).toMillis() / MILLIS_PER_HOUR;
            if (hours > thresholds.backupAgeHours()) {
                alerts.add(alert(
                        AlertType.BACKUP,
                        Alert.Level.DOWN,
                        series,
                        DUMP.equals(series)
                                ? TEXTS.alert().oldDump((long) hours)
                                : TEXTS.alert().oldArchive(series, (long) hours),
                        TEXTS.alert().permittedAge(thresholds.backupAgeHours()),
                        BACKUPS_PAGE));
            }
        });
    }

    /** The newest archive copied off this host must be young enough; none is yellow, since the disk still has them. */
    private static void offsite(
            final List<StackReading.Archive> finished,
            final Thresholds thresholds,
            final Instant now,
            final List<Alert> alerts) {
        final Instant newest = finished.stream()
                .filter(StackReading.Archive::offsite)
                .map(StackReading.Archive::modified)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (newest == null) {
            alerts.add(new Alert(
                    AlertType.BACKUP, Alert.Level.WARN, OFFSITE, TEXTS.alert().noOffsite(), BACKUPS_PAGE));
            return;
        }
        final double hours = Duration.between(newest, now).toMillis() / MILLIS_PER_HOUR;
        if (hours > thresholds.backupAgeHours()) {
            alerts.add(alert(
                    AlertType.BACKUP,
                    Alert.Level.DOWN,
                    OFFSITE,
                    TEXTS.alert().oldOffsite((long) hours),
                    TEXTS.alert().permittedAge(thresholds.backupAgeHours()),
                    BACKUPS_PAGE));
        }
    }

    /** Disk and memory at or over their thresholds are yellow; numbers that could not be read are never over. */
    private static void host(
            final StackReading.@Nullable Host host, final Thresholds thresholds, final List<Alert> alerts) {
        if (host == null) {
            return;
        }
        final double disk = share(host.diskUsed(), host.diskTotal());
        if (disk >= thresholds.diskPercent()) {
            alerts.add(alert(
                    AlertType.DISK,
                    Alert.Level.WARN,
                    "disk",
                    TEXTS.alert().disk(Math.round(disk)),
                    TEXTS.alert().threshold(thresholds.diskPercent()),
                    "/"));
        }
        final double memory = share(host.memoryTotal() - host.memoryAvailable(), host.memoryTotal());
        if (memory >= thresholds.memoryPercent()) {
            alerts.add(new Alert(
                    AlertType.MEMORY,
                    Alert.Level.WARN,
                    "memory",
                    TEXTS.alert().memory(Math.round(memory)),
                    List.of(
                            TEXTS.alert().threshold(thresholds.memoryPercent()),
                            TEXTS.alert().noLimit()),
                    "/"));
        }
    }

    /** One volume by its name, or the one database dump series, or null for a file that is neither. */
    private static @Nullable String seriesOf(final String name) {
        if (DATABASE_DUMP.matcher(name).matches()) {
            return DUMP;
        }
        final Matcher volume = VOLUME_ARCHIVE.matcher(name);
        return volume.matches() ? volume.group(1) : null;
    }

    private static double share(final long part, final long whole) {
        return whole <= 0 ? 0 : (double) part / whole * 100;
    }

    /** An alert with one line below its title. */
    private static Alert alert(
            final AlertType type,
            final Alert.Level level,
            final String subject,
            final MessageRef title,
            final MessageRef detail,
            final String path) {
        return new Alert(type, level, subject, title, List.of(detail), path);
    }
}

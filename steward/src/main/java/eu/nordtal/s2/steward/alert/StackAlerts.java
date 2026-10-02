package eu.nordtal.s2.steward.alert;

import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
            alerts.add(alert(
                    AlertType.SERVICE, Alert.Level.WARN, "services", "Docker returned no services", "", UPDATES_PAGE));
            return;
        }
        for (final StackReading.Service service : services) {
            final boolean running = "running".equals(service.state());
            final String page = "/services/" + service.name();
            if (!running && !service.quiet()) {
                alerts.add(alert(
                        AlertType.SERVICE,
                        Alert.Level.DOWN,
                        service.name(),
                        service.name() + " is not running",
                        "Docker reports the state " + service.state() + ".",
                        page));
            } else if (running && "unhealthy".equals(service.health())) {
                alerts.add(alert(
                        AlertType.SERVICE,
                        Alert.Level.DOWN,
                        service.name(),
                        service.name() + " is unhealthy",
                        "It is running, but its healthcheck fails.",
                        page));
            }
        }
    }

    /** An older image, or a registry that did not answer, is yellow: nothing is broken yet. */
    private static void drift(final StackReading reading, final List<Alert> alerts) {
        final List<String> outdated = reading.services().stream()
                .filter(StackReading.Service::outdated)
                .map(StackReading.Service::name)
                .toList();
        if (!outdated.isEmpty()) {
            final String names = String.join(", ", outdated);
            alerts.add(alert(
                    AlertType.DRIFT,
                    Alert.Level.WARN,
                    names,
                    names + (outdated.size() == 1 ? " runs" : " run") + " an older image than the registry has",
                    "",
                    UPDATES_PAGE));
        }
        final String problem = reading.registryProblem();
        if (problem != null) {
            alerts.add(alert(
                    AlertType.DRIFT,
                    Alert.Level.WARN,
                    "registry",
                    "The images were not compared",
                    problem,
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
            alerts.add(alert(
                    AlertType.BACKUP,
                    Alert.Level.DOWN,
                    "backups",
                    "There is no finished backup",
                    archives.isEmpty() ? "" : "Only started ones (.partial).",
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
                    "There is no database dump",
                    "The worlds and configurations are saved, the accesses and payments are not.",
                    BACKUPS_PAGE));
        }
        if (newest.size() == (newest.containsKey(DUMP) ? 1 : 0)) {
            alerts.add(alert(
                    AlertType.BACKUP, Alert.Level.DOWN, "backups", "There is no volume archive", "", BACKUPS_PAGE));
        }
        newest.forEach((series, at) -> {
            final double hours = Duration.between(at, now).toMillis() / MILLIS_PER_HOUR;
            if (hours > thresholds.backupAgeHours()) {
                alerts.add(alert(
                        AlertType.BACKUP,
                        Alert.Level.DOWN,
                        series,
                        "The newest " + (DUMP.equals(series) ? DUMP : "archive of " + series) + " is " + (long) hours
                                + " hours old",
                        "The permitted age is " + thresholds.backupAgeHours() + " hours.",
                        BACKUPS_PAGE));
            }
        });
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
                    "The disk is " + percent(disk) + " full",
                    "The threshold is " + thresholds.diskPercent() + " %.",
                    "/"));
        }
        final double memory = share(host.memoryTotal() - host.memoryAvailable(), host.memoryTotal());
        if (memory >= thresholds.memoryPercent()) {
            alerts.add(alert(
                    AlertType.MEMORY,
                    Alert.Level.WARN,
                    "memory",
                    "Memory is " + percent(memory) + " used",
                    "The threshold is " + thresholds.memoryPercent() + " %. No container has a limit.",
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

    private static String percent(final double share) {
        return String.format(Locale.ROOT, "%.0f %%", share);
    }

    private static Alert alert(
            final AlertType type,
            final Alert.Level level,
            final String subject,
            final String title,
            final String detail,
            final String path) {
        return new Alert(type, level, subject, title, detail, path);
    }
}

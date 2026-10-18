package eu.nordtal.season.steward.alert;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.Measure;
import eu.nordtal.season.database.alert.Measure.Gauge;
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

/** Turns a {@link StackReading} and the {@link Thresholds} into the measured alerts, red first. */
public final class StackAlerts {

    /** Matches the names of {@code TarSnapshots}, as {@code backup-name.ts} does. */
    private static final Pattern VOLUME_ARCHIVE = Pattern.compile("^(.+)-(\\d{8}T\\d{6}Z)\\.tar\\.zst(\\.partial)?$");

    /** Matches the names of {@code DatabaseDump}, as {@code backup-name.ts} does. */
    private static final Pattern DATABASE_DUMP = Pattern.compile("^(.+)-(\\d{8}T\\d{6}Z)\\.dump(\\.partial)?$");

    static final String PACK = "resource pack";

    static final String PROXY_PAGE = "/services/proxy";

    private static final String DUMP = "database dump";
    private static final String OFFSITE = "offsite copy";
    private static final String BACKUPS_PAGE = "/operations/backups";
    private static final String UPDATES_PAGE = "/operations/updates";
    private static final double MILLIS_PER_HOUR = 3_600_000d;

    private StackAlerts() {}

    public static List<Alert> of(final StackReading reading, final Thresholds thresholds, final Instant now) {
        final List<Alert> alerts = new ArrayList<>();
        services(reading.services(), alerts);
        drift(reading, alerts);
        pack(reading.deadPack(), alerts);
        backups(reading, thresholds, now, alerts);
        host(reading.host(), thresholds, alerts);
        alerts.sort(Comparator.comparing(alert -> alert.level() != Alert.Level.DOWN));
        return List.copyOf(alerts);
    }

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
            final Integer exitCode = service.exitCode();
            final MessageRef title;
            final MessageRef detail;
            final Measure measured;
            if (running) {
                title = TEXTS.alert().unhealthy(service.name());
                detail = TEXTS.alert().healthFails();
                measured = Measure.state(Gauge.HEALTH, service.name(), service.health());
            } else if (service.purpose() == StackReading.Purpose.ONCE && exitCode != null) {
                title = TEXTS.alert().failedOnce(service.name(), exitCode);
                detail = TEXTS.alert().dockerState(String.valueOf(service.state()));
                measured = Measure.of(Gauge.EXIT_CODE, service.name(), exitCode);
            } else {
                title = TEXTS.alert().notRunning(service.name());
                detail = TEXTS.alert().dockerState(String.valueOf(service.state()));
                measured = Measure.state(Gauge.CONTAINER_STATE, service.name(), service.state());
            }
            alerts.add(new Alert(
                    AlertType.SERVICE,
                    Alert.Level.DOWN,
                    service.name(),
                    title,
                    List.of(detail),
                    List.of(measured),
                    "/services/" + service.name()));
        }
    }

    /**
     * Returns true for a service stopped against its purpose, failing its health check or failed.
     * A service with purpose MOVING is never down.
     */
    public static boolean down(final StackReading.Service service) {
        if (service.purpose() == StackReading.Purpose.MOVING) {
            return false;
        }
        if ("running".equals(service.state())) {
            return "unhealthy".equals(service.health());
        }
        return switch (service.purpose()) {
            case SERVES -> true;
            case ONCE -> failed(service.exitCode());
            case RESTS, MOVING -> false;
        };
    }

    private static boolean failed(final @Nullable Integer exitCode) {
        return exitCode != null && exitCode != 0;
    }

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
                    List.of(),
                    outdated.stream()
                            .map(service -> Measure.state(Gauge.OLDER_IMAGE, service, null))
                            .toList(),
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

    private static void pack(final @Nullable String deadPack, final List<Alert> alerts) {
        if (deadPack != null) {
            alerts.add(alert(
                    AlertType.PACK,
                    Alert.Level.DOWN,
                    PACK,
                    TEXTS.alert().deadPack(),
                    TEXTS.alert().offeredPack(deadPack),
                    PROXY_PAGE));
        }
    }

    /**
     * Adds alerts for a missing backup or dump, a volume without an archive and a series with a file that is too old.
     * Series of volumes outside the backup set are ignored.
     */
    private static void backups(
            final StackReading reading, final Thresholds thresholds, final Instant now, final List<Alert> alerts) {
        final List<StackReading.Archive> archives = reading.archives();
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
            if (series != null
                    && (DUMP.equals(series) || reading.backupVolumes().contains(series))) {
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
        for (final String volume : reading.backupVolumes()) {
            if (!newest.containsKey(volume)) {
                final String name = volumeName(volume);
                alerts.add(new Alert(
                        AlertType.BACKUP, Alert.Level.DOWN, name, TEXTS.alert().noArchive(name), BACKUPS_PAGE));
            }
        }
        offsite(finished, thresholds, now, alerts);
        newest.forEach((series, at) -> stale(reading, series, at, thresholds, now, alerts));
    }

    /**
     * Adds an alert when the newest file of the series is too old.
     * The dump is DOWN. A volume is DOWN when a service on it is down, else WARN.
     */
    private static void stale(
            final StackReading reading,
            final String series,
            final Instant at,
            final Thresholds thresholds,
            final Instant now,
            final List<Alert> alerts) {
        final double hours = Duration.between(at, now).toMillis() / MILLIS_PER_HOUR;
        if (hours <= thresholds.backupAgeHours()) {
            return;
        }
        if (DUMP.equals(series)) {
            alerts.add(old(Alert.Level.DOWN, DUMP, TEXTS.alert().oldDump((long) hours), hours, thresholds));
            return;
        }
        final String volume = volumeName(series);
        alerts.add(old(
                serviceDown(reading, series) ? Alert.Level.DOWN : Alert.Level.WARN,
                volume,
                TEXTS.alert().oldArchive(volume, (long) hours),
                hours,
                thresholds));
    }

    /** Adds a WARN alert for no offsite copy and a DOWN alert for an old one. */
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
            alerts.add(old(Alert.Level.DOWN, OFFSITE, TEXTS.alert().oldOffsite((long) hours), hours, thresholds));
        }
    }

    private static Alert old(
            final Alert.Level level,
            final String subject,
            final MessageRef title,
            final double hours,
            final Thresholds thresholds) {
        return new Alert(
                AlertType.BACKUP,
                level,
                subject,
                title,
                List.of(TEXTS.alert().permittedAge(thresholds.backupAgeHours())),
                List.of(Measure.against(Gauge.BACKUP_AGE_HOURS, subject, hours, thresholds.backupAgeHours())),
                BACKUPS_PAGE);
    }

    /** Adds a WARN alert for disk or memory at or over its threshold. */
    private static void host(
            final StackReading.@Nullable Host host, final Thresholds thresholds, final List<Alert> alerts) {
        if (host == null) {
            return;
        }
        final double disk = share(host.diskUsed(), host.diskTotal());
        if (disk >= thresholds.diskPercent()) {
            alerts.add(new Alert(
                    AlertType.DISK,
                    Alert.Level.WARN,
                    "disk",
                    TEXTS.alert().disk(Math.round(disk)),
                    List.of(TEXTS.alert().threshold(thresholds.diskPercent())),
                    List.of(Measure.against(Gauge.DISK_PERCENT, "disk", disk, thresholds.diskPercent())),
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
                    List.of(Measure.against(Gauge.MEMORY_PERCENT, "memory", memory, thresholds.memoryPercent())),
                    "/"));
        }
    }

    private static boolean serviceDown(final StackReading reading, final String series) {
        final List<String> users = reading.mountedBy().getOrDefault(series, List.of());
        return reading.services().stream().anyMatch(service -> users.contains(service.name()) && down(service));
    }

    /** Returns the {@code compose.yml} name of a volume, without the {@code <project>_} prefix of Docker. */
    static String volumeName(final String series) {
        return series.substring(series.indexOf('_') + 1);
    }

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

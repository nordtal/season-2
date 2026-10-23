package eu.nordtal.season.steward.alert;

import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.Measure;
import eu.nordtal.season.database.alert.Measure.Gauge;
import eu.nordtal.season.database.audit.JournalAction;
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

    private static final String DUMP = Alert.DUMP;
    private static final double MILLIS_PER_HOUR = 3_600_000d;

    /** A restart this long ago no longer counts as a loop, until the next one. */
    private static final Duration RESTART_WINDOW = Duration.ofHours(1);

    private StackAlerts() {}

    public static List<Alert> of(final StackReading reading, final Thresholds thresholds, final Instant now) {
        final List<Alert> alerts = new ArrayList<>();
        services(reading.services(), now, alerts);
        drift(reading, alerts);
        pack(reading.deadPack(), alerts);
        backups(reading, thresholds, now, alerts);
        host(reading.host(), thresholds, alerts);
        alerts.sort(Comparator.comparing(alert -> alert.level() != Alert.Level.DOWN));
        return List.copyOf(alerts);
    }

    private static void services(
            final List<StackReading.Service> services, final Instant now, final List<Alert> alerts) {
        if (services.isEmpty()) {
            alerts.add(new Alert(JournalAction.SERVICE_DOWN, Alert.Level.WARN, List.of()));
            return;
        }
        for (final StackReading.Service service : services) {
            if (!down(service)) {
                restarting(service, now, alerts);
                continue;
            }
            final Integer exitCode = service.exitCode();
            final Measure measured;
            if ("running".equals(service.state())) {
                measured = Measure.state(Gauge.HEALTH, service.name(), service.health());
            } else if (service.purpose() == StackReading.Purpose.ONCE && exitCode != null) {
                measured = Measure.of(Gauge.EXIT_CODE, service.name(), exitCode);
            } else {
                measured = Measure.state(Gauge.CONTAINER_STATE, service.name(), String.valueOf(service.state()));
            }
            alerts.add(new Alert(JournalAction.SERVICE_DOWN, Alert.Level.DOWN, List.of(measured)));
        }
    }

    /** Adds a WARN alert for a running service that Docker restarted within the last hour. */
    private static void restarting(final StackReading.Service service, final Instant now, final List<Alert> alerts) {
        final Instant startedAt = service.startedAt();
        if (service.restarts() == 0
                || startedAt == null
                || service.purpose() == StackReading.Purpose.MOVING
                || startedAt.isBefore(now.minus(RESTART_WINDOW))) {
            return;
        }
        alerts.add(new Alert(
                JournalAction.SERVICE_DOWN,
                Alert.Level.WARN,
                List.of(Measure.of(Gauge.RESTARTS, service.name(), service.restarts()))));
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
                    JournalAction.IMAGE_DRIFT,
                    Alert.Level.WARN,
                    outdated.stream()
                            .map(service -> Measure.state(Gauge.OLDER_IMAGE, service, null))
                            .toList()));
        }
        final String problem = reading.registryProblem();
        if (problem != null) {
            alerts.add(new Alert(
                    JournalAction.IMAGE_DRIFT,
                    Alert.Level.WARN,
                    List.of(Measure.state(Gauge.REGISTRY, "registry", problem))));
        }
    }

    private static void pack(final @Nullable String deadPack, final List<Alert> alerts) {
        if (deadPack != null) {
            alerts.add(new Alert(
                    JournalAction.PACK_DEAD, Alert.Level.DOWN, List.of(Measure.state(Gauge.PACK, PACK, deadPack))));
        }
    }

    /**
     * Adds alerts for a missing backup or dump, a volume without an archive and a series with a file that is too old.
     * Series of volumes outside the backup set are ignored.
     */
    private static void backups(
            final StackReading reading, final Thresholds thresholds, final Instant now, final List<Alert> alerts) {
        final List<StackReading.Archive> finished = reading.archives().stream()
                .filter(archive -> !archive.partial())
                .toList();
        if (finished.isEmpty()) {
            alerts.add(missing(Alert.Level.DOWN, Alert.BACKUPS, thresholds));
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
            alerts.add(missing(Alert.Level.DOWN, DUMP, thresholds));
        }
        for (final String volume : reading.backupVolumes()) {
            if (!newest.containsKey(volume)) {
                alerts.add(missing(Alert.Level.DOWN, volumeName(volume), thresholds));
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
            alerts.add(old(Alert.Level.DOWN, DUMP, hours, thresholds));
            return;
        }
        alerts.add(old(
                serviceDown(reading, series) ? Alert.Level.DOWN : Alert.Level.WARN,
                volumeName(series),
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
            alerts.add(missing(Alert.Level.WARN, Alert.OFFSITE, thresholds));
            return;
        }
        final double hours = Duration.between(newest, now).toMillis() / MILLIS_PER_HOUR;
        if (hours > thresholds.backupAgeHours()) {
            alerts.add(old(Alert.Level.DOWN, Alert.OFFSITE, hours, thresholds));
        }
    }

    private static Alert old(
            final Alert.Level level, final String subject, final double hours, final Thresholds thresholds) {
        return new Alert(
                JournalAction.BACKUP_STALE,
                level,
                List.of(Measure.against(Gauge.BACKUP_AGE_HOURS, subject, hours, thresholds.backupAgeHours())));
    }

    /** Returns the alert of a backup series without a finished file: an age without a value. */
    private static Alert missing(final Alert.Level level, final String subject, final Thresholds thresholds) {
        return new Alert(
                JournalAction.BACKUP_STALE,
                level,
                List.of(new Measure(
                        Gauge.BACKUP_AGE_HOURS, subject, null, (double) thresholds.backupAgeHours(), null)));
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
                    JournalAction.DISK_FULL,
                    Alert.Level.WARN,
                    List.of(Measure.against(Gauge.DISK_PERCENT, "disk", disk, thresholds.diskPercent()))));
        }
        final double memory = share(host.memoryTotal() - host.memoryAvailable(), host.memoryTotal());
        if (memory >= thresholds.memoryPercent()) {
            alerts.add(new Alert(
                    JournalAction.MEMORY_FULL,
                    Alert.Level.WARN,
                    List.of(Measure.against(Gauge.MEMORY_PERCENT, "memory", memory, thresholds.memoryPercent()))));
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
}

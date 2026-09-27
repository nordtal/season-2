package eu.nordtal.s2.steward.worker.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The traffic light behind web pushes: what is wrong right now, without the configured thresholds.
 *
 * It sends raw measurements, and steward-ui's {@code AlertWatch} holds them against its own thresholds.
 */
final class AlertLevel {

    enum Level {
        OK,
        WARN,
        DOWN
    }

    /** What a trigger is about, so a push can be switched per type. */
    enum Kind {
        /** A service is stopped, reports itself unhealthy, or the list came back empty. */
        SERVICE,
        /** A kind of backup is missing altogether. */
        BACKUP,
        /** A container runs an older image than the registry has, or the registry did not answer. */
        DRIFT
    }

    /**
     * One thing that is wrong, and where a tap should land.
     *
     * @param subject a short word or comma list, mirroring {@code health.ts}'s {@code Trigger.subject}
     * @param path a frontend route: {@code /services/<name>} for one service, {@code /operations} for a backup or drift
     */
    record Trigger(Kind kind, Level level, String subject, String path) {}

    /**
     * Everything that is wrong right now, plus the three raw measurements.
     *
     * @param diskPercent percent of the disk in use, or null when {@code /proc} could not be read
     * @param memoryPercent percent of host memory in use, or null for the same reason
     * @param backupAgeHours the age of the most neglected finished backup series, or null when there is none
     */
    record Reading(
            List<Trigger> triggers,
            @Nullable Double diskPercent,
            @Nullable Double memoryPercent,
            @Nullable Double backupAgeHours) {

        static final Reading OK = new Reading(List.of(), null, null, null);

        Reading {
            triggers = List.copyOf(triggers);
        }

        /** The worst level any trigger reached. */
        Level level() {
            for (final Trigger trigger : triggers) {
                if (trigger.level() == Level.DOWN) {
                    return Level.DOWN;
                }
            }
            return triggers.isEmpty() ? Level.OK : Level.WARN;
        }

        /** The trigger a single-line summary is about: the first red one, else the first at all. */
        @Nullable
        Trigger worst() {
            for (final Trigger trigger : triggers) {
                if (trigger.level() == Level.DOWN) {
                    return trigger;
                }
            }
            return triggers.isEmpty() ? null : triggers.getFirst();
        }

        String subject() {
            final Trigger worst = worst();
            return worst == null ? "" : worst.subject();
        }

        String path() {
            final Trigger worst = worst();
            return worst == null ? "/" : worst.path();
        }
    }

    /** `TarSnapshots` names, mirroring `backup-name.ts`'s `VOLUME_ARCHIVE`. */
    private static final Pattern VOLUME_ARCHIVE = Pattern.compile("^(.+)-(\\d{8}T\\d{6}Z)\\.tar\\.zst(\\.partial)?$");

    /** `DatabaseDump` names, mirroring `backup-name.ts`'s `DATABASE_DUMP`. */
    private static final Pattern DATABASE_DUMP = Pattern.compile("^(.+)-(\\d{8}T\\d{6}Z)\\.dump(\\.partial)?$");

    private AlertLevel() {}

    static Reading of(final Map<String, Object> serviceTable, final List<Map<String, Object>> archives) {
        return of(serviceTable, archives, Map.of(), Instant.now());
    }

    /**
     * The whole reading.
     *
     * @param host {@code /api/host}'s own map; an empty or {@code unreadable} one leaves both percentages null
     */
    @SuppressWarnings("unchecked")
    static Reading of(
            final Map<String, Object> serviceTable,
            final List<Map<String, Object>> archives,
            final Map<String, Object> host,
            final Instant now) {
        final List<Map<String, Object>> services =
                (List<Map<String, Object>>) serviceTable.getOrDefault("services", List.of());

        // An empty service list means the daemon said nothing, since compose.yml declares real services.
        if (services.isEmpty()) {
            return new Reading(
                    List.of(new Trigger(Kind.SERVICE, Level.WARN, "services", "/operations/updates")),
                    diskPercent(host),
                    memoryPercent(host),
                    backupAgeHours(archives, now));
        }

        final List<Trigger> triggers = new ArrayList<>();
        serviceTriggers(services, triggers);
        backupTriggers(archives, triggers);
        driftTriggers(serviceTable, services, triggers);
        return new Reading(triggers, diskPercent(host), memoryPercent(host), backupAgeHours(archives, now));
    }

    /** A service stopped or unhealthy: red, and first, since {@code worst()} reads in order. */
    private static void serviceTriggers(final List<Map<String, Object>> services, final List<Trigger> triggers) {
        for (final Map<String, Object> service : services) {
            final String name = String.valueOf(service.get("service"));
            if (!"running".equals(service.get("state")) || "unhealthy".equals(service.get("health"))) {
                triggers.add(new Trigger(Kind.SERVICE, Level.DOWN, name, "/services/" + name));
            }
        }
    }

    /** A missing backup of either kind; the age is steward-ui's half. */
    private static void backupTriggers(final List<Map<String, Object>> archives, final List<Trigger> triggers) {
        boolean dump = false;
        boolean volume = false;
        for (final Map<String, Object> archive : archives) {
            if (Boolean.TRUE.equals(archive.get("partial"))) {
                continue;
            }
            final String name = String.valueOf(archive.get("name"));
            if (DATABASE_DUMP.matcher(name).matches()) {
                dump = true;
            } else if (VOLUME_ARCHIVE.matcher(name).matches()) {
                volume = true;
            }
        }
        if (!dump && !volume) {
            triggers.add(new Trigger(Kind.BACKUP, Level.DOWN, "backups", "/operations/backups"));
        } else if (!dump) {
            triggers.add(new Trigger(Kind.BACKUP, Level.DOWN, "database dump", "/operations/backups"));
        } else if (!volume) {
            triggers.add(new Trigger(Kind.BACKUP, Level.DOWN, "backups", "/operations/backups"));
        }
    }

    /** Image drift: yellow, since nothing is broken yet. */
    private static void driftTriggers(
            final Map<String, Object> serviceTable,
            final List<Map<String, Object>> services,
            final List<Trigger> triggers) {
        final List<String> outdated = new ArrayList<>();
        for (final Map<String, Object> service : services) {
            if ("OUTDATED".equals(service.get("drift"))) {
                outdated.add(String.valueOf(service.get("service")));
            }
        }
        if (!outdated.isEmpty()) {
            triggers.add(new Trigger(Kind.DRIFT, Level.WARN, String.join(", ", outdated), "/operations/updates"));
        }
        final Object drift = serviceTable.get("drift");
        if (drift instanceof Map<?, ?> about && Boolean.FALSE.equals(about.get("reached"))) {
            triggers.add(new Trigger(Kind.DRIFT, Level.WARN, "registry", "/operations/updates"));
        }
    }

    /** {@code diskUsedBytes / diskTotalBytes} as a percentage, or null when either is missing. */
    private static @Nullable Double diskPercent(final Map<String, Object> host) {
        return share(number(host.get("diskUsedBytes")), number(host.get("diskTotalBytes")));
    }

    /**
     * How much of the machine's memory is in use, as a percentage.
     *
     * Used is the total minus {@code memoryAvailableBytes}, as in {@code health.ts}, so the two cannot disagree.
     */
    private static @Nullable Double memoryPercent(final Map<String, Object> host) {
        final Double total = number(host.get("memoryTotalBytes"));
        final Double available = number(host.get("memoryAvailableBytes"));
        if (total == null || available == null) {
            return null;
        }
        return share(total - available, total);
    }

    private static @Nullable Double share(final @Nullable Double part, final @Nullable Double whole) {
        if (part == null || whole == null || whole <= 0) {
            return null;
        }
        return part / whole * 100;
    }

    private static @Nullable Double number(final @Nullable Object value) {
        return value instanceof Number found ? found.doubleValue() : null;
    }

    /**
     * The age of the most neglected finished backup series, in hours; null when there is no finished backup.
     *
     * A series is one volume or the database dump, so one failing volume cannot hide behind the others.
     */
    private static @Nullable Double backupAgeHours(final List<Map<String, Object>> archives, final Instant now) {
        final Map<String, Instant> newestPerSeries = new LinkedHashMap<>();
        for (final Map<String, Object> archive : archives) {
            if (Boolean.TRUE.equals(archive.get("partial"))) {
                continue;
            }
            final String name = String.valueOf(archive.get("name"));
            final String series = seriesOf(name);
            if (series == null) {
                continue;
            }
            final Instant modified = modifiedOf(archive);
            if (modified == null) {
                continue;
            }
            final Instant known = newestPerSeries.get(series);
            if (known == null || modified.isAfter(known)) {
                newestPerSeries.put(series, modified);
            }
        }
        Double worst = null;
        for (final Instant newest : newestPerSeries.values()) {
            final double hours = Duration.between(newest, now).toMillis() / 3_600_000d;
            if (worst == null || hours > worst) {
                worst = hours;
            }
        }
        return worst;
    }

    /** Which series a file belongs to: one volume by name, or the single database-dump series. */
    private static @Nullable String seriesOf(final String name) {
        if (DATABASE_DUMP.matcher(name).matches()) {
            return "database dump";
        }
        final Matcher volume = VOLUME_ARCHIVE.matcher(name);
        return volume.matches() ? volume.group(1) : null;
    }

    /** {@code modified} as an instant, or null if it is not one. */
    private static @Nullable Instant modifiedOf(final Map<String, Object> archive) {
        final Object modified = archive.get("modified");
        if (modified == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(modified));
        } catch (final java.time.format.DateTimeParseException notATime) {
            return null;
        }
    }
}

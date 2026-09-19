package eu.nordtal.s2.steward.worker.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The traffic light of concept §10c, read again for a reason `steward-ui/frontend/src/lib/health.ts`
 * does not have: deciding when to fire a web push (steward/98).
 *
 * <h2>The raw reading lives here; the policy lives in steward-ui</h2>
 * This class answers only what needs no taste at all - a stopped or unhealthy service, a completely
 * missing backup, an image the registry says is outdated. The three configured thresholds (disk %,
 * memory %, the permitted age of a backup) are <b>not</b> evaluated here, and the reason is not that
 * they were left out: their numbers live in steward-ui's {@code UiSpec.AlertSpec}, and a second copy
 * of a configured number in a second process's config file is exactly the duplicate
 * {@code InternalClient}'s own javadoc warns about. So this sends the <b>measurements</b> instead -
 * disk percent, memory percent, the age of the most neglected backup series - and steward-ui's
 * {@code AlertWatch} holds them against its own thresholds. Every number still exists exactly once.
 *
 * <p>That is Till's decision of 2026-09-19 on the scoping question steward/98 raised: threshold
 * alarms <em>do</em> push, and they do it without the worker learning what a threshold is.</p>
 *
 * <h2>Every trigger, not only the worst one</h2>
 * {@link Reading} carries the whole list. It used to return the first branch that fired and stop,
 * which was enough while one push carried one traffic light - but a push is now per type and each
 * type is switchable per account (steward/98, Till's review of 2026-09-18). With only the worst
 * trigger, an account that had turned "service" off and "images" on would hear nothing at all about
 * a drifted image for as long as any service was down. The overall {@link Reading#level},
 * {@link Reading#subject} and {@link Reading#path} are still answered, derived from the list, so
 * everything that only wants "how bad is it right now" is unchanged.
 *
 * <h2>Why it takes the SAME maps {@code /api/services} and {@code /api/backups} already answer</h2>
 * {@code WorkerApi} builds {@code serviceTable()} and {@code archives()} as {@code Map<String,
 * Object>} for Gson to serialise, and this reads those same objects directly, in the same process,
 * before they are ever turned into JSON. That is deliberately not a DTO: the fields are the ones
 * {@code WorkerApi} already owns, read once more rather than copied into a second shape. The host
 * numbers below arrive the same way, out of {@code /api/host}'s own map.
 */
final class AlertLevel {

    enum Level { OK, WARN, DOWN }

    /**
     * What a trigger is <b>about</b>, in the words an admin would use for it.
     *
     * <p>This is the half of the per-type switch that can only be decided here: which branch fired
     * is knowable in this method and nowhere else. Parsing it back out of {@link Trigger#subject} on
     * the other side of the wire - "backups" means a backup, "/operations" means drift - would be
     * reading prose as an enum, and the prose is written for a lock screen.</p>
     */
    enum Kind {
        /** A service is stopped, reports itself unhealthy, or the list came back empty. */
        SERVICE,
        /** A kind of backup is missing altogether. The age is steward-ui's half - see the class note. */
        BACKUP,
        /** A container runs an older image than the registry has, or the registry did not answer. */
        DRIFT
    }

    /**
     * One thing that is wrong, and where a tap should land.
     *
     * @param subject a short word or comma list, mirroring {@code health.ts}'s {@code Trigger.subject}
     * @param path    a frontend route - {@code /services/<name>} for one service, {@code /operations}
     *                for a backup or drift problem
     */
    record Trigger(Kind kind, Level level, String subject, String path) {
    }

    /**
     * One reading: everything that is wrong right now, plus the three raw measurements.
     *
     * <p>{@link #level}, {@link #subject} and {@link #path} are derived rather than stored - two
     * fields that have to agree with a list is one field that eventually will not.</p>
     *
     * @param diskPercent     percent of the disk in use, or null when {@code /proc} could not be read
     * @param memoryPercent   percent of host memory in use, or null for the same reason
     * @param backupAgeHours  how old the <b>most neglected</b> finished backup series is, in hours,
     *                        or null when there is not a single finished backup - which is a
     *                        {@link Kind#BACKUP} trigger already and not an age question
     */
    record Reading(List<Trigger> triggers,
                   Double diskPercent,
                   Double memoryPercent,
                   Double backupAgeHours) {

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

    /** `TarSnapshots`: `<volume>-<stamp>.tar.zst`, mirroring `backup-name.ts`'s `VOLUME_ARCHIVE`. */
    private static final Pattern VOLUME_ARCHIVE =
            Pattern.compile("^(.+)-(\\d{8}T\\d{6}Z)\\.tar\\.zst(\\.partial)?$");

    /** `DatabaseDump`: `nordtal-<stamp>.dump`, mirroring `backup-name.ts`'s `DATABASE_DUMP`. */
    private static final Pattern DATABASE_DUMP =
            Pattern.compile("^(.+)-(\\d{8}T\\d{6}Z)\\.dump(\\.partial)?$");

    private AlertLevel() {
    }

    static Reading of(final Map<String, Object> serviceTable, final List<Map<String, Object>> archives) {
        return of(serviceTable, archives, Map.of(), Instant.now());
    }

    /**
     * The whole reading.
     *
     * @param host {@code /api/host}'s own map, for the two percentages. An empty map - or one whose
     *             {@code unreadable} key is set, which is how {@code hostNumbers} reports a
     *             {@code /proc} it could not read - leaves both percentages null, and steward-ui
     *             then has nothing to compare and says nothing. A guessed percentage would be a
     *             lock-screen alarm about a number nobody measured.
     */
    @SuppressWarnings("unchecked")
    static Reading of(final Map<String, Object> serviceTable,
                      final List<Map<String, Object>> archives,
                      final Map<String, Object> host,
                      final Instant now) {
        final List<Trigger> triggers = new ArrayList<>();
        final List<Map<String, Object>> services =
                (List<Map<String, Object>>) serviceTable.getOrDefault("services", List.of());

        // 0 - an answered, EMPTY service list. health.ts's own case 0, unchanged: compose.yml
        // declares real services, so an empty answer is the daemon saying nothing rather than
        // nothing being wrong.
        //
        // It still stops here rather than going on to the backup and drift checks below, and that
        // is deliberate: with no service list there is no drift to speak of, and a Docker daemon
        // that answers nothing is the one sentence worth sending on its own.
        if (services.isEmpty()) {
            return new Reading(
                    List.of(new Trigger(Kind.SERVICE, Level.WARN, "services", "/operations")),
                    diskPercent(host), memoryPercent(host), backupAgeHours(archives, now));
        }

        // 1 - a service stopped or unhealthy. Red, and first in the list: without this it is not a
        // traffic light at all, and `worst()` reads the list in order.
        for (final Map<String, Object> service : services) {
            final String name = String.valueOf(service.get("service"));
            if (!"running".equals(service.get("state")) || "unhealthy".equals(service.get("health"))) {
                triggers.add(new Trigger(Kind.SERVICE, Level.DOWN, name, "/services/" + name));
            }
        }

        // 2 - the backup, presence only (steward/40's two kinds). The AGE is not judged here - see
        // the class note on why the hours threshold stays out of this process; `backupAgeHours`
        // below is the measurement that lets steward-ui judge it.
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
            triggers.add(new Trigger(Kind.BACKUP, Level.DOWN, "backups", "/operations"));
        } else if (!dump) {
            triggers.add(new Trigger(Kind.BACKUP, Level.DOWN, "database dump", "/operations"));
        } else if (!volume) {
            triggers.add(new Trigger(Kind.BACKUP, Level.DOWN, "backups", "/operations"));
        }

        // 3 - image drift, presence only. Yellow: nothing is broken, but this is the failure that
        // went unnoticed for four releases (A24).
        final List<String> outdated = new ArrayList<>();
        for (final Map<String, Object> service : services) {
            if ("OUTDATED".equals(service.get("drift"))) {
                outdated.add(String.valueOf(service.get("service")));
            }
        }
        if (!outdated.isEmpty()) {
            triggers.add(new Trigger(Kind.DRIFT, Level.WARN, String.join(", ", outdated), "/operations"));
        }
        final Object drift = serviceTable.get("drift");
        if (drift instanceof Map<?, ?> about && Boolean.FALSE.equals(about.get("reached"))) {
            triggers.add(new Trigger(Kind.DRIFT, Level.WARN, "registry", "/operations"));
        }

        return new Reading(triggers, diskPercent(host), memoryPercent(host),
                backupAgeHours(archives, now));
    }

    /** {@code diskUsedBytes / diskTotalBytes} as a percentage, or null when either is missing. */
    private static Double diskPercent(final Map<String, Object> host) {
        return share(number(host.get("diskUsedBytes")), number(host.get("diskTotalBytes")));
    }

    /**
     * How much of the machine's memory is in use, as a percentage.
     *
     * <p>{@code memoryAvailableBytes} is what {@code /proc/meminfo} calls available rather than
     * free, and "used" is the rest of the total - the same arithmetic {@code health.ts} does on the
     * same two fields, so the tile and the lock screen cannot disagree about what 90 % means.</p>
     */
    private static Double memoryPercent(final Map<String, Object> host) {
        final Double total = number(host.get("memoryTotalBytes"));
        final Double available = number(host.get("memoryAvailableBytes"));
        if (total == null || available == null) {
            return null;
        }
        return share(total - available, total);
    }

    private static Double share(final Double part, final Double whole) {
        if (part == null || whole == null || whole <= 0) {
            return null;
        }
        return part / whole * 100;
    }

    private static Double number(final Object value) {
        return value instanceof Number found ? found.doubleValue() : null;
    }

    /**
     * The age of the <b>most neglected</b> finished backup series, in hours.
     *
     * <p><b>Per series, and then the oldest of them - never the newest file in the directory.</b>
     * That is {@code health.ts}'s own argument, held to one number so that a threshold nobody here
     * knows can still be applied on the other side: sixteen archives from tonight and a world from
     * three weeks ago make "the newest backup" minutes old, and the per-volume tar failure that a
     * missing mount produces hides behind the small volumes that succeeded. A series is one volume,
     * or the database dump.</p>
     *
     * <p>Null when there is no finished backup at all - that is a {@link Kind#BACKUP} trigger and
     * not an age, and sending an age of "infinity" would make the two look like one thing.</p>
     */
    private static Double backupAgeHours(final List<Map<String, Object>> archives, final Instant now) {
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
    private static String seriesOf(final String name) {
        if (DATABASE_DUMP.matcher(name).matches()) {
            return "database dump";
        }
        final Matcher volume = VOLUME_ARCHIVE.matcher(name);
        return volume.matches() ? volume.group(1) : null;
    }

    /** {@code archiveRow}'s own {@code modified}, an ISO instant string, or null if it is not one. */
    private static Instant modifiedOf(final Map<String, Object> archive) {
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

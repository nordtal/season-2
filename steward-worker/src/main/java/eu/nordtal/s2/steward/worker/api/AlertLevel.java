package eu.nordtal.s2.steward.worker.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The traffic light of concept §10c, read again for a reason `steward-ui/frontend/src/lib/health.ts`
 * does not have: deciding when to fire a web push (steward/98).
 *
 * <h2>Why this is a SUBSET of {@code health.ts}'s {@code summarise}, not a port of it</h2>
 * That file has five families of trigger, and two of them - the disk/memory percentage and the
 * backup-age-in-hours checks - read a "taste" threshold that lives in steward-ui's own
 * {@code UiSpec.AlertSpec}, not here. Porting those two as well would mean a second copy of a
 * configured number sitting in a different process's config file, with nothing to stop the two from
 * drifting the way {@code InternalClient}'s own javadoc warns a duplicated shape always eventually
 * does. So this class answers only the checks that need no taste at all - a stopped or unhealthy
 * service, a completely missing backup, an image the registry says is outdated - which happen to be
 * exactly the two failures the whole light was built for (A24, image drift; A23, a backup run that
 * saved nothing). A threshold crossing (disk 85 %, memory 90 %, a backup older than 36 hours) still
 * turns the START PAGE's tile yellow or red - it simply does not, on its own, wake a phone. Flagged
 * in the ticket for Till to confirm or overrule; the fallback if the answer is "no, threshold alerts
 * must push too" is a second, small, worker-owned copy of those three numbers - not a shared DTO.
 *
 * <h2>Why it takes the SAME maps {@code /api/services} and {@code /api/backups} already answer</h2>
 * {@code WorkerApi} builds {@code serviceTable()} and {@code archives()} as {@code Map<String,
 * Object>} for Gson to serialise, and this reads those same objects directly, in the same process,
 * before they are ever turned into JSON. That is deliberately not a DTO: the fields are the ones
 * {@code WorkerApi} already owns, read once more rather than copied into a second shape.
 */
final class AlertLevel {

    enum Level { OK, WARN, DOWN }

    /**
     * One reading: how bad, what it is about, and where a tap should land.
     *
     * @param subject a short word or comma list, mirroring {@code health.ts}'s {@code Trigger.subject}
     * @param path    a frontend route - {@code /services/<name>} for one service, {@code /operations}
     *                for a backup or drift problem, {@code /} for {@link #OK}
     */
    record Reading(Level level, String subject, String path) {

        static final Reading OK = new Reading(Level.OK, "", "/");
    }

    /** `TarSnapshots`: `<volume>-<stamp>.tar.zst`, mirroring `backup-name.ts`'s `VOLUME_ARCHIVE`. */
    private static final Pattern VOLUME_ARCHIVE =
            Pattern.compile("^(.+)-(\\d{8}T\\d{6}Z)\\.tar\\.zst(\\.partial)?$");

    /** `DatabaseDump`: `nordtal-<stamp>.dump`, mirroring `backup-name.ts`'s `DATABASE_DUMP`. */
    private static final Pattern DATABASE_DUMP =
            Pattern.compile("^(.+)-(\\d{8}T\\d{6}Z)\\.dump(\\.partial)?$");

    private AlertLevel() {
    }

    @SuppressWarnings("unchecked")
    static Reading of(final Map<String, Object> serviceTable, final List<Map<String, Object>> archives) {
        final List<Map<String, Object>> services =
                (List<Map<String, Object>>) serviceTable.getOrDefault("services", List.of());

        // 0 - an answered, EMPTY service list. health.ts's own case 0, unchanged: compose.yml
        // declares real services, so an empty answer is the daemon saying nothing rather than
        // nothing being wrong.
        if (services.isEmpty()) {
            return new Reading(Level.WARN, "services", "/operations");
        }

        // 1 - a service stopped or unhealthy. Red, and checked first: without this it is not a
        // traffic light at all.
        for (final Map<String, Object> service : services) {
            final String name = String.valueOf(service.get("service"));
            if (!"running".equals(service.get("state"))) {
                return new Reading(Level.DOWN, name, "/services/" + name);
            }
            if ("unhealthy".equals(service.get("health"))) {
                return new Reading(Level.DOWN, name, "/services/" + name);
            }
        }

        // 3 - the backup, presence only (steward/40's two kinds). No age check here - see the class
        // note on why the hours threshold stays out of this process.
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
            return new Reading(Level.DOWN, "backups", "/operations");
        }
        if (!dump) {
            return new Reading(Level.DOWN, "database dump", "/operations");
        }
        if (!volume) {
            return new Reading(Level.DOWN, "backups", "/operations");
        }

        // 2 - image drift, presence only. Yellow: nothing is broken, but this is the failure that
        // went unnoticed for four releases (A24).
        final Object drift = serviceTable.get("drift");
        if (drift instanceof Map<?, ?> about && Boolean.FALSE.equals(about.get("reached"))) {
            return new Reading(Level.WARN, "registry", "/operations");
        }
        final List<String> outdated = new ArrayList<>();
        for (final Map<String, Object> service : services) {
            if ("OUTDATED".equals(service.get("drift"))) {
                outdated.add(String.valueOf(service.get("service")));
            }
        }
        if (!outdated.isEmpty()) {
            return new Reading(Level.WARN, String.join(", ", outdated), "/operations");
        }

        return Reading.OK;
    }
}

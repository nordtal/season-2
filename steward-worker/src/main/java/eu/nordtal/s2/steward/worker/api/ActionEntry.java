package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.database.audit.AuditEntry;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One row of the actions feed: a run from {@code update_request} or a line from {@code audit_log}.
 *
 * @param kind the {@link UpdateKind} name for a run, or the free-text {@code audit_log.action} for a journal line
 * @param occurred when this happened, by the database's clock
 * @param extent the outcome or its extent, such as "3/3 successful"; never empty
 * @param actorDiscordId the Discord id to resolve through the roster, or {@code ""} (never null) when there is none
 * @param actorLabel plain text to show when there is an actor but no id, {@code ""} (never null) otherwise
 * @param system whether Steward itself is credited, such as the nightly backup clock or an orphan settle
 */
public record ActionEntry(
        String kind, Instant occurred, String extent, String actorDiscordId, String actorLabel, boolean system) {

    /** A trailing snowflake on a free-text requester, as in {@code "name (id)"}. */
    private static final Pattern TRAILING_SNOWFLAKE = Pattern.compile("^.*\\((\\d{17,20})\\)\\s*$");

    /**
     * This entry in the shape the browser reads, with {@code occurred} as ISO-8601 text.
     *
     * @return a map, in the order the fields are declared
     */
    public java.util.Map<String, Object> json() {
        final java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("kind", kind);
        row.put("occurred", occurred.toString());
        row.put("extent", extent);
        row.put("actorDiscordId", actorDiscordId);
        row.put("actorLabel", actorLabel);
        row.put("system", system);
        return row;
    }

    /**
     * A run from {@code update_request}; a trailing {@code (snowflake)} in the requester becomes the actor id.
     *
     * @param run the row, however it finished
     * @return the entry that describes it
     */
    static ActionEntry of(final UpdateRequest run) {
        final Instant occurred = run.finished() != null ? run.finished() : run.requested();
        final String requestedBy = run.requestedBy();
        final boolean system = requestedBy == null || requestedBy.startsWith("steward-worker");
        String actorDiscordId = "";
        String actorLabel = "";
        if (requestedBy != null && !system) {
            final Matcher match = TRAILING_SNOWFLAKE.matcher(requestedBy);
            if (match.matches()) {
                actorDiscordId = match.group(1);
            } else {
                actorLabel = requestedBy;
            }
        }
        return new ActionEntry(run.kind().name(), occurred, extentOf(run), actorDiscordId, actorLabel, system);
    }

    /**
     * A line from {@code audit_log}, whose {@code actor} is already a clean Discord id or {@code null}.
     *
     * @param entry the journal line
     * @return the entry that describes it
     */
    static ActionEntry of(final AuditEntry entry) {
        final String actor = entry.actor();
        final boolean system = actor == null;
        final String detail = entry.detail();
        final String extent = detail == null || detail.isBlank() ? entry.action() : detail;
        return new ActionEntry(entry.action(), entry.occurred(), extent, actor == null ? "" : actor, "", system);
    }

    /**
     * What to say a run amounted to.
     *
     * @param run a finished, running or pending request
     * @return "n/m successful" over the touched services, else the report's headline, else the row's status word
     */
    private static String extentOf(final UpdateRequest run) {
        if (run.status() == UpdateStatus.PENDING) {
            return "pending";
        }
        if (run.status() == UpdateStatus.RUNNING) {
            return "running";
        }
        final var report = UpdateReports.parse(run.result());
        if (report.isEmpty()) {
            return run.status() == UpdateStatus.CANCELLED
                    ? "cancelled"
                    : run.status() == UpdateStatus.FAILED ? "failed" : "done";
        }
        final UpdateReport parsed = report.get();
        final long total =
                parsed.services().stream().filter(ActionEntry::touched).count();
        if (total == 0) {
            return parsed.stage().headline();
        }
        final long successful = parsed.services().stream()
                .filter(line -> line.state() == UpdateReport.State.HEALTHY || line.state() == UpdateReport.State.SAVED)
                .count();
        return successful + "/" + total + " successful";
    }

    private static boolean touched(final UpdateReport.ServiceLine line) {
        return line.state() != UpdateReport.State.UNCHANGED && line.state() != UpdateReport.State.PLANNED;
    }
}

package eu.nordtal.s2.steward.api;

import com.google.gson.JsonElement;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.audit.AuditEntry;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import java.time.Instant;

/**
 * One row of the actions feed: a run from the run inbox or a line from {@code audit_log}.
 *
 * @param kind the {@link UpdateKind} name for a run, or the free-text {@code audit_log.action} for a journal line
 * @param occurred when this happened, by the database's clock
 * @param extent the outcome or its extent, such as "3/3 successful"; never empty
 * @param actor who asked for the run or did the journalled thing
 */
public record ActionEntry(String kind, Instant occurred, String extent, Actor actor) {

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
        row.put("actorKind", actor.kind().name());
        row.put("actorId", java.util.Objects.requireNonNullElse(actor.id(), ""));
        return row;
    }

    /**
     * A run from the run inbox.
     *
     * @param run the row, however it finished
     * @return the entry that describes it
     */
    static ActionEntry of(final UpdateRequest run) {
        final Instant occurred = run.finished() != null ? run.finished() : run.requested();
        return new ActionEntry(run.kind().name(), occurred, extentOf(run), run.actor());
    }

    /**
     * A line from {@code audit_log}: its action, or the sentence a line from before typed values still carries.
     *
     * @param entry the journal line
     * @return the entry that describes it
     */
    static ActionEntry of(final AuditEntry entry) {
        final JsonElement detail = entry.facts().get("detail");
        final String extent = detail != null
                        && detail.isJsonPrimitive()
                        && !detail.getAsString().isBlank()
                ? detail.getAsString()
                : entry.action();
        return new ActionEntry(entry.action(), entry.occurred(), extent, entry.actor());
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

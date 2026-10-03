package eu.nordtal.s2.steward.api;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.database.audit.AuditEntry;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.database.update.UpdateReport;
import eu.nordtal.s2.database.update.UpdateReports;
import eu.nordtal.s2.database.update.UpdateRequest;
import eu.nordtal.s2.database.update.UpdateStatus;
import eu.nordtal.s2.messages.MessageRef;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * One row of the actions feed: a run from the run inbox or a line from {@code audit_log}.
 *
 * @param kind the {@code UpdateKind} name for a run, or the {@code audit_log.action} for a journal line
 * @param occurred when this happened, by the database's clock
 * @param label what happened, as a heading
 * @param extent the outcome or its extent, such as "3 of 3 successful", or the journal's line
 * @param actor who asked for the run or did the journalled thing
 */
public record ActionEntry(String kind, Instant occurred, MessageRef label, MessageRef extent, Actor actor) {

    /** One row of {@code GET /api/actions}, with who did it flattened beside it. */
    public record Action(
            String kind, Instant occurred, MessageRef label, MessageRef extent, Actor.Kind actorKind, String actorId) {}

    /** This entry in the shape the browser reads. */
    public Action wire() {
        return new Action(kind, occurred, label, extent, actor.kind(), Objects.requireNonNullElse(actor.id(), ""));
    }

    /**
     * A run from the run inbox.
     *
     * @param run the row, however it finished
     * @return the entry that describes it
     */
    static ActionEntry of(final UpdateRequest run) {
        final Instant occurred = run.finished() != null ? run.finished() : run.requested();
        return new ActionEntry(run.kind().name(), occurred, TEXTS.run().kind(run.kind()), extentOf(run), run.actor());
    }

    /**
     * A line from {@code audit_log}, headed by its action, or by the action's own name when it is no longer listed.
     *
     * @param entry the journal line
     * @return the entry that describes it
     */
    static ActionEntry of(final AuditEntry entry) {
        final MessageRef label = Arrays.stream(JournalAction.values())
                .filter(action -> action.name().equals(entry.action()))
                .findFirst()
                .map(action -> TEXTS.journal().action(action))
                .orElseGet(() -> TEXTS.journal().written(entry.action()));
        return new ActionEntry(entry.action(), entry.occurred(), label, entry.line(), entry.actor());
    }

    /**
     * What to say a run amounted to.
     *
     * @param run a finished, running or pending request
     * @return how many of the touched services came back, else the report's stage, else the row's status
     */
    private static MessageRef extentOf(final UpdateRequest run) {
        if (run.status() == UpdateStatus.PENDING || run.status() == UpdateStatus.RUNNING) {
            return TEXTS.run().status(run.status());
        }
        final var report = UpdateReports.parse(run.result());
        if (report.isEmpty()) {
            return TEXTS.run().status(run.status());
        }
        final UpdateReport parsed = report.get();
        final long total =
                parsed.services().stream().filter(ActionEntry::touched).count();
        if (total == 0) {
            return TEXTS.run().stage(parsed.stage());
        }
        final long successful = parsed.services().stream()
                .filter(line -> line.state() == UpdateReport.State.HEALTHY || line.state() == UpdateReport.State.SAVED)
                .count();
        return TEXTS.run().successful(successful, total);
    }

    private static boolean touched(final UpdateReport.ServiceLine line) {
        return line.state() != UpdateReport.State.UNCHANGED && line.state() != UpdateReport.State.PLANNED;
    }
}

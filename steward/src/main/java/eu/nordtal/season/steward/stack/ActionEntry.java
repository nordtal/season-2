package eu.nordtal.season.steward.stack;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.audit.AuditEntry;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.update.UpdateReport;
import eu.nordtal.season.database.update.UpdateReports;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.MessageRef;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One row of the actions feed, from the run inbox or the journal.
 *
 * @param kind the {@code UpdateKind} name for a run, or the {@link JournalAction} name for a journal entry
 * @param occurred the database time of the entry
 * @param label the heading: the kind and stage of a run, or the action of a journal entry
 * @param extent the outcome of a run, or the line of a journal entry
 * @param actor the actor of the run or of the journal entry
 */
public record ActionEntry(String kind, Instant occurred, MessageRef label, MessageRef extent, Actor actor) {

    /** One row of {@code GET /api/actions}. */
    public record Action(
            String kind, Instant occurred, MessageRef label, MessageRef extent, Actor.Kind actorKind, String actorId) {}

    public Action wire() {
        return new Action(kind, occurred, label, extent, actor.kind(), Objects.requireNonNullElse(actor.id(), ""));
    }

    /** Returns the entry for a run of the run inbox. */
    static ActionEntry of(final UpdateRequest run) {
        final Instant occurred = run.finished() != null ? run.finished() : run.requested();
        final UpdateReport report = UpdateReports.parse(run.result()).orElse(null);
        final MessageRef label =
                report == null ? TEXTS.run().kind(run.kind()) : TEXTS.run().title(run.kind(), report.stage());
        return new ActionEntry(run.kind().name(), occurred, label, extentOf(run, report), run.actor());
    }

    /** Returns the entry for a journal entry. */
    static ActionEntry of(final AuditEntry entry) {
        return new ActionEntry(
                entry.action().name(),
                entry.occurred(),
                TEXTS.journal().action(entry.action()),
                entry.line(),
                entry.actor());
    }

    /**
     * Returns the number of successful services among the touched ones, else the status of the run.
     *
     * @param report the report of the run, or {@code null} when none parses
     */
    private static MessageRef extentOf(final UpdateRequest run, final @Nullable UpdateReport report) {
        if (report == null || run.status() == UpdateStatus.PENDING || run.status() == UpdateStatus.RUNNING) {
            return TEXTS.run().status(run.status());
        }
        final long total =
                report.services().stream().filter(ActionEntry::touched).count();
        if (total == 0) {
            return TEXTS.run().status(run.status());
        }
        final long successful = report.services().stream()
                .filter(line -> line.state() == UpdateReport.State.HEALTHY
                        || line.state() == UpdateReport.State.SAVED
                        || line.state() == UpdateReport.State.RESTORED)
                .count();
        return TEXTS.run().successful(successful, total);
    }

    private static boolean touched(final UpdateReport.ServiceLine line) {
        return line.state() != UpdateReport.State.UNCHANGED && line.state() != UpdateReport.State.PLANNED;
    }
}

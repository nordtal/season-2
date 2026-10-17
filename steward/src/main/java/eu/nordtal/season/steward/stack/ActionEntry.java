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
 * One row of the actions feed: a run from the run inbox or an entry of the journal.
 *
 * @param kind the {@code UpdateKind} name for a run, or the {@link JournalAction} name for a journal entry
 * @param occurred when this happened, by the database's clock
 * @param label what happened, as a heading: a run's kind and stage, or a journal line's action
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
        final UpdateReport report = UpdateReports.parse(run.result()).orElse(null);
        final MessageRef label =
                report == null ? TEXTS.run().kind(run.kind()) : TEXTS.run().title(run.kind(), report.stage());
        return new ActionEntry(run.kind().name(), occurred, label, extentOf(run, report), run.actor());
    }

    /**
     * An entry of the journal, headed by its action.
     *
     * @param entry the journal entry
     * @return the entry that describes it
     */
    static ActionEntry of(final AuditEntry entry) {
        return new ActionEntry(
                entry.action().name(),
                entry.occurred(),
                TEXTS.journal().action(entry.action()),
                entry.line(),
                entry.actor());
    }

    /**
     * What to say a run amounted to, below its title.
     *
     * @param run a finished, running or pending request
     * @param report its report, or null when it has none that parses
     * @return how many of the touched services came back, else the row's status
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

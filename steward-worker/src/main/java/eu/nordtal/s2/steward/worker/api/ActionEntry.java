package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.common.audit.AuditEntry;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReport;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateStatus;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One row of steward/82's unified feed: a run from {@code update_request} or a line from
 * {@code audit_log}, reduced to what the interface's five-item list actually draws - a kind, when
 * it happened, an outcome, and who is credited with it.
 *
 * <h2>Why one shape for two tables</h2>
 * The ticket's own words: a page that sorts and merges two lists is the place the third source gets
 * forgotten later. {@link ActionsApi} is the one query that knows about both tables; nothing past
 * this record does.
 *
 * <h2>Why the actor fields are empty strings, not {@code null}</h2>
 * This is a record, not the {@code LinkedHashMap} the rest of this package uses for a JSON body -
 * see {@code MessagesApi}'s note on a {@code null} map entry being dropped where a {@code null}
 * record field is instead written as the JSON literal. Rather than depend on that distinction (and
 * on whether the app-wide {@code Gson} ever turns {@code serializeNulls()} on), "no name" is spelled
 * as an empty string here, which every JSON representation writes the same way.
 *
 * @param kind           the {@link UpdateKind} name for a run, or the free-text
 *                        {@code audit_log.action} for a journal line - the interface's own map from
 *                        this string to an icon and a label
 * @param occurred        when this happened, by the database's clock
 * @param extent          the outcome or the extent of it - "3/3 successful", "30 days granted by
 *                         hm.till from the web interface, until ...", "Everything is already
 *                         current". Never empty
 * @param actorDiscordId  the Discord id to resolve through the roster, or {@code ""} when there is
 *                         none to resolve
 * @param actorLabel      plain text to show when there is an actor but no id to resolve it by -
 *                         {@code ""} otherwise
 * @param system          whether Steward itself is credited - the nightly backup clock, an orphan
 *                         settle, or a journal line the bot wrote with no admin behind it
 */
public record ActionEntry(@NotNull String kind, @NotNull Instant occurred, @NotNull String extent,
                          @NotNull String actorDiscordId, @NotNull String actorLabel,
                          boolean system) {

    /**
     * A trailing {@code (12345678901234567)} on a free-text requester - the shape a human's request
     * is written in wherever it started life as "name and id together" rather than as a bare
     * snowflake. See {@link #of(UpdateRequest)} for what happens to the rest of the string.
     */
    private static final Pattern TRAILING_SNOWFLAKE = Pattern.compile("^.*\\((\\d{17,20})\\)\\s*$");

    /**
     * A run from {@code update_request}.
     *
     * <h2>Reading who asked</h2>
     * {@code requested_by} is free text, and not uniformly shaped: this deployment's own rows carry
     * {@code "hm.till (594510749410525200)"} (a name with the id that goes with it, already
     * formatted for a person to read), {@code "steward-worker (nightly)"} (the clock, not a person),
     * and bare tool identifiers such as {@code "token-rotation-check"} that are not an account at
     * all. Only the first of those carries a raw Discord snowflake - and that snowflake is exactly
     * what {@code identifiers-stay-in-the-popover.test.ts} exists to keep out of plain text, so it is
     * extracted here into {@link #actorDiscordId} rather than shipped as part of a label the
     * interface would otherwise have to print unexamined. A row with no parenthesised id is shown as
     * plain text: there is nothing here that safely resolves "token-rotation-check" to a person, and
     * showing it as a person would be a guess this record has no business making.
     *
     * @param run the row, however it finished
     * @return the entry that describes it
     */
    static ActionEntry of(final @NotNull UpdateRequest run) {
        final Instant occurred = run.finished() != null ? run.finished() : run.requested();
        final String requestedBy = run.requestedBy();
        final boolean system = requestedBy == null || requestedBy.startsWith("steward-worker");
        String actorDiscordId = "";
        String actorLabel = "";
        if (!system) {
            final Matcher match = TRAILING_SNOWFLAKE.matcher(requestedBy);
            if (match.matches()) {
                actorDiscordId = match.group(1);
            } else {
                actorLabel = requestedBy;
            }
        }
        return new ActionEntry(run.kind().name(), occurred, extentOf(run), actorDiscordId,
                actorLabel, system);
    }

    /**
     * A line from {@code audit_log}. {@code actor} is already a clean Discord id or {@code null} -
     * unlike a run's free-text requester, nothing here needs to be picked apart.
     *
     * @param entry the journal line
     * @return the entry that describes it
     */
    static ActionEntry of(final @NotNull AuditEntry entry) {
        final boolean system = entry.actor() == null;
        final String extent = entry.detail() == null || entry.detail().isBlank()
                ? entry.action() : entry.detail();
        return new ActionEntry(entry.action(), entry.occurred(), extent,
                system ? "" : entry.actor(), "", system);
    }

    /**
     * What to say a run amounted to.
     *
     * <h2>The count is not the row's status</h2>
     * "Did any step report a failure" and "how much of what was touched came back" are different
     * questions - see {@link UpdateReport#savedSomething()} for the same distinction made about
     * backups specifically. This counts every service line the run actually touched (excluding
     * {@link UpdateReport.State#UNCHANGED} and {@link UpdateReport.State#PLANNED}, which are "the run
     * never got here" and "the run has not got here yet") against how many of those reached
     * {@link UpdateReport.State#HEALTHY} or {@link UpdateReport.State#SAVED} - a service back up, or
     * a volume written.
     *
     * @param run a finished, running or pending request
     * @return "n/m successful" when something was touched, the report's own stage headline when
     *         nothing was (a {@code REPORT} or a {@code NOTHING_TO_DO} run), or the row's own status
     *         word when the report cannot be read at all - a worker older than 2026-09-07 wrote
     *         plain text into this column, and a row that has not finished yet has no report to read
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
            return run.status() == UpdateStatus.CANCELLED ? "cancelled"
                    : run.status() == UpdateStatus.FAILED ? "failed" : "done";
        }
        final UpdateReport parsed = report.get();
        final long total = parsed.services().stream().filter(ActionEntry::touched).count();
        if (total == 0) {
            return parsed.stage().headline();
        }
        final long successful = parsed.services().stream()
                .filter(line -> line.state() == UpdateReport.State.HEALTHY
                        || line.state() == UpdateReport.State.SAVED)
                .count();
        return successful + "/" + total + " successful";
    }

    /** Whether the run actually did something to this service, rather than skipping past it. */
    private static boolean touched(final UpdateReport.ServiceLine line) {
        return line.state() != UpdateReport.State.UNCHANGED
                && line.state() != UpdateReport.State.PLANNED;
    }
}

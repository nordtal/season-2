package eu.nordtal.s2.common.update;

import java.time.Instant;

/**
 * One row of {@code update_request}: what was asked for, by whom, and - once an updater has been
 * through it - what happened.
 *
 * @param id          the primary key; what a caller keeps in order to read the answer back
 * @param kind        what was asked for
 * @param status      where it has got to
 * @param source      which surface asked
 * @param requestedBy a Discord id, a Minecraft name, or {@code null} for the console
 * @param requested   when the row was written
 * @param notBefore   the instant the updater may act. Equal to {@code requested} for everything
 *                    but a restart, whose countdown this is
 * @param started     when an updater claimed it, {@code null} while {@link UpdateStatus#PENDING}
 * @param finished    when it reached a terminal state, {@code null} until then
 * @param result      the report, verbatim - the same text {@code updater apply} prints. {@code null}
 *                    until finished
 */
public record UpdateRequest(long id,
                            UpdateKind kind,
                            UpdateStatus status,
                            UpdateSource source,
                            String requestedBy,
                            Instant requested,
                            Instant notBefore,
                            Instant started,
                            Instant finished,
                            String result) {

    /**
     * How long until this may run, from a caller's clock.
     *
     * <p>Clamped at zero rather than going negative: every caller of this is rendering a countdown,
     * and "-3 seconds" is not a thing to show a player.</p>
     *
     * @param now the instant to measure from
     * @return whole seconds remaining, never negative
     */
    public long secondsUntilDue(final Instant now) {
        final long seconds = notBefore.getEpochSecond() - now.getEpochSecond();
        return Math.max(0L, seconds);
    }
}

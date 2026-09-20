package eu.nordtal.s2.common.access;

import java.time.Instant;

/**
 * One row of {@code access_request}: what was asked for, about whom, by whom, and - once the bot
 * has been through it - what happened (season-2-community/08).
 *
 * @param id          the primary key; what a caller keeps in order to read the answer back
 * @param kind        what was asked for
 * @param status      where it has got to
 * @param subject     a Discord id, or a payment reference for {@link AccessRequestKind#SETTLE}
 * @param argument    days for a grant, seconds for a play time, {@code null} for the rest
 * @param source      which surface asked
 * @param requestedBy the asker's Discord id, or {@code null} for a request nobody signed
 * @param requested   when the row was written
 * @param expires     when to stop waiting. The bot refuses to claim a row past this
 * @param started     when the bot claimed it, {@code null} while {@link AccessRequestStatus#PENDING}
 * @param finished    when it reached a terminal state, {@code null} until then
 * @param result      what happened, as JSON. {@code null} until finished
 */
public record AccessRequest(long id,
                            AccessRequestKind kind,
                            AccessRequestStatus status,
                            String subject,
                            String argument,
                            AccessRequestSource source,
                            String requestedBy,
                            Instant requested,
                            Instant expires,
                            Instant started,
                            Instant finished,
                            String result) {

    /**
     * The argument as a number.
     *
     * <p>The column is text because it holds days in one row and seconds in the next, and a numeric
     * column that means two units is a column that means nothing. This is the one place that
     * conversion happens, so a malformed argument is one failure rather than one per caller.</p>
     *
     * @return the argument parsed as a long
     * @throws IllegalStateException when there is no argument, or it is not a number - either is a
     *                               row that should never have been written, and carrying on with a
     *                               zero would grant nobody anything and look like it worked
     */
    public long number() {
        if (argument == null || argument.isBlank()) {
            throw new IllegalStateException(kind + " request " + id + " carries no argument");
        }
        try {
            return Long.parseLong(argument.trim());
        } catch (final NumberFormatException notANumber) {
            throw new IllegalStateException(
                    kind + " request " + id + " carries a non-numeric argument", notANumber);
        }
    }
}

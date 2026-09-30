package eu.nordtal.s2.database.access;

import eu.nordtal.s2.database.Actor;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One row of {@code access_request}: what was asked for, about whom, by whom, and what happened.
 *
 * @param subject     a Discord id, or a payment reference for {@link AccessRequestKind#SETTLE}
 * @param argument    days for a grant, seconds for a play time, {@code null} for the rest
 * @param actor       who asked
 * @param expires     when to stop waiting; the bot refuses to claim a row past this
 * @param started     when the bot claimed it, {@code null} while {@link AccessRequestStatus#PENDING}
 * @param finished    when it reached a terminal state, {@code null} until then
 * @param result      what happened, as JSON, {@code null} until finished
 */
public record AccessRequest(
        long id,
        AccessRequestKind kind,
        AccessRequestStatus status,
        String subject,
        @Nullable String argument,
        Actor actor,
        Instant requested,
        Instant expires,
        @Nullable Instant started,
        @Nullable Instant finished,
        @Nullable String result) {

    /**
     * Returns the argument parsed as a long.
     *
     * @throws IllegalStateException when there is no argument or it is not a number, rather than carrying on with a
     *     zero
     */
    public long number() {
        if (argument == null || argument.isBlank()) {
            throw new IllegalStateException(kind + " request " + id + " carries no argument");
        }
        try {
            return Long.parseLong(argument.trim());
        } catch (final NumberFormatException notANumber) {
            throw new IllegalStateException(kind + " request " + id + " carries a non-numeric argument", notANumber);
        }
    }
}

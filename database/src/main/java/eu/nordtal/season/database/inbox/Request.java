package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.DatabaseJson;
import eu.nordtal.season.messages.Refusal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One row of an inbox table: what was asked, by whom, when it may run and what became of it.
 *
 * @param kind the kind name, as {@link InboxTable#kindOf} spells it
 * @param scheduledFor when the consumer may claim it. Never moves
 * @param expires when an unclaimed row stops waiting, null for never
 * @param outcome the answer of the consumer as JSON, null until written
 */
public record Request<P>(
        long id,
        String kind,
        P payload,
        InboxStatus status,
        Actor actor,
        Instant requested,
        Instant scheduledFor,
        @Nullable Instant expires,
        @Nullable Instant started,
        @Nullable Instant finished,
        @Nullable String outcome) {

    public Request {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(scheduledFor, "scheduledFor");
    }

    public <T> Optional<T> outcome(final Class<T> type) {
        return outcome == null ? Optional.empty() : Optional.ofNullable(DatabaseJson.decode(outcome, type));
    }

    /** Returns the refusal of a {@link InboxStatus#REFUSED} row, or empty for any other. */
    public Optional<Refusal> refusal() {
        return status == InboxStatus.REFUSED && outcome != null
                ? Optional.of(StoredRefusal.read(outcome))
                : Optional.empty();
    }
}

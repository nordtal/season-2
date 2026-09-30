package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.messages.Refusal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One row of an inbox table: what was asked for, by whom, when it may run and what became of it.
 *
 * @param kind         the kind's name, as {@link InboxTable#kindOf} spells it
 * @param payload      the kind's parameters
 * @param scheduledFor when the consumer may claim it; never moves
 * @param expires      when an unclaimed row stops waiting, {@code null} for never
 * @param outcome      the consumer's answer as JSON, {@code null} until it writes one
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

    /** Returns the answer read as {@code type}, or empty while there is none. */
    public <T> Optional<T> outcome(final Class<T> type) {
        return outcome == null ? Optional.empty() : Optional.ofNullable(Json.decode(outcome, type));
    }

    /** Returns the refusal a {@link InboxStatus#REFUSED} row carries, or empty for any other. */
    public Optional<Refusal> refusal() {
        return status == InboxStatus.REFUSED && outcome != null
                ? Optional.of(StoredRefusal.read(outcome))
                : Optional.empty();
    }
}

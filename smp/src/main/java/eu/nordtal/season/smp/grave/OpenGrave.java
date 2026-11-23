package eu.nordtal.season.smp.grave;

import java.time.Instant;
import java.util.Optional;

/** Holds the contents of a grave on the main thread. */
final class OpenGrave {

    private enum State {
        HOLDS_LOOT,
        AWAITING_LOOT,
        EXPIRED
    }

    private GraveRow row;
    private State state = State.HOLDS_LOOT;

    OpenGrave(final GraveRow row) {
        this.row = row;
    }

    GraveRow row() {
        return row;
    }

    Optional<byte[]> contentsForWindow() {
        return holdsLoot() ? Optional.of(row.contents()) : Optional.empty();
    }

    /** Returns whether a viewer may still take items, which ends once the grave is emptied or expires. */
    boolean holdsLoot() {
        return state == State.HOLDS_LOOT;
    }

    boolean expired() {
        return state == State.EXPIRED;
    }

    boolean expiresBy(final Instant cutoff) {
        return row.created().isBefore(cutoff);
    }

    void expire() {
        state = State.EXPIRED;
    }

    void settle(final Optional<byte[]> remainder) {
        state = remainder.isEmpty() ? State.AWAITING_LOOT : State.HOLDS_LOOT;
        row = row.withContents(remainder.orElseGet(() -> new byte[0]));
    }
}

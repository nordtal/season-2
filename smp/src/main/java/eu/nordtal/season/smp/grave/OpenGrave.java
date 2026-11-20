package eu.nordtal.season.smp.grave;

import java.util.Optional;

/** Holds the contents of a grave on the main thread. */
final class OpenGrave {

    private GraveRow row;
    private boolean awaitingLoot;

    OpenGrave(final GraveRow row) {
        this.row = row;
    }

    GraveRow row() {
        return row;
    }

    Optional<byte[]> contentsForWindow() {
        return awaitingLoot ? Optional.empty() : Optional.of(row.contents());
    }

    boolean awaitingLoot() {
        return awaitingLoot;
    }

    void settle(final Optional<byte[]> remainder) {
        awaitingLoot = remainder.isEmpty();
        row = row.withContents(remainder.orElseGet(() -> new byte[0]));
    }
}

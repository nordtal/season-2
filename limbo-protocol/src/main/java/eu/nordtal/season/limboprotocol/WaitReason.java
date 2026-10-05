package eu.nordtal.season.limboprotocol;

import java.util.Optional;

/**
 * Why a player is sitting in the waiting room, the whole content of {@code limbo}'s screen.
 * {@link #name()} travels on the wire, so renaming a constant breaks a proxy against another version.
 */
public enum WaitReason {

    /** The resource pack has been offered and not applied yet, the ordinary state of every login. */
    PACK,

    /** The phase's backend is not registered or refuses the connection; it resolves when the backend comes up. */
    BACKEND,

    /** The network is in {@code MAINTENANCE} and the player is not an admin; it ends when the phase changes. */
    MAINTENANCE,

    /**
     * An update run has the player's backend stopped and will have it back in a few minutes.
     * Unlike {@link #BACKEND} it is on purpose and nearly over, which the proxy reads from the update row.
     */
    UPDATE,

    /**
     * Somebody stopped the player's backend on purpose, with no automatic return.
     * The player is released once the backend takes a connection again.
     */
    HELD,

    /** The proxy has not said why yet; it carries a real text, because a blank screen looks like a crash. */
    UNKNOWN;

    /**
     * Parses a name off the wire, never throwing, so a newer proxy's reason degrades to a screen.
     *
     * @param name the value that arrived, may be {@code null}
     * @return the reason, or empty when the name is not one of these
     */
    public static Optional<WaitReason> parse(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (final WaitReason reason : values()) {
            if (reason.name().equals(name)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}

package eu.nordtal.s2.common.limbo;

import java.util.Optional;

/**
 * Why a player is sitting in the waiting room, the whole content of {@code limbo}'s screen.
 *
 * The proxy decides and tells {@code limbo} over {@link LimboProtocol#CHANNEL}. {@link #name()} travels,
 * so renaming a constant breaks a proxy against a backend of another version.
 */
public enum WaitReason {

    /** The resource pack has been offered and not applied yet, the ordinary state of every login. */
    PACK,

    /** The phase's backend is not registered or refuses the connection; it resolves when the backend comes up. */
    BACKEND,

    /** The network is in {@code MAINTENANCE} and the player is not an admin; it ends when the phase changes. */
    MAINTENANCE,

    /**
     * An update run has the player's backend stopped, and will have it back in a few minutes.
     *
     * Distinct from {@link #BACKEND} even though the proxy cannot tell the two apart by looking
     * at the server - both are "the destination is not taking connections". The difference is the
     * one the person on the black screen cares about: this one is somebody doing something on
     * purpose and is nearly over, and the other is an accident of unknown length. Getting that
     * wrong in either direction is what makes a waiting room feel broken, so the proxy decides it
     * from the update row rather than from the connection.
     */
    UPDATE,

    /**
     * Somebody stopped the player's backend on purpose and it stays stopped until they start it again.
     *
     * Unlike {@link #UPDATE} it promises no automatic return; the waiting room releases the player once the
     * backend takes a connection again.
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

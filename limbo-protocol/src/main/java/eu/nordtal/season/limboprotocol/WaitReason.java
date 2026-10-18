package eu.nordtal.season.limboprotocol;

import java.util.Optional;

/**
 * The reason a player waits in the waiting room. {@code limbo} shows the title of the reason.
 * The wire carries {@link #name()}.
 */
public enum WaitReason {

    /** The resource pack is offered and not applied yet. */
    PACK,

    /** The backend of the phase is not registered or refuses the connection. */
    BACKEND,

    /** The phase is {@code MAINTENANCE} and the player is not an admin. */
    MAINTENANCE,

    /** An update run has stopped the backend of the player. The proxy reads this from the update row. */
    UPDATE,

    /**
     * An admin stopped the backend of the player. The proxy sends the player on when the backend accepts connections.
     */
    HELD,

    /** The proxy has sent no reason yet. */
    UNKNOWN;

    /** Returns the reason with this name, or empty when the name is {@code null} or unknown. */
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

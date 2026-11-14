package eu.nordtal.season.proxy.pack;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Decides from the SHA-1 a player applied whether the pack is owed; a pack the client applied is not owed again.
 * It is asked when a player enters the waiting room and, on a change, for every player there.
 */
final class PackHash {

    private PackHash() {}

    /**
     * Returns whether the player is offered {@code current}.
     *
     * @param applied the pack the client last reported as applied
     * @param offered the newest pack offered, which may still wait for an answer
     */
    static boolean owed(
            final boolean exempt,
            final @Nullable String applied,
            final @Nullable String offered,
            final String current) {
        return !exempt && !current.equals(applied) && !current.equals(offered);
    }

    /**
     * Returns whether the pack lets the player leave the waiting room.
     *
     * That is when the client applied the newest pack it was offered.
     */
    static boolean settled(final boolean exempt, final @Nullable String applied, final @Nullable String offered) {
        return exempt || (applied != null && (offered == null || Objects.equals(applied, offered)));
    }
}

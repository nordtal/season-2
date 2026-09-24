package eu.nordtal.s2.common.limbo;

import java.util.Optional;

/**
 * Why a player is sitting in the waiting room - the entire content of {@code limbo}'s interface,
 * which is otherwise black with a title in the player's language.
 *
 * <p>The proxy decides which one and tells {@code limbo} over {@link LimboProtocol#CHANNEL}. Two of
 * the reasons are facts only the proxy has, and routing all of them the same way keeps the waiting
 * room's title from having two sources that disagree on the seam.
 *
 * <p>{@link #name()} is what travels, so these constants are protocol and renaming one breaks a
 * proxy against a backend of a different version.
 */
public enum WaitReason {

    /**
     * The resource pack has been offered and has not been applied yet - the ordinary state of every
     * login, and the reason {@code limbo} exists at all.
     */
    PACK,

    /**
     * The pack is applied and the player is ready, but the backend the current phase points at is
     * not registered or would not take the connection. Distinct from {@link #MAINTENANCE} because
     * it resolves itself the moment the backend comes up.
     */
    BACKEND,

    /**
     * The network is in {@code MAINTENANCE} and this player is not an admin. Unlike the other two it
     * does not end on its own: it ends when somebody switches the phase.
     */
    MAINTENANCE,

    /**
     * An update run has the player's backend stopped, and will have it back in a few minutes.
     *
     * <p>Distinct from {@link #BACKEND} even though the proxy cannot tell the two apart by looking
     * at the server - both are "the destination is not taking connections". The difference is the
     * one the person on the black screen cares about: this one is somebody doing something on
     * purpose and is nearly over, and the other is an accident of unknown length. Getting that
     * wrong in either direction is what makes a waiting room feel broken, so the proxy decides it
     * from the update row rather than from the connection.</p>
     */
    UPDATE,

    /**
     * Somebody stopped the player's backend on purpose and it stays stopped until they start it
     * again (season-2-ops/125).
     *
     * <p>Distinct from {@link #UPDATE} for the one thing that separates them: an update is nearly
     * over and brings the server back by itself, and this does not. The update screen promises
     * "you will be moved back automatically", which is a promise nothing can keep here - the
     * return is a person pressing a button, and it may be an hour. Telling the two apart is the
     * difference between a player waiting and a player waiting for something that is not coming.
     *
     * <p>It also does not end on its own, which puts it beside {@link #MAINTENANCE} rather than
     * beside {@link #BACKEND}: the waiting room keeps re-asking and releases them the moment the
     * backend takes a connection again, which is exactly when the Start button has been pressed.
     */
    HELD,

    /**
     * The player is in the waiting room and the proxy has not said why - the message has not arrived
     * yet, or there is no {@code proxy} on the proxy. It carries a real text because a
     * blank screen is indistinguishable from a crash to the person looking at it.
     */
    UNKNOWN;

    /**
     * Parses a name off the wire. Never throws: an unknown reason from a newer proxy has to
     * degrade to a screen that says something, not to an exception on a network path.
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

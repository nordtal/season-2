package eu.nordtal.s2.common.limbo;

import java.util.Optional;

/**
 * Why a player is sitting in the waiting room. This is the entire content of {@code limbo}'s
 * interface: docs/architecture.md settles that limbo shows "nothing. Black, no visible world, no
 * other players and no chat. A title in the player's language says what they are waiting for, and
 * that is the entire interface."
 *
 * <h2>Who decides which one</h2>
 * <b>The proxy does</b>, and it tells {@code limbo} over {@link LimboProtocol#CHANNEL}. That is
 * not an arbitrary split: two of these three reasons are facts only the proxy has. Whether the
 * pack has been applied is a {@code PlayerResourcePackStatusEvent} on the proxy, and whether the
 * phase's backend is registered and reachable is a question about {@code velocity.toml}'s server
 * list. {@code limbo} could read the phase itself - it has a database connection - but then one of
 * the three reasons would arrive by a different route than the other two, and a waiting room whose
 * title has two sources is a waiting room that shows the wrong title on the seam.
 *
 * <h2>Names on the wire</h2>
 * {@link #name()} is what travels, so these constants are protocol and renaming one is a
 * compatibility break between a proxy and a backend of different versions. {@link #UNKNOWN} is the
 * value a decoder falls back to, which is why the set is closed rather than open-ended.
 *
 * <p>The message key each reason renders through is {@link #titleKey()} /
 * {@link #subtitleKey()}, resolved against {@code messages/limbo/&lt;language&gt;.properties}.
 */
public enum WaitReason {

    /**
     * The resource pack has been offered and has not been applied yet - the ordinary state of
     * every login, and the reason {@code limbo} exists at all - see
     * docs/architecture.md#the-login-path-end-to-end.
     */
    PACK,

    /**
     * The pack is applied and the player is ready, but the backend the current phase points at is
     * not registered on this proxy or would not take the connection. Distinct from
     * {@link #MAINTENANCE} because it is an accident and maintenance is a decision - and because
     * this one resolves itself the moment the backend comes up, without anybody switching a phase.
     */
    BACKEND,

    /**
     * The network is in {@code MAINTENANCE} and this player is not an admin. Unlike the other two
     * this does not end on its own: it ends when somebody switches the phase, at which point
     * {@code PlayerRouter} re-routes everybody and this player leaves the waiting room.
     */
    MAINTENANCE,

    /**
     * The player is in the waiting room and the proxy has not said why - either the message has
     * not arrived yet (it is sent moments after the connection, so this is what the first tick
     * shows), or there is no {@code network-control} on the proxy at all.
     *
     * <p>It is a real reason with a real text rather than a blank screen, because "black screen,
     * no title, nothing happens" is indistinguishable from a crash to the person looking at it.
     */
    UNKNOWN;

    /** @return the message key for this reason's title line */
    public String titleKey() {
        return "limbo.wait." + name().toLowerCase(java.util.Locale.ROOT) + ".title";
    }

    /** @return the message key for this reason's subtitle line */
    public String subtitleKey() {
        return "limbo.wait." + name().toLowerCase(java.util.Locale.ROOT) + ".subtitle";
    }

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

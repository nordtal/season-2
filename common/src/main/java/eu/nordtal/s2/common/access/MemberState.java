package eu.nordtal.s2.common.access;

/**
 * Guild membership of a Discord account, as the bot last saw it.
 * <p>
 * The proxy cannot ask Discord anything, so this is a projection the bot maintains from guild
 * events plus a reconcile at startup. It decides whether a login is refused right now; it does
 * <b>not</b> pause a paid access period - a banned user's days keep running down.
 * </p>
 * <p>
 * The names are the exact strings stored in {@code discord_user.member_state}, which a database
 * {@code CHECK} constraint restricts to these three.
 * </p>
 */
public enum MemberState {

    /** In the guild and not banned. The only state that may join. */
    MEMBER,

    /** Was in the guild and is not any more. */
    LEFT,

    /** Banned from the guild. */
    BANNED;

    /**
     * Parses a value read from {@code discord_user.member_state}.
     *
     * @param value the stored string, may be {@code null}
     * @return the matching state, or {@link #LEFT} for {@code null} or anything unrecognised -
     *         an unreadable state must never be more permissive than the real one
     */
    public static MemberState fromDatabase(final String value) {
        if (value == null) {
            return LEFT;
        }
        for (final MemberState state : values()) {
            if (state.name().equalsIgnoreCase(value)) {
                return state;
            }
        }
        return LEFT;
    }
}

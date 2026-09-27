package eu.nordtal.s2.common.access;

/**
 * Guild membership of a Discord account, as the bot last saw it.
 * It decides whether a login is refused; it does not pause a paid access period.
 */
public enum MemberState {

    /** In the guild and not banned. The only state that may join. */
    MEMBER,

    /** Was in the guild and is not any more. */
    LEFT,

    /** Banned from the guild. */
    BANNED;

    /** Parses a stored {@code discord_user.member_state}, reading {@code null} or anything unknown as {@link #LEFT}. */
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

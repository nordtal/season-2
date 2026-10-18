package eu.nordtal.season.database.access;

/**
 * The guild membership of a Discord account, as the bot last saw it.
 * It decides whether a login is refused. It does not pause a paid access period.
 */
public enum MemberState {

    /** In the guild and not banned. Only this state may join. */
    MEMBER,

    /** Left the guild. */
    LEFT,

    /** Banned from the guild. */
    BANNED;

    /** Parses a stored {@code discord_user.member_state}. Null or an unknown value reads as {@link #LEFT}. */
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

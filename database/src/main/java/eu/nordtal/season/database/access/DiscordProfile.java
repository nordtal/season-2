package eu.nordtal.season.database.access;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What the bot last observed a Discord account to be called and pictured as in the guild.
 * It is a cache, not an identity: never resolve an account by name.
 *
 * @param usernameUpdated when {@code username} was last written, null together with it
 * @param displayName the guild nickname, null if unset or the account is no longer a member
 * @param avatarUrl the guild avatar, null for the same reasons as {@code displayName}
 */
public record DiscordProfile(
        @Nullable String username,
        @Nullable Instant usernameUpdated,
        @Nullable String displayName,
        @Nullable Instant displayNameUpdated,
        @Nullable String avatarUrl,
        @Nullable Instant avatarUrlUpdated) {

    /** All six fields empty, for an account without a mirrored profile. */
    public static final DiscordProfile EMPTY = new DiscordProfile(null, null, null, null, null, null);
}

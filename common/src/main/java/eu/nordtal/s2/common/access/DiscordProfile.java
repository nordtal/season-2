package eu.nordtal.s2.common.access;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What discord-bot last observed a Discord account to be called and pictured as in the guild.
 *
 * A cache, not an identity: two accounts may carry identical values, so never resolve an account by name.
 * Each field is nullable and timestamped on its own, since each goes stale on its own.
 *
 * @param username           the global Discord username, {@code null} if never observed
 * @param usernameUpdated    when {@code username} was last written, {@code null} together with it
 * @param displayName        the guild nickname, {@code null} when unset or no longer a member
 * @param displayNameUpdated when {@code displayName} was last written, {@code null} together with it
 * @param avatarUrl          the guild avatar, {@code null} for the same reasons as {@code displayName}
 * @param avatarUrlUpdated   when {@code avatarUrl} was last written, {@code null} together with it
 */
public record DiscordProfile(
        @Nullable String username,
        @Nullable Instant usernameUpdated,
        @Nullable String displayName,
        @Nullable Instant displayNameUpdated,
        @Nullable String avatarUrl,
        @Nullable Instant avatarUrlUpdated) {

    /** All six fields empty, the answer for an account nobody has mirrored a profile onto. */
    public static final DiscordProfile EMPTY = new DiscordProfile(null, null, null, null, null, null);
}

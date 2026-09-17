package eu.nordtal.s2.common.access;

import java.time.Instant;

/**
 * What discord-bot last <em>observed</em> a Discord account to be called and pictured as, in the
 * guild - a cache with a timestamp sitting beside {@code discord_user}'s identity, never a
 * replacement for it. See {@code V21__discord_and_minecraft_profile_cache.sql}.
 *
 * <p><b>A name is not a key.</b> {@code discordId} is what every other table in this schema is
 * joined on; nothing here is declared {@code UNIQUE}, and two different accounts may carry the exact
 * same values in every field of this record - two people can and do pick the same nickname. A
 * caller that resolves an account from a name instead of a {@code discordId} has built the
 * expensive confusion this class exists to prevent: two people quietly sharing one person's access.
 *
 * <p>Every field is independently nullable and independently timestamped, because the three facts
 * go stale independently: an account keeps its global username after leaving the guild, but loses
 * its guild nickname and guild avatar the moment it does - see
 * {@code eu.nordtal.s2.discordbot.discord.GuildState}.
 *
 * @param username           the global Discord username, without a discriminator; {@code null} if
 *                           never observed
 * @param usernameUpdated    when {@code username} was last written, {@code null} together with it
 * @param displayName        the <b>guild</b> nickname (not the global display name); {@code null}
 *                           when unset or when the account is not a member of the guild any more
 * @param displayNameUpdated when {@code displayName} was last written, {@code null} together with it
 * @param avatarUrl          the <b>guild</b> avatar; {@code null} for the same reasons as
 *                           {@code displayName}
 * @param avatarUrlUpdated   when {@code avatarUrl} was last written, {@code null} together with it
 */
public record DiscordProfile(
        String username, Instant usernameUpdated,
        String displayName, Instant displayNameUpdated,
        String avatarUrl, Instant avatarUrlUpdated) {

    /**
     * All six fields empty - what an account nobody has ever mirrored a Discord profile onto reads
     * as, and what {@link AccessDirectory#discordProfile(String)} answers for an unknown
     * {@code discordId} rather than throwing or returning {@code null}.
     */
    public static final DiscordProfile EMPTY = new DiscordProfile(null, null, null, null, null, null);
}

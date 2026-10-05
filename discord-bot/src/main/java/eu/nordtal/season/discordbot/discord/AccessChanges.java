package eu.nordtal.season.discordbot.discord;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;

/**
 * The access changes as the bot carries them out, each filed under the {@link Actor} who asked.
 *
 * It is what {@link BotInbox} dispatches to, so the inbox is testable without JDA.
 */
public interface AccessChanges {

    /** Adds days of access, applies the role and tells them, returning when access now runs until. */
    Instant grant(DiscordId discordId, int days, Actor by);

    /** Takes every running grant away, removes the role and tells them, returning how many went. */
    int revoke(DiscordId discordId, Actor by);

    /** Breaks the link between a Discord account and a Minecraft one. */
    boolean unlink(DiscordId discordId, Actor by);

    /** Writes somebody's total play time, in seconds. */
    void setPlaytime(DiscordId discordId, long seconds, Actor by);
}

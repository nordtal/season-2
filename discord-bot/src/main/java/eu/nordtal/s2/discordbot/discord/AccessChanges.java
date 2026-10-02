package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.id.DiscordId;
import java.time.Instant;

/**
 * The access changes as the bot carries them out, for a caller with only an {@link Actor}.
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

    /**
     * Re-reads the message bundles, keeping the running ones on failure.
     *
     * @return {@code true} when the re-read succeeded
     */
    boolean reloadMessages();

    /** Returns the override keys the bundles do not declare, after a reload. */
    java.util.List<String> unknownOverrideKeys();
}

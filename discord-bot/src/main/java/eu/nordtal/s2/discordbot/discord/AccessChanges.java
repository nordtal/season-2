package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.access.AccessEffects;
import java.time.Instant;

/**
 * The five access changes as the bot carries them out, for a caller with only an {@link Actor}.
 *
 * It is what {@link AccessInbox} dispatches to, so the inbox is testable without JDA.
 */
public interface AccessChanges {

    /** Adds days of access, applies the role and tells them, returning when access now runs until. */
    Instant grant(String discordId, int days, Actor by);

    /** Takes every running grant away, removes the role and tells them, returning how many went. */
    int revoke(String discordId, Actor by);

    /** Breaks the link between a Discord account and a Minecraft one. */
    boolean unlink(String discordId, Actor by);

    /** Books a payment by hand. */
    AccessEffects.Settled settle(String reference, Actor by);

    /** Writes somebody's total play time, in seconds. */
    void setPlaytime(String discordId, long seconds, Actor by);

    /**
     * Re-reads the message bundles, keeping the running ones on failure.
     *
     * @return {@code true} when the re-read succeeded
     */
    boolean reloadMessages();

    /** Returns the override keys the bundles do not declare, after a reload. */
    java.util.List<String> unknownOverrideKeys();
}

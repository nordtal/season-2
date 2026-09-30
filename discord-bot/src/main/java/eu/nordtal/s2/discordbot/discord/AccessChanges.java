package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.common.id.DiscordId;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The five access changes as the bot carries them out, for a caller with only an {@link Actor}.
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

    /** Books a payment by hand. */
    Settled settle(String reference, Actor by);

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

    /**
     * The outcome of {@link #settle}.
     *
     * @param outcome which of the three happened
     * @param until   when the access it bought runs until, for {@link Settlement#BOOKED} only
     * @param days    how many days it bought
     * @param status  the status a {@link Settlement#NOT_OPEN} request was actually in, so the refusal can name it
     */
    record Settled(
            Settlement outcome,
            @Nullable Instant until,
            int days,
            @Nullable String status) {}

    /** The three ways {@link #settle} can end. */
    enum Settlement {

        /** No request carries that reference. */
        UNKNOWN,

        /** It exists and is not open, so there is nothing to book. */
        NOT_OPEN,

        /** Booked. */
        BOOKED
    }
}

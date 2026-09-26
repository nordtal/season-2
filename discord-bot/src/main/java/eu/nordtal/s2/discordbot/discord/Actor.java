package eu.nordtal.s2.discordbot.discord;

import eu.nordtal.s2.commands.NordtalUser;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Whoever asked for an access change, reduced to the three things carrying one out needs
 * (season-2-community/08).
 *
 * <h2>Why this exists rather than a {@code NordtalUser}</h2>
 * A command hands over a {@link NordtalUser}, which is a whole request: a locale, a reply channel, a
 * permission answer. A row in {@code access_request} hands over a Discord id and nothing else, and
 * there is no request left to reply to by the time the bot reads it - the asker's HTTP call returned
 * long ago. Both are the same three facts as far as {@link BotAccessEffects} is concerned: what to
 * file the audit entry against, how to name them in the admin channel, and which Minecraft account
 * was theirs.
 *
 * <p>So this is the seam the ticket asks for. The effects take one of these; the
 * {@code AccessEffects} methods a command calls build one and delegate. Nothing about carrying a
 * grant out depends on a command any more, which is what lets the same code sit behind the inbox.</p>
 *
 * @param filed         what the audit column records - a Discord id when there is one, a readable
 *                      name otherwise. The column is free text for exactly that reason: a foreign
 *                      key would mean an action taken by an admin who has not linked could not be
 *                      recorded at all
 * @param mention       the same, as something that renders in the admin channel
 * @param minecraftUuid their Minecraft account, or {@code null} when this surface knows of none
 */
public record Actor(String filed, String mention, UUID minecraftUuid) {

    public Actor {
        Objects.requireNonNull(filed, "filed");
        Objects.requireNonNull(mention, "mention");
    }

    /** Whoever typed a command. */
    public static Actor of(final NordtalUser by) {
        Objects.requireNonNull(by, "by");
        final Optional<String> discordId = by.discordId();
        return new Actor(
                discordId.orElseGet(by::name),
                discordId.map(id -> "<@" + id + ">").orElseGet(() -> "`" + by.name() + "`"),
                by.minecraftUuid().orElse(null));
    }

    /**
     * Whoever a {@code access_request} row names.
     *
     * <p>The row carries a Discord id and no Minecraft account: the surfaces that write one are web
     * interfaces, where the person is signed in as a Discord account and nothing else. An audit
     * entry from here therefore has no UUID column, which is honest - inventing one by looking up
     * the asker's link would record a Minecraft account that took no part in the action.</p>
     *
     * @param discordId the row's {@code requested_by}, or {@code null} for a row nobody signed
     */
    public static Actor asked(final String discordId) {
        return discordId == null || discordId.isBlank()
                // A row with no asker is a sweep or a hand-written insert. It is filed under a name
                // rather than left blank, because an empty actor column reads as a bug.
                ? new Actor("unsigned", "`an unsigned request`", null)
                : new Actor(discordId, "<@" + discordId + ">", null);
    }
}

package eu.nordtal.s2.discordbot.discord;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Whoever asked for an access change: what the audit files it under, how the admin channel names them, their account.
 *
 * @param filed what the audit column records: a Discord id when there is one, a readable name otherwise
 * @param mention the same, as something that renders in the admin channel.
 * @param minecraftUuid their Minecraft account, or {@code null} when this surface knows of none.
 */
public record Actor(String filed, String mention, @Nullable UUID minecraftUuid) {

    public Actor {
        Objects.requireNonNull(filed, "filed");
        Objects.requireNonNull(mention, "mention");
    }

    /**
     * Returns whoever an {@code access_request} row names, with no Minecraft account.
     *
     * @param discordId the row's {@code requested_by}, or {@code null} for a row nobody signed
     */
    public static Actor asked(final @Nullable String discordId) {
        return discordId == null || discordId.isBlank()
                // A row with no asker is a sweep or a hand-written insert.
                ? new Actor("unsigned", "`an unsigned request`", null)
                : new Actor(discordId, "<@" + discordId + ">", null);
    }
}

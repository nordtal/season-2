package eu.nordtal.s2.discordbot.discord;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Whoever asked for an access change: what the audit files it under, how the admin channel names them, their account.
 *
 * @param filed the Discord id the audit records, or {@code null} for Steward and the host, which the audit reads as
 *     the system
 * @param mention the same, as something that renders in the admin channel.
 * @param minecraftUuid their Minecraft account, or {@code null} when this surface knows of none.
 */
public record Actor(
        @Nullable String filed, String mention, @Nullable UUID minecraftUuid) {

    public Actor {
        Objects.requireNonNull(mention, "mention");
    }

    /** Returns whoever an {@code access_request} row names, with no Minecraft account. */
    public static Actor asked(final eu.nordtal.s2.database.Actor asker) {
        return switch (asker.kind()) {
            case PERSON -> new Actor(asker.id(), "<@" + asker.id() + ">", null);
            case STEWARD -> new Actor(null, "Steward", null);
            case HOST -> new Actor(null, "the host", null);
        };
    }
}

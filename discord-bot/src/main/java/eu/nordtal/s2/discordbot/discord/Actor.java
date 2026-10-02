package eu.nordtal.s2.discordbot.discord;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Whoever asked for an access change: what the journal files it under, how the admin channel names them, their account.
 *
 * @param filed the actor the journal records
 * @param mention the same, as something that renders in the admin channel.
 * @param minecraftUuid their Minecraft account, or {@code null} when this surface knows of none.
 */
public record Actor(
        eu.nordtal.s2.database.Actor filed,
        String mention,
        @Nullable UUID minecraftUuid) {

    public Actor {
        Objects.requireNonNull(filed, "filed");
        Objects.requireNonNull(mention, "mention");
    }

    /** Returns whoever a request in the bot's inbox names, with no Minecraft account. */
    public static Actor asked(final eu.nordtal.s2.database.Actor asker) {
        return switch (asker.kind()) {
            case PERSON -> new Actor(asker, "<@" + asker.id() + ">", null);
            case STEWARD -> new Actor(asker, "Steward", null);
            case HOST -> new Actor(asker, "the host", null);
        };
    }
}

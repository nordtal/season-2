package eu.nordtal.season.discordbot.discord;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import java.time.Instant;

/** The access changes that {@link BotInbox} runs, each recorded under the {@link Actor} who asked. */
public interface AccessChanges {

    /** Adds days of access, applies the role, sends a direct message and returns the end of the access. */
    Instant grant(DiscordId discordId, int days, Actor by);

    /** Revokes every running grant, removes the role, sends a direct message and returns the number of grants. */
    int revoke(DiscordId discordId, Actor by);

    /** Removes the link between a Discord account and a Minecraft account. Returns whether a link existed. */
    boolean unlink(DiscordId discordId, Actor by);

    void setPlaytime(DiscordId discordId, long seconds, Actor by);
}

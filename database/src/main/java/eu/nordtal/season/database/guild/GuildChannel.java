package eu.nordtal.season.database.guild;

import java.util.Objects;

/**
 * One channel of the guild as discord-bot sees it, categories included.
 *
 * @param id   the channel's Discord id
 * @param name the channel's name, without the {@code #}
 * @param type Discord's channel type, such as 0 for text, 2 for voice and 4 for a category
 */
public record GuildChannel(String id, String name, int type) {

    public GuildChannel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}

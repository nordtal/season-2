package eu.nordtal.season.database.guild;

import java.util.Objects;

/**
 * One channel of the guild, categories included.
 *
 * @param name the name without the {@code #}
 * @param type Discord's channel type, such as 0 for text, 2 for voice and 4 for a category
 */
public record GuildChannel(String id, String name, int type) {

    public GuildChannel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}

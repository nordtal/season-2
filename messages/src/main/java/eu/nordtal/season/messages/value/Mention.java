package eu.nordtal.season.messages.value;

import eu.nordtal.season.common.id.DiscordId;
import java.util.Objects;

/**
 * A Discord member. Discord renders it as a mention and every other target as {@code @name}.
 *
 * @param name the name the guild shows for the member
 */
public record Mention(DiscordId member, String name) {

    public Mention {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(name, "name");
    }

    /** Creates a mention that uses the member id as the name, for a line written without waiting for Discord. */
    public static Mention of(final DiscordId member) {
        return new Mention(member, member.value());
    }
}

package eu.nordtal.season.messages.value;

import eu.nordtal.season.common.id.DiscordId;
import java.util.Objects;

/**
 * A Discord member, which Discord renders as a mention and every other target as {@code @name}.
 *
 * @param member the member
 * @param name   the name the guild shows for them
 */
public record Mention(DiscordId member, String name) {

    public Mention {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(name, "name");
    }

    /** A member known by id alone, named by it, for a line written without waiting for Discord. */
    public static Mention of(final DiscordId member) {
        return new Mention(member, member.value());
    }
}

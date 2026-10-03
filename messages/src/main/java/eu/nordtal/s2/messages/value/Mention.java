package eu.nordtal.s2.messages.value;

import eu.nordtal.s2.common.id.DiscordId;
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
}

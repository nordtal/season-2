package eu.nordtal.season.messages.value;

import eu.nordtal.season.common.id.DiscordId;
import java.util.Objects;

/**
 * A Discord member, or a role. Discord renders it as a mention and every other target as {@code @name}.
 *
 * @param member the id of the member, or of the role when {@code role} is true
 * @param name the name the guild shows for the member or the role
 */
public record Mention(DiscordId member, String name, boolean role) {

    public Mention {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(name, "name");
    }

    public Mention(final DiscordId member, final String name) {
        this(member, name, false);
    }

    /** Creates a mention that uses the member id as the name, for a line written without waiting for Discord. */
    public static Mention of(final DiscordId member) {
        return new Mention(member, member.value());
    }

    /** Creates a mention of a role that uses its id as the name. */
    public static Mention role(final DiscordId role) {
        return new Mention(role, role.value(), true);
    }
}

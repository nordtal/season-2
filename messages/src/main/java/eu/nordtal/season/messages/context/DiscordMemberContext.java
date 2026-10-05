package eu.nordtal.season.messages.context;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Mention;

/**
 * A member of the Discord guild: a mention in Discord, {@code @name} everywhere else.
 *
 * @param name the member and the name the guild shows for them
 */
@ContextType(value = "discord-member", name = "Discord member")
public record DiscordMemberContext(@Example("Alex") Mention name) implements MessageContext {

    /** Returns the member {@code member}, shown outside Discord as {@code name}. */
    public DiscordMemberContext(final DiscordId member, final String name) {
        this(new Mention(member, name));
    }
}

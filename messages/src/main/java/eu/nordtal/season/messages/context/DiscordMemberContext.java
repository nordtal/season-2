package eu.nordtal.season.messages.context;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Mention;

/** A member of the Discord guild. Discord shows a mention, other targets show {@code @name}. */
@ContextType(value = "discord-member", name = "Discord member")
public record DiscordMemberContext(@Example("Alex") Mention name) implements MessageContext {

    public DiscordMemberContext(final DiscordId member, final String name) {
        this(new Mention(member, name));
    }
}

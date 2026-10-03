package eu.nordtal.s2.messages.context;

import eu.nordtal.s2.messages.value.Example;

/**
 * A member of the Discord guild, by the name the guild shows.
 *
 * @param name its name
 */
@ContextType(value = "discord-member", name = "Discord member")
public record DiscordMemberContext(@Example("Alex") String name) implements MessageContext {}

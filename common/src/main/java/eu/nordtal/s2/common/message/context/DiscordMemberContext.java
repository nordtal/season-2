package eu.nordtal.s2.common.message.context;

/**
 * A member of the Discord guild, by the name the guild shows.
 *
 * @param name its name
 */
@ContextType(value = "discord-member", name = "Discord member")
public record DiscordMemberContext(String name) implements MessageContext {}

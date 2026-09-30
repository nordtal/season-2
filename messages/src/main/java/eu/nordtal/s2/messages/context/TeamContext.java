package eu.nordtal.s2.messages.context;

/**
 * A hunger games team.
 *
 * @param name its name
 */
@ContextType(value = "team", name = "Team")
public record TeamContext(String name) implements MessageContext {}

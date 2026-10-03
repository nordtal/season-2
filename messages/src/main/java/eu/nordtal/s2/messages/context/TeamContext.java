package eu.nordtal.s2.messages.context;

import eu.nordtal.s2.messages.value.Example;

/**
 * A hunger games team.
 *
 * @param name its name
 */
@ContextType(value = "team", name = "Team")
public record TeamContext(@Example("Red") String name) implements MessageContext {}

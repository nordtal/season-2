package eu.nordtal.season.messages.context;

import eu.nordtal.season.messages.value.Example;

/**
 * A hunger games team.
 *
 * @param name its name
 */
@ContextType(value = "team", name = "Team")
public record TeamContext(@Example("Red") String name) implements MessageContext {}

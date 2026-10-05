package eu.nordtal.season.messages.context;

import eu.nordtal.season.messages.value.Example;

/**
 * A milestone of the season's shared track.
 *
 * @param name its name
 */
@ContextType(value = "milestone", name = "Milestone")
public record MilestoneContext(@Example("The Nether") String name) implements MessageContext {}

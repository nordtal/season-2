package eu.nordtal.s2.messages.context;

import eu.nordtal.s2.messages.value.Example;

/**
 * A milestone of the season's shared track.
 *
 * @param name its name
 */
@ContextType(value = "milestone", name = "Milestone")
public record MilestoneContext(@Example("The Nether") String name) implements MessageContext {}

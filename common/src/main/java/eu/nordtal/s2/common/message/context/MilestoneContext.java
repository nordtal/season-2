package eu.nordtal.s2.common.message.context;

/**
 * A milestone of the season's shared track.
 *
 * @param name its name
 */
@ContextType(value = "milestone", name = "Milestone")
public record MilestoneContext(String name) implements MessageContext {}

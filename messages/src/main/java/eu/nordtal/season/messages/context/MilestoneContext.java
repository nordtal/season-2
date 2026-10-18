package eu.nordtal.season.messages.context;

import eu.nordtal.season.messages.value.Example;

@ContextType(value = "milestone", name = "Milestone")
public record MilestoneContext(@Example("The Nether") String name) implements MessageContext {}

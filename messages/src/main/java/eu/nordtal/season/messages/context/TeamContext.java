package eu.nordtal.season.messages.context;

import eu.nordtal.season.messages.value.Example;

@ContextType(value = "team", name = "Team")
public record TeamContext(@Example("Red") String name) implements MessageContext {}

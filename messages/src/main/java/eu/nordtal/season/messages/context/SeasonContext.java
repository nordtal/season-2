package eu.nordtal.season.messages.context;

import eu.nordtal.season.messages.value.Example;

/** The season from the network's settings. Every message can name it. */
@ContextType(value = "season", name = "Season")
public record SeasonContext(
        @Example("2") int number, @Example("Season 2") String name) implements MessageContext {}

package eu.nordtal.s2.messages.context;

import eu.nordtal.s2.messages.value.Example;

/**
 * The season this network runs, out of the network's settings; available in every message.
 *
 * @param number the season's number
 * @param name what a player reads for it, {@code Season 2}
 */
@ContextType(value = "season", name = "Season")
public record SeasonContext(
        @Example("2") int number, @Example("Season 2") String name) implements MessageContext {}

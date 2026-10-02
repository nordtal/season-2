package eu.nordtal.s2.messages.context;

/**
 * The season this network runs, out of the network's settings; available in every message.
 *
 * @param number the season's number
 * @param name what a player reads for it, {@code Season 2}
 */
@ContextType(value = "season", name = "Season")
public record SeasonContext(int number, String name) implements MessageContext {}

package eu.nordtal.s2.common.message.context;

/**
 * The season this network runs; available in every message.
 *
 * @param number the season's number
 */
@ContextType(value = "season", name = "Season")
public record SeasonContext(int number) implements MessageContext {

    /** The season this code belongs to. Each season is its own repository, so this never changes in it. */
    public static final SeasonContext CURRENT = new SeasonContext(2);
}

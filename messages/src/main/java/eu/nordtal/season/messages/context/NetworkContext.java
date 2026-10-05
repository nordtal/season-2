package eu.nordtal.season.messages.context;

import eu.nordtal.season.messages.value.Example;

/**
 * The network as a whole; available in every message.
 *
 * @param name what a player reads for it
 */
@ContextType(value = "network", name = "Network")
public record NetworkContext(@Example("nordtal") String name) implements MessageContext {

    /** The network this code runs. */
    public static final NetworkContext NORDTAL = new NetworkContext("nordtal");
}

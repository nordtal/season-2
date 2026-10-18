package eu.nordtal.season.messages.context;

import eu.nordtal.season.messages.value.Example;

/** The network. Every message can name it. */
@ContextType(value = "network", name = "Network")
public record NetworkContext(@Example("Nordtal") String name) implements MessageContext {

    public static final NetworkContext NORDTAL = new NetworkContext("Nordtal");
}

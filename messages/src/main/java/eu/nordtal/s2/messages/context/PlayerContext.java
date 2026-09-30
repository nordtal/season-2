package eu.nordtal.s2.messages.context;

/**
 * A player on the network, by their Minecraft name.
 *
 * @param name its name
 */
@ContextType(value = "player", name = "Player")
public record PlayerContext(String name) implements MessageContext {}

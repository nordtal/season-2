package eu.nordtal.season.messages.context;

/**
 * Something a message is about, such as a player.
 * An implementation is a record annotated with {@link ContextType}.
 * Each component is a placeholder, such as {@code {sender.name}}.
 */
public interface MessageContext {}

package eu.nordtal.season.messages.context;

/**
 * Something a message is about, such as a player, handed over as a whole.
 * An implementation is a record annotated {@link ContextType}; each component is a placeholder such as {@code
 * {sender.name}}.
 */
public interface MessageContext {}

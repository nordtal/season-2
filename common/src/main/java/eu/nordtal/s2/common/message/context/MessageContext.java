package eu.nordtal.s2.common.message.context;

/**
 * Something a message is about, such as a player, handed over as a whole.
 *
 * An implementation is a record annotated {@link ContextType}; each component is a placeholder such as
 * {@code {sender.name}}, and a component joins only when every builder of the type can fill it.
 */
public interface MessageContext {}

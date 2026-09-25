package eu.nordtal.s2.common.message.context;

/**
 * Something a message is about - a player, a team, a service - handed to a message as a whole
 * rather than as one value at a time.
 *
 * <p>An implementation is a record annotated {@link ContextType}, and each of its components is a
 * placeholder: a spec parameter {@code @Arg("sender") PlayerContext sender} makes
 * <code>{sender.name}</code> available to the text, and a component added to {@link PlayerContext}
 * later is available in every message that has a player in it, without touching a spec. A component
 * joins a type only when every place that builds the type can fill it, so a text never meets a
 * placeholder that is sometimes empty.</p>
 */
public interface MessageContext {
}

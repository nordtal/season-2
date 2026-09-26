package eu.nordtal.s2.common.message.context;

/**
 * A service of the network, such as smp; as the global role {@code server}, the one showing the text.
 *
 * @param name its name
 */
@ContextType(value = "service", name = "Service")
public record ServiceContext(String name) implements MessageContext {}

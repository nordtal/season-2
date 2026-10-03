package eu.nordtal.s2.messages.context;

import eu.nordtal.s2.messages.value.Example;

/**
 * A service of the network, such as smp; as the global role {@code server}, the one showing the text.
 *
 * @param name its name
 */
@ContextType(value = "service", name = "Service")
public record ServiceContext(@Example("smp") String name) implements MessageContext {}

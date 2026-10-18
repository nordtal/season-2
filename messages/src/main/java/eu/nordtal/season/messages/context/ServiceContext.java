package eu.nordtal.season.messages.context;

import eu.nordtal.season.messages.value.Example;

/** A service of the network, such as smp. The global role {@code server} is the showing service. */
@ContextType(value = "service", name = "Service")
public record ServiceContext(@Example("smp") String name) implements MessageContext {}

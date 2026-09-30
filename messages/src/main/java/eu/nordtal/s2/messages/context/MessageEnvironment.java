package eu.nordtal.s2.messages.context;

import java.util.Map;

/**
 * The contexts every message of one process can name, {@code server} and {@code season}, handed in at startup.
 *
 * @param globals role to context; empty where no process is named, which leaves those placeholders as written
 */
public record MessageEnvironment(Map<String, MessageContext> globals) {

    /** No process: {@code {server.name}} and {@code {season.number}} stay as written. */
    public static final MessageEnvironment NONE = new MessageEnvironment(Map.of());

    public MessageEnvironment {
        globals = Map.copyOf(globals);
    }

    /** Returns the environment of the service called {@code service}, in the season this code belongs to. */
    public static MessageEnvironment of(final String service) {
        return new MessageEnvironment(Map.of("server", new ServiceContext(service), "season", SeasonContext.CURRENT));
    }
}

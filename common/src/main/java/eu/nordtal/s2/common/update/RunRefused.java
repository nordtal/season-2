package eu.nordtal.s2.common.update;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A run that was not written, because the network already has one or the service is already down.
 *
 * Thrown by {@link UpdateDirectory#submit}, and only there, because that is where every source
 * submits: the interface, Discord, the game console and the nightly clock. A check in one client
 * would leave the other three free to start a second run beside the first - which is how pressing
 * Take down twice once counted down and stopped a server that was already held.
 *
 * Unchecked, like every other failure of the directory: a caller that does not translate it
 * still fails loudly rather than reporting a run that does not exist.
 */
public final class RunRefused extends RuntimeException {

    public enum Reason {
        /** Another run is pending or running; {@link #open()} names it. */
        RUN_OPEN,
        /** A take-down named services that are already held; {@link #services()} names them. */
        ALREADY_HELD
    }

    private final Reason reason;
    private final @Nullable UpdateRequest open;
    private final List<String> services;

    private RunRefused(
            final Reason reason,
            final @Nullable UpdateRequest open,
            final List<String> services,
            final String message) {
        super(message);
        this.reason = reason;
        this.open = open;
        this.services = List.copyOf(services);
    }

    public static RunRefused runOpen(final UpdateRequest open) {
        return new RunRefused(
                Reason.RUN_OPEN,
                open,
                List.of(),
                "run " + open.id() + " (" + open.kind() + ") is still "
                        + open.status().name().toLowerCase(Locale.ROOT));
    }

    public static RunRefused alreadyHeld(final List<String> services) {
        return new RunRefused(
                Reason.ALREADY_HELD,
                null,
                services,
                String.join(", ", services) + (services.size() == 1 ? " is" : " are") + " already down");
    }

    public Reason reason() {
        return reason;
    }

    /** The run in the way, for {@link Reason#RUN_OPEN}; {@code null} otherwise. */
    public @Nullable UpdateRequest open() {
        return open;
    }

    /** The services already held, for {@link Reason#ALREADY_HELD}; empty otherwise. */
    public List<String> services() {
        return services;
    }
}

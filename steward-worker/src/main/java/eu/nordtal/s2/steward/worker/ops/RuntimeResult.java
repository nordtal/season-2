package eu.nordtal.s2.steward.worker.ops;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What came of asking the container runtime for the project's services.
 *
 * <p>Two answers rather than an {@code Optional}, because "the daemon did not answer" is the one
 * that has to stop the whole run before anything is touched, and the sentence explaining it is what
 * a person reads days later. An empty list from a daemon that did answer is a different thing
 * entirely - a project with no containers - and would be silently identical under an
 * {@code Optional}.</p>
 *
 * @param reached  whether the runtime could be read at all
 * @param services what was found, empty when it could not
 * @param message  why not, or {@code null} when it could
 */
public record RuntimeResult(
        boolean reached,
        @NotNull List<ServiceRuntime> services,
        @Nullable String message) {

    public RuntimeResult {
        services = List.copyOf(services);
    }

    public static RuntimeResult of(final @NotNull List<ServiceRuntime> services) {
        return new RuntimeResult(true, services, null);
    }

    public static RuntimeResult unreachable(final @NotNull String message) {
        return new RuntimeResult(false, List.of(), message);
    }

    /** @return the entry for that compose service, if the project has a container for it */
    public Optional<ServiceRuntime> service(final @NotNull String name) {
        return services.stream().filter(entry -> entry.service().equals(name)).findFirst();
    }
}

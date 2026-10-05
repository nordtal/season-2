package eu.nordtal.season.internalapi.agent;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What came of asking the runtime for the project's services; an unreached daemon differs from an empty project.
 *
 * @param reached whether the runtime could be read at all
 * @param services what was found, empty when it could not
 * @param message why not, or {@code null} when it could
 */
public record RuntimeResult(
        boolean reached,
        List<ServiceRuntime> services,
        @Nullable String message) {

    public RuntimeResult {
        services = List.copyOf(services);
    }

    public static RuntimeResult of(final List<ServiceRuntime> services) {
        return new RuntimeResult(true, services, null);
    }

    public static RuntimeResult unreachable(final String message) {
        return new RuntimeResult(false, List.of(), message);
    }

    /** Returns the entry for that compose service, if the project has a container for it. */
    public Optional<ServiceRuntime> service(final String name) {
        return services.stream().filter(entry -> entry.service().equals(name)).findFirst();
    }
}

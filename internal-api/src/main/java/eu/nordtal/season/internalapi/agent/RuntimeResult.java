package eu.nordtal.season.internalapi.agent;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The project's services as the runtime reports them. An unreached daemon differs from an empty project.
 *
 * @param reached whether the runtime could be read at all
 * @param services the services found, empty if it could not be read
 * @param message the reason if it could not be read, otherwise {@code null}
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

    public Optional<ServiceRuntime> service(final String name) {
        return services.stream().filter(entry -> entry.service().equals(name)).findFirst();
    }
}

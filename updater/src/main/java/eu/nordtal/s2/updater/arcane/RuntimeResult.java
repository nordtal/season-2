package eu.nordtal.s2.updater.arcane;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * What came of asking Arcane for the project's services.
 *
 * <p>Two answers rather than an {@code Optional}, because "Arcane did not answer" is the one that
 * has to stop the whole run before anything is touched, and the sentence explaining it is what a
 * person reads days later. An empty list from a reachable Arcane is a different thing entirely - a
 * project with no services - and would be silently identical under an {@code Optional}.</p>
 *
 * @param reached  whether Arcane answered at all
 * @param services what it said, empty when it did not
 * @param message  why not, or {@code null} when it did
 */
public record RuntimeResult(boolean reached, @NotNull List<ServiceRuntime> services,
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

    /** @return the entry for that compose service, if Arcane knows it */
    public Optional<ServiceRuntime> service(final @NotNull String name) {
        return services.stream().filter(entry -> entry.service().equals(name)).findFirst();
    }
}

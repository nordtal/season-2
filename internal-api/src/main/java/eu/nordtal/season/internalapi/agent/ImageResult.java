package eu.nordtal.season.internalapi.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The services that run an image older than the registry's. It keeps "up to date" and "not looked at" apart.
 *
 * @param reached whether the images could be read at all
 * @param services the finding for each running compose service
 * @param unverifiable the {@link State#UNKNOWN} services whose image could not be identified
 * @param message the reason if the images could not be read, otherwise {@code null}
 * @param localBuilds what each service runs that was built on this host and in no release, absent if nothing
 */
public record ImageResult(
        boolean reached,
        Map<String, State> services,
        Set<String> unverifiable,
        @Nullable String message,
        Map<String, LocalBuild> localBuilds) {

    public ImageResult {
        services = Map.copyOf(services);
        unverifiable = Set.copyOf(unverifiable);
        localBuilds = localBuilds == null ? Map.of() : Map.copyOf(localBuilds);
    }

    /**
     * What one service runs that was built on this host. The next run that replaces the service overwrites it.
     *
     * @param image the image reference, if the image itself was built here
     * @param jars the jars in its volume whose descriptor says they were built outside a release
     */
    public record LocalBuild(@Nullable String image, List<String> jars) {

        public LocalBuild {
            jars = List.copyOf(jars);
        }
    }

    public enum State {

        /** compose.yml makes this container differently now, or the registry has a newer image under its tag. */
        OUTDATED,

        UP_TO_DATE,

        /** The container runs an image built on this host and never published. The next update run replaces it. */
        LOCAL,

        /** This service's image could not be compared with a registry; a note, never work and never current. */
        UNKNOWN
    }

    public static ImageResult of(final Map<String, State> services) {
        return of(services, Set.of());
    }

    public static ImageResult of(final Map<String, State> services, final Set<String> unverifiable) {
        return new ImageResult(true, services, unverifiable, null, Map.of());
    }

    public static ImageResult unreachable(final String message) {
        return new ImageResult(false, Map.of(), Set.of(), message, Map.of());
    }

    public ImageResult withLocalBuilds(final Map<String, LocalBuild> builds) {
        return new ImageResult(reached, services, unverifiable, message, builds);
    }

    public State state(final String service) {
        return services.getOrDefault(service, State.UNKNOWN);
    }

    public boolean isOutdated(final String service) {
        return state(service) == State.OUTDATED;
    }

    public boolean isLocal(final String service) {
        return state(service) == State.LOCAL;
    }

    /**
     * Returns whether the images were read and none was compared with a registry.
     * Never true together with {@link #unverifiable}.
     */
    public boolean nothingCompared() {
        return reached
                && unverifiable.isEmpty()
                && services.values().stream().allMatch(state -> state == State.UNKNOWN);
    }

    /** Returns the services on an image built on this host, which the next update run replaces, sorted. */
    public List<String> local() {
        return services.entrySet().stream()
                .filter(entry -> entry.getValue() == State.LOCAL)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}

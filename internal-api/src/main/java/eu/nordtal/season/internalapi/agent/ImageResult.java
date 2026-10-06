package eu.nordtal.season.internalapi.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which services run an image older than the registry's, where "up to date" and "nobody looked" stay apart.
 *
 * @param reached whether the images could be read at all
 * @param services one entry per running compose service, service name to what was found
 * @param unverifiable the {@link State#UNKNOWN} services whose image could not be identified, so the report can say why
 * @param message why not, or {@code null} when they could be read
 * @param localBuilds what each service runs that was built on this host and no release has, absent when nothing
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
        // An older agent's answer has no such field.
        localBuilds = localBuilds == null ? Map.of() : Map.copyOf(localBuilds);
    }

    /**
     * What one service runs that was built on this host, which the next run that replaces it overwrites.
     *
     * @param image the image reference when the image itself was built here, whatever its {@link State}
     * @param jars the plugin and server jars in its volume whose descriptor says they were built outside a release
     */
    public record LocalBuild(@Nullable String image, List<String> jars) {

        public LocalBuild {
            jars = List.copyOf(jars);
        }
    }

    /** What is known about one service's image. */
    public enum State {

        /** compose.yml now makes this container differently, or the registry has a newer image under its tag. */
        OUTDATED,

        /** Checked, and what is running is what the registry has. */
        UP_TO_DATE,

        /** Running from an image built on this host and never published, which the next update run replaces. */
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

    /** This result with what each service runs that was built here. */
    public ImageResult withLocalBuilds(final Map<String, LocalBuild> builds) {
        return new ImageResult(reached, services, unverifiable, message, builds);
    }

    /** Returns what was found for that service, or {@link State#UNKNOWN} if it was not among them. */
    public State state(final String service) {
        return services.getOrDefault(service, State.UNKNOWN);
    }

    /** Returns whether that service runs an image the registry has moved past. */
    public boolean isOutdated(final String service) {
        return state(service) == State.OUTDATED;
    }

    /** Returns whether that service runs an image built here and published nowhere. */
    public boolean isLocal(final String service) {
        return state(service) == State.LOCAL;
    }

    /**
     * Returns whether the images were read and none was compared with a registry, which otherwise reads as current.
     *
     * Never together with {@link #unverifiable}: two notes about the same thing is how a report stops being read.
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

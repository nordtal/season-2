package eu.nordtal.s2.internalapi.agent;

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
 */
public record ImageResult(
        boolean reached,
        Map<String, State> services,
        Set<String> unverifiable,
        @Nullable String message) {

    public ImageResult {
        services = Map.copyOf(services);
        unverifiable = Set.copyOf(unverifiable);
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
        return new ImageResult(true, services, unverifiable, null);
    }

    public static ImageResult unreachable(final String message) {
        return new ImageResult(false, Map.of(), Set.of(), message);
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

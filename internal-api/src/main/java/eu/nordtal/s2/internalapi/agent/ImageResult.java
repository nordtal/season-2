package eu.nordtal.s2.internalapi.agent;

import java.util.Map;
import java.util.Optional;
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

        /** The registry has a newer image than the one this container was created from. */
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

    /** Returns the report sentence when no service could be checked, since that otherwise reads as all current. */
    public Optional<String> nothingChecked() {
        if (!reached) {
            return Optional.of(
                    "The images could not be read, so this run knows nothing about" + " image updates: " + message);
        }
        if (!unverifiable.isEmpty()) {
            // Never both: two notes about the same thing is how a report stops being read.
            return Optional.empty();
        }
        if (services.isEmpty() || services.values().stream().allMatch(state -> state == State.UNKNOWN)) {
            return Optional.of("No service's image was compared against a registry, so this run"
                    + " cannot tell a current image from a stale one. Either the daemon listed no"
                    + " running container for this project, or no reference could be resolved.");
        }
        return Optional.empty();
    }

    /** Returns the sentence naming the services whose image could not be identified, or empty when there are none. */
    public Optional<String> notCheckable() {
        if (!reached || unverifiable.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("The registry could not be asked about " + String.join(", ", unverifiable)
                + ", so nothing here can tell a current image from a stale one for "
                + (unverifiable.size() == 1 ? "it" : "them") + ". A local build is told apart from"
                + " this already, so what is left is a registry that did not answer, or an image"
                + " whose exact identity the daemon no longer has on file. Neither of those is"
                + " `up to date`, and a run that stayed silent about "
                + (unverifiable.size() == 1 ? "it" : "them") + " would read exactly as if it had"
                + " checked and found nothing to do.");
    }

    /** Returns the sentence naming the services on a local build, which the next update run replaces, or empty. */
    public Optional<String> localImages() {
        if (services.isEmpty()) {
            return Optional.empty();
        }
        final java.util.List<String> local = new java.util.ArrayList<>();
        for (final Map.Entry<String, State> entry : services.entrySet()) {
            if (entry.getValue() == State.LOCAL) {
                local.add(entry.getKey());
            }
        }
        if (local.isEmpty()) {
            return Optional.empty();
        }
        java.util.Collections.sort(local);
        return Optional.of("Built on this host and never published: " + String.join(", ", local)
                + ". The next real update run replaces " + (local.size() == 1 ? "it" : "them")
                + " with whatever the last release actually contains, without asking.");
    }
}

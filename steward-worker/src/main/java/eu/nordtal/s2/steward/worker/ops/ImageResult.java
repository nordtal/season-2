package eu.nordtal.s2.steward.worker.ops;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which of the project's services are running an image older than the registry's.
 *
 * Three answers, not two: the same rule the artefact plan follows: "up to date" and "nobody has looked" must never
 * be the same value. This is not a theoretical distinction: a management panel that never queried a registry at all,
 * answering from results it had persisted itself, would report a project nothing had ever checked as current while
 * it silently ran releases behind. {@code DockerOps} asks the registry per reference, but the third answer stays,
 * because the question can still fail to be answered - and folding that into "no updates" is the failure this type
 * exists to prevent.
 *
 * @param reached whether the images could be read at all
 * @param services one entry per running compose service, service name to what was found
 * @param unverifiable the subset of {@code services} whose image could not be identified at all - the registry did
 *     not answer, or the daemon no longer has the container's exact image on file. They are {@link State#UNKNOWN}
 *     like any other unchecked image; this only records why, so the report can say something true instead of staying
 *     silent - see {@link #notCheckable()}. A service built here and never published is {@link State#LOCAL}, not a
 *     member of this set: that is a known answer, not an unanswered question
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

        /**
         * Running from an image built on this host and never published.
         *
         * The opposite direction from {@link #OUTDATED}, not a milder version of it.
         *
         * A container recreated from a local {@code docker build} must never answer {@code OUTDATED}, which would
         * be backwards - the registry has nothing newer, this host has something the registry has never seen.
         * Neutral, not a fault: drawn without warning colour in the interface, but carrying the one warning that is
         * true of it - the next real update run replaces this image silently, because the updater installs from a
         * release and this one is not on any.
         */
        LOCAL,

        /**
         * This service's image could not be compared with a registry.
         *
         * It carries no registry
         * digest, was not built here either, or the registry did not answer. Never treated as work
         * and never reported as current; it is a note.
         */
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

    /** @return what was found for that service, or {@link State#UNKNOWN} if it was not among them */
    public State state(final String service) {
        return services.getOrDefault(service, State.UNKNOWN);
    }

    /** @return whether that service is running an image the registry has moved past */
    public boolean isOutdated(final String service) {
        return state(service) == State.OUTDATED;
    }

    /** @return whether that service is running an image built here and published nowhere */
    public boolean isLocal(final String service) {
        return state(service) == State.LOCAL;
    }

    /**
     * @return the sentence to put in the report when nothing could be said, or empty when at least
     *         one service was actually checked. A run whose every service is {@code UNKNOWN} looks
     *         exactly like one where every image is current, and this is the only thing that tells
     *         them apart
     */
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

    /**
     * @return the sentence naming the services whose image could not be identified at all, or empty when there are
     *     none. Separate from {@link #nothingChecked()} and fires on its own: one service that was checked is
     *     enough to silence that method, and saying nothing about the rest reads exactly like "checked, and
     *     current". A build performed here and never pushed is told apart from this on its own, as
     *     {@link State#LOCAL}, so what is left in this set is a registry that did not answer, or a container whose
     *     exact image the daemon no longer has on file. Folding the local-build case into this one would produce
     *     the worst of the two remaining behaviours: either every run carries a note blaming a setting, or no note
     *     at all while the unverifiable images sit there.
     */
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

    /**
     * @return the sentence naming the services running an image built here and never published, or empty when there are
     *     none. {@link State#LOCAL} is neutral, not a fault - but the one fact about it worth putting in a report is
     *     that the updater only ever installs from a release, so the next real update run replaces a local build
     *     silently, and a report that stays quiet about that reads exactly like one where every image is the
     *     published kind.
     */
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

package eu.nordtal.s2.steward.worker.ops;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which of the project's services are running an image older than the registry's.
 *
 * <h2>Three answers, not two</h2>
 * The same rule the artefact plan follows: "up to date" and "nobody has looked" must never be the
 * same value. This is not a theoretical distinction - it is finding A24, on 2026-09-12. Arcane, the
 * management panel this project asked until then, never queried a registry at all: it answered from
 * results it had persisted itself, so a project nothing had ever checked came back with no entries
 * and four releases ran behind while every report said the network was current. Arcane was removed
 * on 2026-09-13 and {@code DockerOps} asks the registry per reference now, but the third answer
 * stays, because the question can still fail to be answered - and folding that into "no updates" is
 * the failure this type exists to prevent.
 *
 * @param reached      whether the images could be read at all
 * @param services     one entry per running compose service, service name to what was found
 * @param unverifiable the subset of {@code services} whose image could not be compared with a
 *                     registry - it carries no registry digest, or the registry did not answer.
 *                     They are {@link State#UNKNOWN} like any other unchecked image; this only
 *                     records <em>why</em>, so the report can say something true instead of
 *                     staying silent - see {@link #notCheckable()}
 * @param message      why not, or {@code null} when they could be read
 */
public record ImageResult(boolean reached, @NotNull Map<String, State> services,
                          @NotNull Set<String> unverifiable, @Nullable String message) {

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
         * This service's image could not be compared with a registry - it carries no registry
         * digest, or the registry did not answer. Never treated as work and never reported as
         * current; it is a note.
         */
        UNKNOWN
    }

    public static ImageResult of(final @NotNull Map<String, State> services) {
        return of(services, Set.of());
    }

    public static ImageResult of(final @NotNull Map<String, State> services,
                                 final @NotNull Set<String> unverifiable) {
        return new ImageResult(true, services, unverifiable, null);
    }

    public static ImageResult unreachable(final @NotNull String message) {
        return new ImageResult(false, Map.of(), Set.of(), message);
    }

    /** @return what was found for that service, or {@link State#UNKNOWN} if it was not among them */
    public @NotNull State state(final @NotNull String service) {
        return services.getOrDefault(service, State.UNKNOWN);
    }

    /** @return whether that service is running an image the registry has moved past */
    public boolean isOutdated(final @NotNull String service) {
        return state(service) == State.OUTDATED;
    }

    /**
     * @return the sentence to put in the report when nothing could be said, or empty when at least
     *         one service was actually checked. A run whose every service is {@code UNKNOWN} looks
     *         exactly like one where every image is current, and this is the only thing that tells
     *         them apart
     */
    public Optional<String> nothingChecked() {
        if (!reached) {
            return Optional.of("The images could not be read, so this run knows nothing about"
                    + " image updates: " + message);
        }
        if (!unverifiable.isEmpty()) {
            // Never both. When some image really was unreadable for another reason the sentence
            // below is still the honest one, and two notes about the same thing is how a report
            // stops being read.
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
     * @return the sentence naming the services whose image could not be compared against a
     *         registry, or empty when there are none
     *
     * <h2>Why this is separate from {@link #nothingChecked()} and fires on its own</h2>
     * Because one service that <em>was</em> checked is enough to silence that method, and saying
     * nothing about the rest reads exactly like "checked, and current". On this deployment almost
     * every image is a {@code ghcr.io/nordtal} reference that answers; what is left over is the one
     * built on this host and pushed nowhere - {@code steward-ui} during the alpha - which has no
     * registry digest to compare at all. Folding the two together produced the worst of the three
     * possible behaviours: before 0.8.5 every run carried a note blaming a setting, and the first
     * cut of the fix carried no note at all while the unverifiable images sat there.
     */
    public Optional<String> notCheckable() {
        if (!reached || unverifiable.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("The registry could not be asked about " + String.join(", ", unverifiable)
                + ", so nothing here can tell a current image from a stale one for "
                + (unverifiable.size() == 1 ? "it" : "them") + ". Every reference is asked about"
                + " now, so what is left is an image that carries no registry digest - built on this"
                + " host and pushed nowhere - or a registry that did not answer. Neither of those is"
                + " `up to date`, and a run that stayed silent about "
                + (unverifiable.size() == 1 ? "it" : "them") + " would read exactly as if it had"
                + " checked and found nothing to do.");
    }
}

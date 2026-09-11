package eu.nordtal.s2.updater.arcane;

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
 * same value. Arcane does not check a registry when it is asked - it answers from the results its
 * own image-update check has <em>persisted</em>, so a project it has never checked comes back with
 * no entries at all. Folding that into "no updates" would let a stale image sit there for ever
 * while every run reported the network current.
 *
 * @param reached      whether Arcane answered at all
 * @param services     one entry per compose service Arcane named, service name to what it said
 * @param notCheckable the subset of {@code services} whose image Arcane never asked a registry
 *                     about, because it classified it as local - see {@link #nothingChecked()}.
 *                     They are {@link State#UNKNOWN} like any other unchecked image; this only
 *                     records <em>why</em>, so the report can say something true instead of
 *                     sending somebody to a setting that is already on
 * @param message      why not, or {@code null} when it did
 */
public record ImageResult(boolean reached, @NotNull Map<String, State> services,
                          @NotNull Set<String> notCheckable, @Nullable String message) {

    public ImageResult {
        services = Map.copyOf(services);
        notCheckable = Set.copyOf(notCheckable);
    }

    /** What Arcane knows about one service's image. */
    public enum State {

        /** The registry has a newer image than the one this container was created from. */
        OUTDATED,

        /** Checked, and what is running is what the registry has. */
        UP_TO_DATE,

        /**
         * Arcane has no check result for this service's image - it has never run one, or the last
         * one failed. Never treated as work and never reported as current; it is a note.
         */
        UNKNOWN
    }

    public static ImageResult of(final @NotNull Map<String, State> services) {
        return of(services, Set.of());
    }

    public static ImageResult of(final @NotNull Map<String, State> services,
                                 final @NotNull Set<String> notCheckable) {
        return new ImageResult(true, services, notCheckable, null);
    }

    public static ImageResult unreachable(final @NotNull String message) {
        return new ImageResult(false, Map.of(), Set.of(), message);
    }

    /** @return what Arcane said about that service, or {@link State#UNKNOWN} if it named none */
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
            return Optional.of("Arcane could not be asked about image updates: " + message);
        }
        if (services.isEmpty() || services.values().stream().allMatch(state -> state == State.UNKNOWN)) {
            if (!notCheckable.isEmpty()) {
                // The honest sentence, and the reason this field exists. Sending somebody to turn
                // on a check that is already on is worse than saying nothing: they turn it on,
                // nothing changes, and the next person reads the same note and believes it less.
                return Optional.of("Arcane never asked a registry about "
                        + String.join(", ", notCheckable) + ", so this run cannot tell a current"
                        + " image from a stale one. That is not a setting: Arcane treats the image"
                        + " of any service carrying a `build:` directive as local and only reports"
                        + " the digest it already has. compose.yml keeps those blocks on purpose,"
                        + " for developing the images here - the price is that image drift cannot"
                        + " be detected for them, and a new image reaches this host only through a"
                        + " Redeploy in Arcane.");
            }
            return Optional.of("Arcane holds no image-update result for any service, so this run"
                    + " cannot tell a current image from a stale one. It answers from its own"
                    + " persisted checks rather than asking a registry when asked - turn the image"
                    + " update check on in Arcane, or the containers will keep running whatever"
                    + " image they were created from.");
        }
        return Optional.empty();
    }
}

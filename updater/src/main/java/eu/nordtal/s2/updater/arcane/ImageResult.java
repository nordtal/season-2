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
 * @param unverifiable the subset of {@code services} whose image Arcane never asked a registry
 *                     about, because it classified it as local - see {@link #notCheckable()}.
 *                     They are {@link State#UNKNOWN} like any other unchecked image; this only
 *                     records <em>why</em>, so the report can say something true instead of
 *                     sending somebody to a setting that is already on
 * @param message      why not, or {@code null} when it did
 */
public record ImageResult(boolean reached, @NotNull Map<String, State> services,
                          @NotNull Set<String> unverifiable, @Nullable String message) {

    public ImageResult {
        services = Map.copyOf(services);
        unverifiable = Set.copyOf(unverifiable);
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
                                 final @NotNull Set<String> unverifiable) {
        return new ImageResult(true, services, unverifiable, null);
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
        if (!unverifiable.isEmpty()) {
            // Never both. When some image really was unreadable for another reason the sentence
            // below is still the honest one, and two notes about the same thing is how a report
            // stops being read.
            return Optional.empty();
        }
        if (services.isEmpty() || services.values().stream().allMatch(state -> state == State.UNKNOWN)) {
            return Optional.of("Arcane holds no image-update result for any service, so this run"
                    + " cannot tell a current image from a stale one. It answers from its own"
                    + " persisted checks rather than asking a registry when asked - turn the image"
                    + " update check on in Arcane, or the containers will keep running whatever"
                    + " image they were created from.");
        }
        return Optional.empty();
    }

    /**
     * @return the sentence naming the services whose image Arcane never compared against anything,
     *         or empty when there are none
     *
     * <h2>Why this is separate from {@link #nothingChecked()} and fires on its own</h2>
     * Because one service that <em>was</em> checked is enough to silence that method, and on this
     * deployment that service is {@code postgres} - the only one in the project without a
     * {@code build:} directive. Everything this project publishes is in the other group. Folding
     * the two together produced the worst of the three possible behaviours: before 0.8.5 every run
     * carried a note blaming a setting that was already on, and the first cut of the fix carried no
     * note at all while four images sat unverifiable. Saying nothing reads exactly like "checked,
     * and current".
     */
    public Optional<String> notCheckable() {
        if (!reached || unverifiable.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("Arcane never asked a registry about " + String.join(", ", unverifiable)
                + ", so nothing here can tell a current image from a stale one for "
                + (unverifiable.size() == 1 ? "it" : "them") + ". That is not a setting somebody"
                + " forgot: Arcane treats the image of any service carrying a `build:` directive as"
                + " local and only reports the digest it already holds. compose.yml keeps those"
                + " blocks deliberately, for developing the images here - the price is this, and a"
                + " newer image reaches this host only through a Redeploy in Arcane.");
    }
}

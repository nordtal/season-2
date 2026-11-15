package eu.nordtal.season.steward.stack;

import eu.nordtal.season.common.http.WebClient;
import eu.nordtal.season.common.time.Backoff;
import eu.nordtal.season.common.time.Waiting;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Checks that the resource pack the proxy offers answers, with patience for short hiccups.
 * The pack is reached from outside, through Cloudflare, so a single failed request is often a short hiccup on the way.
 * A pack counts as dead only after it has failed for {@link #PATIENCE}.
 */
final class PackProbe {

    /** How long the pack may fail before it counts as dead. */
    static final Duration PATIENCE = Duration.ofMinutes(1);

    /** The pauses between tries: 5, 10 and then 20 seconds, the last one cut at {@link #PATIENCE}. */
    static final Backoff BACKOFF = new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(20));

    private static final WebClient WEB =
            WebClient.create(Duration.ofSeconds(10)).followingRedirects();

    private final Predicate<URI> answers;
    private final Waiting waiting;

    /**
     * @param answers whether a GET of the address answers 200
     * @param waiting the pause between tries
     */
    PackProbe(final Predicate<URI> answers, final Waiting waiting) {
        this.answers = Objects.requireNonNull(answers, "answers");
        this.waiting = Objects.requireNonNull(waiting, "waiting");
    }

    /** Returns the probe that sends real requests. */
    static PackProbe web(final Waiting waiting) {
        return new PackProbe(WEB::answers, waiting);
    }

    /** Returns {@code url} when it has not answered 200 for {@link #PATIENCE}, else empty. */
    Optional<String> dead(final String url) {
        try {
            final URI uri = URI.create(url);
            final Optional<Boolean> answered = waiting.until(
                    () -> answers.test(uri) ? Optional.of(true) : Optional.empty(), PATIENCE, BACKOFF);
            return answered.isPresent() ? Optional.empty() : Optional.of(url);
        } catch (final IllegalArgumentException notAUrl) {
            return Optional.of(url);
        }
    }

}

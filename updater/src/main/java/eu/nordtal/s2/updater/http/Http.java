package eu.nordtal.s2.updater.http;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;

/**
 * The one thing this module does that reaches outside the container: fetch a small document over
 * HTTPS.
 * <p>
 * It is an interface for one reason, and it is the reason step 1 of docs/updater.md is the step
 * worth building carefully: <b>every parser below it is tested against recorded responses.</b>
 * A resolver that picks the wrong version is worse than no updater at all, and "wrong" here means
 * things like a {@code -sources.jar} or a pre-release - cases that exist in the real payloads and
 * would never appear in a test that talks to the live API on a good day.
 * </p>
 * <p>
 * Only GET, only text. The updater does download jars, but not through here (step 3): those go
 * straight to a file and are verified against a checksum, and holding a 64 MB Paper jar in a
 * String would be an odd way to start.
 * </p>
 */
public interface Http {

    /**
     * Fetches {@code uri} and returns the body as a string.
     *
     * @throws HttpException on anything that is not a 2xx, including the redirects GitHub uses for
     *                       release assets - implementations are expected to follow those
     *                       themselves rather than surfacing them here.
     * @throws IOException   on a transport failure or a timeout.
     */
    @NotNull String get(@NotNull URI uri) throws IOException;
}

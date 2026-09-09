package eu.nordtal.s2.updater.http;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;

/**
 * Fetches a small document over HTTPS.
 *
 * <p>An interface so that every parser above it can be tested against recorded responses: a
 * resolver that picks a {@code -sources.jar} or a pre-release is worse than no updater at all, and
 * those cases never appear when a test talks to the live API on a good day.</p>
 *
 * <p>Only GET, only text. Jars are downloaded straight to a file and verified against a checksum,
 * never through here.</p>
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

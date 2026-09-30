package eu.nordtal.s2.steward.worker.http;

import java.io.IOException;
import java.net.URI;

/** Fetches a small text document over HTTPS with GET; an interface so parsers are tested on recorded responses. */
public interface Http {

    /**
     * Fetches {@code uri} and returns the body as a string, following redirects.
     *
     * @throws eu.nordtal.s2.common.http.HttpFailure on anything that is not a 2xx
     * @throws IOException on a transport failure or a timeout
     */
    String get(URI uri) throws IOException;
}

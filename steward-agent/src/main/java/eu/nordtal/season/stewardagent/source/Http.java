package eu.nordtal.season.stewardagent.source;

import java.io.IOException;
import java.net.URI;

/** Fetches a small text document over HTTPS with GET. Tests replace it with recorded responses. */
public interface Http {

    /**
     * Fetches {@code uri} and returns the body as a string, following redirects.
     *
     * @throws eu.nordtal.season.common.http.HttpFailure on any status except 2xx
     * @throws IOException on a transport failure or a timeout
     */
    String get(URI uri) throws IOException;
}

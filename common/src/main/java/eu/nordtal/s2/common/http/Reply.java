package eu.nordtal.s2.common.http;

import eu.nordtal.s2.common.json.Json;
import java.net.URI;

/**
 * An answer to a request: where it went, its status and its body as text.
 *
 * @param body the body, empty when there was none
 */
public record Reply(URI uri, int status, String body) {

    /** Returns whether the status is a 2xx. */
    public boolean ok() {
        return status / 100 == 2;
    }

    /** Returns the body of a 2xx answer, and refuses any other. */
    public String okBody() throws HttpFailure {
        if (!ok()) {
            throw new HttpFailure(uri, status, body);
        }
        return body;
    }

    /** Returns the body of a 2xx answer read as {@code type}. */
    public <T> T okJson(final Class<T> type) throws HttpFailure {
        return Json.decode(okBody(), type);
    }
}

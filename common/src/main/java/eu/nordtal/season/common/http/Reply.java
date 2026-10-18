package eu.nordtal.season.common.http;

import eu.nordtal.season.common.json.Json;
import java.net.URI;

/** The answer to a request. {@code body} is empty if the answer had none. */
public record Reply(URI uri, int status, String body) {

    public boolean ok() {
        return status / 100 == 2;
    }

    /** Returns the body of a 2xx answer and refuses any other status. */
    public String okBody() throws HttpFailure {
        if (!ok()) {
            throw new HttpFailure(uri, status, body);
        }
        return body;
    }

    /** Returns the body of a 2xx answer as {@code type}. */
    public <T> T okJson(final Class<T> type) throws HttpFailure {
        return Json.decode(okBody(), type);
    }
}

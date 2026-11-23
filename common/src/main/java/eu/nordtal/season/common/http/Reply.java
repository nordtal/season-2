package eu.nordtal.season.common.http;

import eu.nordtal.season.common.json.Json;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.Optional;

/** The answer to a request. {@code body} is empty if the answer had none. */
public record Reply(URI uri, int status, String body, HttpHeaders headers) {

    private static final HttpHeaders EMPTY = HttpHeaders.of(Map.of(), (name, value) -> true);

    public Reply(final URI uri, final int status, final String body) {
        this(uri, status, body, EMPTY);
    }

    public boolean ok() {
        return status / 100 == 2;
    }

    /** Returns the first value of header {@code name}, matched without regard to case. */
    public Optional<String> header(final String name) {
        return headers.firstValue(name);
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

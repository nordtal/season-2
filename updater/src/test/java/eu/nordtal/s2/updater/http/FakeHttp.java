package eu.nordtal.s2.updater.http;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link Http} backed by responses recorded from the live APIs on 2026-09-01.
 * <p>
 * Routing is by substring rather than by exact URL on purpose: the tests are about what the
 * parsers do with a real payload, not about how a query string is spelled. The one place the spelling
 * matters - Modrinth's bracketed JSON filters - is asserted directly in {@link #requested()}.
 * </p>
 */
public final class FakeHttp implements Http {

    private final Map<String, String> routes = new LinkedHashMap<>();
    private final Map<String, IOException> failures = new LinkedHashMap<>();
    private final List<URI> requested = new ArrayList<>();

    /** Serves {@code fixtures/<name>} for any URL containing {@code match}. */
    public FakeHttp serving(final String match, final String fixture) {
        routes.put(match, read(fixture));
        return this;
    }

    /** Serves a literal body - for the pack's 41-byte .sha1 asset and for hand-built edge cases. */
    public FakeHttp answering(final String match, final String body) {
        routes.put(match, body);
        return this;
    }

    /** Makes any URL containing {@code match} fail, to test that one source's outage stays local. */
    public FakeHttp failing(final String match, final IOException failure) {
        failures.put(match, failure);
        return this;
    }

    public List<URI> requested() {
        return List.copyOf(requested);
    }

    @Override
    public @NotNull String get(final @NotNull URI uri) throws IOException {
        requested.add(uri);
        final String url = uri.toString();
        for (final Map.Entry<String, IOException> failure : failures.entrySet()) {
            if (url.contains(failure.getKey())) {
                throw failure.getValue();
            }
        }
        for (final Map.Entry<String, String> route : routes.entrySet()) {
            if (url.contains(route.getKey())) {
                return route.getValue();
            }
        }
        throw new HttpException(uri, 404, "no fixture is routed for this URL");
    }

    public static String read(final String fixture) {
        try (InputStream stream = FakeHttp.class.getResourceAsStream("/fixtures/" + fixture)) {
            if (stream == null) {
                throw new IllegalStateException("missing fixture: " + fixture);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new IllegalStateException("could not read fixture " + fixture, unreadable);
        }
    }
}

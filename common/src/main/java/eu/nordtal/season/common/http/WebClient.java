package eu.nordtal.season.common.http;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.time.Backoff;
import eu.nordtal.season.common.time.Waiting;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The one HTTP client. It sends headers and a token with every request, times out, and can retry.
 * It follows no redirect unless asked, so a token never reaches another host.
 * Each {@code with} method returns a client that shares the connection pool.
 */
public final class WebClient {

    private final HttpClient client;
    private final Duration timeout;
    private final Map<String, String> headers;
    private final int attempts;
    private final Backoff backoff;
    private final @Nullable Waiting waiting;

    private WebClient(
            final HttpClient client,
            final Duration timeout,
            final Map<String, String> headers,
            final int attempts,
            final Backoff backoff,
            final @Nullable Waiting waiting) {
        this.client = client;
        this.timeout = timeout;
        this.headers = Map.copyOf(headers);
        this.attempts = attempts;
        this.backoff = backoff;
        this.waiting = waiting;
    }

    /**
     * Returns {@code baseUrl} without a trailing slash if a token may travel to it unencrypted.
     * That holds for https, for http to loopback and for http to a compose service name, which has no dot.
     *
     * @param setting the configuration key of {@code baseUrl}, named in the refusal
     * @throws IllegalArgumentException for http to any other host
     */
    public static String tokenSafe(final String setting, final String baseUrl) {
        final String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        final URI uri = URI.create(trimmed);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return trimmed;
        }
        final String host = uri.getHost();
        if ("http".equalsIgnoreCase(uri.getScheme()) && host != null && isInside(host)) {
            return trimmed;
        }
        throw new IllegalArgumentException(setting + " is " + baseUrl + ", and this process will not send its token"
                + " there in clear. It is https, or plain http to a compose service name on the internal network.");
    }

    /** Returns whether {@code host} is loopback or a service name. */
    private static boolean isInside(final String host) {
        if (host.startsWith("[")) {
            return "[::1]".equals(host) || "[0:0:0:0:0:0:0:1]".equalsIgnoreCase(host);
        }
        return !host.contains(".") || "127.0.0.1".equals(host);
    }

    /** Returns a client that connects and answers within {@code timeout} and follows no redirect. */
    public static WebClient create(final Duration timeout) {
        return create(timeout, timeout);
    }

    /** Returns a client that connects within {@code connect} and answers within {@code timeout}. */
    public static WebClient create(final Duration connect, final Duration timeout) {
        return new WebClient(
                HttpClient.newBuilder().connectTimeout(connect).build(),
                timeout,
                Map.of(),
                1,
                Backoff.fixed(Duration.ofSeconds(1)),
                null);
    }

    /** Returns the client following redirects within one scheme. */
    public WebClient followingRedirects() {
        return new WebClient(
                HttpClient.newBuilder()
                        .connectTimeout(client.connectTimeout().orElse(timeout))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                timeout,
                headers,
                attempts,
                backoff,
                waiting);
    }

    public WebClient header(final String name, final String value) {
        final Map<String, String> more = new LinkedHashMap<>(headers);
        more.put(name, value);
        return new WebClient(client, timeout, more, attempts, backoff, waiting);
    }

    /** Returns the client sending {@code Authorization: Bearer token}, or the client itself for a blank token. */
    public WebClient bearer(final @Nullable String token) {
        return token == null || token.isBlank() ? this : header("Authorization", "Bearer " + token);
    }

    /** Returns the client retrying a GET or a download up to {@code attempts} times if the connection fails. */
    public WebClient retrying(final int attempts, final Backoff backoff, final Waiting waiting) {
        if (attempts < 1) {
            throw new IllegalArgumentException("a request is tried at least once, got " + attempts);
        }
        return new WebClient(client, timeout, headers, attempts, backoff, waiting);
    }

    public Duration timeout() {
        return timeout;
    }

    public Reply get(final URI uri) throws IOException {
        return get(uri, timeout);
    }

    /** Sends a GET that answers within {@code within}. */
    public Reply get(final URI uri, final Duration within) throws IOException {
        return retried(() -> send(request(uri, within).GET(), uri));
    }

    /** Runs {@code attempt} until it succeeds, fails with a status or an interrupt, or the tries run out. */
    private <T> T retried(final Attempt<T> attempt) throws IOException {
        IOException last = null;
        Duration pause = backoff.first();
        for (int tried = 1; tried <= attempts; tried++) {
            try {
                return attempt.run();
            } catch (final InterruptedIOException | HttpFailure notRetried) {
                throw notRetried;
            } catch (final IOException failed) {
                last = failed;
                if (tried == attempts || waiting == null || !waiting.sleep(pause)) {
                    break;
                }
                pause = backoff.after(pause);
            }
        }
        throw Objects.requireNonNull(last, "last");
    }

    private interface Attempt<T> {
        T run() throws IOException;
    }

    /** Returns the body of a 2xx GET and refuses any other status with {@link HttpFailure}. */
    public String text(final URI uri) throws IOException {
        return get(uri).okBody();
    }

    public Reply post(final URI uri, final String contentType, final String body) throws IOException {
        return post(uri, contentType, body, timeout);
    }

    public Reply post(final URI uri, final String contentType, final String body, final Duration within)
            throws IOException {
        return send(
                request(uri, within)
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body)),
                uri);
    }

    public Reply postJson(final URI uri, final Object body) throws IOException {
        return post(uri, "application/json", Json.encode(body));
    }

    /** Sends {@code fields} as {@code application/x-www-form-urlencoded}. */
    public Reply postForm(final URI uri, final Map<String, String> fields) throws IOException {
        final String form = fields.entrySet().stream()
                .map(field -> encode(field.getKey()) + "=" + encode(field.getValue()))
                .collect(Collectors.joining("&"));
        return post(uri, "application/x-www-form-urlencoded", form);
    }

    public Reply postEmpty(final URI uri) throws IOException {
        return send(request(uri, timeout).POST(HttpRequest.BodyPublishers.noBody()), uri);
    }

    public Reply put(final URI uri, final String contentType, final String body) throws IOException {
        return send(
                request(uri, timeout)
                        .header("Content-Type", contentType)
                        .PUT(HttpRequest.BodyPublishers.ofString(body)),
                uri);
    }

    public Reply delete(final URI uri) throws IOException {
        return send(request(uri, timeout).DELETE(), uri);
    }

    /**
     * Opens a GET whose body is read as it arrives and answers within {@code within}.
     * A failure status is drained and refused.
     */
    public InputStream stream(final URI uri, final Duration within, final String accept) throws IOException {
        final HttpResponse<InputStream> response = exchange(
                request(uri, within).header("Accept", accept).GET(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            try (InputStream refused = response.body()) {
                refused.readAllBytes();
            }
            throw new HttpFailure(uri, response.statusCode(), "");
        }
        return response.body();
    }

    /**
     * Saves the body of a 2xx GET to {@code destination} and refuses any other status.
     * A failed connection or a broken body is tried again like a {@link #get}.
     */
    public void download(final URI uri, final Path destination) throws IOException {
        retried(() -> {
            final HttpResponse<InputStream> response =
                    exchange(request(uri, timeout).GET(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    body.readAllBytes();
                    throw new HttpFailure(uri, response.statusCode(), "");
                }
                final Path parent = destination.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(body, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        });
    }

    /** Returns whether a GET answers 200 within the timeout. */
    public boolean answers(final URI uri) {
        try {
            return send(request(uri, timeout).GET(), uri).status() == 200;
        } catch (final IOException unreachable) {
            return false;
        }
    }

    private HttpRequest.Builder request(final URI uri, final Duration within) {
        final HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(within);
        headers.forEach(request::header);
        return request;
    }

    private Reply send(final HttpRequest.Builder request, final URI uri) throws IOException {
        final HttpResponse<String> response = exchange(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(
                uri, response.statusCode(), Optional.ofNullable(response.body()).orElse(""));
    }

    private <T> HttpResponse<T> exchange(final HttpRequest.Builder request, final HttpResponse.BodyHandler<T> body)
            throws IOException {
        try {
            return client.send(request.build(), body);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            final InterruptedIOException failure =
                    new InterruptedIOException("interrupted while waiting for an answer");
            failure.initCause(interrupted);
            throw failure;
        } catch (final UncheckedIOException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static String encode(final String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

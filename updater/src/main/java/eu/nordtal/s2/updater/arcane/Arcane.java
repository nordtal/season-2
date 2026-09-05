package eu.nordtal.s2.updater.arcane;

import eu.nordtal.s2.updater.config.UpdaterSpec;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The restart: one redeploy of the whole compose project, through Arcane's REST API.
 *
 * <h2>Why not the Docker socket</h2>
 * Mounting {@code /var/run/docker.sock} is the usual way and it was rejected
 * (docs/updater.md#the-restart-and-why-not-the-docker-socket). A container with the socket can do
 * anything on the host, and this is a container whose entire job is to download files from the
 * internet and put them where servers will execute them. One API token is the cheaper half of that
 * trade, and the redeploy then appears in Arcane's own history rather than happening behind its
 * back.
 *
 * <h2>The call is expected to be killed</h2>
 * Arcane answers a long-running operation as a stream of newline-delimited JSON, and this container
 * is one of the things the redeploy takes down. So the call waits only for the response to
 * <em>begin</em> - status line and headers - and never reads the stream to its end. Being killed
 * here is the successful outcome, and it is recognised as one: the {@code update_request} row is
 * left {@code RUNNING}, and the next start of this container reads a {@code RESTART} in that state
 * as "the redeploy happened".
 *
 * <h2>The path is a setting, not a constant</h2>
 * The default was read from Arcane's own source on 2026-09-01 - {@code project/handler.go} at
 * release v2.10.0 registers {@code POST /environments/{id}/projects/{projectId}/redeploy} under
 * the {@code /api} group - so it is no longer a guess. It stays a setting because Arcane's public
 * documentation still does not publish it: a version that moves the path is then one line in
 * {@code updater.yml} rather than a release of ours.
 * <p>
 * Both segments are <b>ids</b>. That is the trap worth naming: the compose project is called
 * {@code nordtal-s2} everywhere else in this deployment, and putting that name here answers 404.
 * </p>
 *
 * <h2>{@code localhost} in this file is this container - finding 40, 2026-09-03</h2>
 * The first restart anybody asked for failed forty milliseconds after the POST, against
 * {@code http://localhost:3553}. Arcane was running and reachable; the URL was not, because inside
 * this container {@code localhost} is <em>this container</em>, which has nothing listening on 3553.
 * It is the one wrong value that looks right in every other context - it is what the browser bar
 * says while somebody is copying it. So {@link #loopback(String)} names it: at startup as a warning,
 * and again in the sentence that goes into the row when the call fails.
 *
 * <h2>Arcane may answer success and do nothing</h2>
 * <a href="https://github.com/getarcaneapp/arcane/issues/1943">arcane#1943</a> reports a redeploy
 * of an <em>already running</em> project doing nothing while still answering success - which is
 * exactly the case here, since the stack is up when the button is pressed. It was reported on one
 * agent at v1.15.3 and closed as not planned. Nothing in this class can detect it: the stream that
 * would say so is one this container is killed part way through reading. The check is the one in
 * todo.md - watch the containers cycle the first time.
 *
 * <h2>And the exception says nothing, which is the other half - 2026-09-05</h2>
 * The second restart anybody asked for failed against {@code http://docker.host.internal:3553}.
 * The labels are transposed: Docker publishes {@code host.docker.internal}, and {@code compose.yml}
 * maps exactly that name for this service. What made it expensive was the message and not the
 * typo - it read
 * <pre>Could not reach Arcane at http://docker.host.internal:3553/...: java.net.ConnectException</pre>
 * and stopped there, because the JDK's {@code HttpClient} wraps a DNS failure in a
 * {@code ConnectException} <b>carrying no message at all</b>; the
 * {@code UnresolvedAddressException} that says what actually happened is one level down in
 * {@code getCause()}. "Connection refused" and "that name does not exist" are opposite diagnoses
 * and both printed as the same eleven characters. So the failure now carries the whole cause
 * chain, {@link #transposedDockerHost(String)} names this particular slip outright, and both are
 * string-only for the same reason {@link #loopback(String)} is.
 */
@Slf4j
public final class Arcane {

    /** The header Arcane's documentation names for token authentication. */
    private static final String API_KEY_HEADER = "X-Api-Key";

    /** Replaced in {@code redeploy-path} by the configured environment id. */
    private static final String ENVIRONMENT_PLACEHOLDER = "{environment}";

    /** Replaced in {@code redeploy-path} by the configured project id. */
    private static final String PROJECT_PLACEHOLDER = "{project}";

    /** The name compose.yml maps for this service, and the only spelling Docker publishes. */
    private static final String DOCKER_GATEWAY = "host.docker.internal";

    /** Its three labels as a set, so any order of them can be recognised as the same mistake. */
    private static final Set<String> DOCKER_GATEWAY_LABELS = Set.of("host", "docker", "internal");

    private final UpdaterSpec.ArcaneSpec config;
    private final HttpClient client;

    public Arcane(final @NotNull UpdaterSpec.ArcaneSpec config) {
        this.config = config;
        if (configured() && loopback(config.baseUrl())) {
            // Warned rather than refused. The updater is the bootstrap of the whole deployment -
            // it is the only process that migrates - and refusing to start over an optional restart
            // button would trade a working schema for a broken one.
            log.warn("arcane.base-url is {}, and inside this container that is THIS CONTAINER, not"
                    + " the host Arcane runs on. Every restart will fail with a connection error."
                    + " Use http://host.docker.internal:{} (the updater service maps it), Arcane's"
                    + " own container name if it shares a network with this one, or the host's"
                    + " address on the network.", config.baseUrl(), portOf(config.baseUrl()));
        }
        transposedDockerHost(config.baseUrl()).ifPresent(suggestion -> log.warn(
                "arcane.base-url is {}, and that host does not exist. Docker publishes the gateway"
                        + " as {} - the labels are transposed. Every restart will fail with a"
                        + " connection error that names DNS only in its cause.",
                config.baseUrl(), suggestion));
        this.client = HttpClient.newBuilder()
                // Arcane sits behind the same reverse proxy as everything else here, so a redirect
                // is plausible; NORMAL follows it and refuses HTTPS to HTTP, which is the one
                // redirect an authenticated request must never take.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }

    /**
     * Whether a restart can be performed at all.
     * <p>
     * An unconfigured Arcane is a supported state, not a broken one: every other part of the
     * updater works and the restart is a click in Arcane. Every surface asks this before it offers
     * a button, so nobody is shown a control that cannot do anything.
     * </p>
     */
    public boolean configured() {
        return !config.baseUrl().isBlank();
    }

    /**
     * The URL that would be called, for a log line and for the message on a failure.
     * <p>
     * Both path segments are substituted, and both are <em>ids</em>: the environment is {@code 0}
     * for Arcane's own host and a UUID for a remote agent, and the project is a UUID Arcane
     * generated. Neither is a name, which is the mistake this class reports by name on a 404.
     * </p>
     */
    public @NotNull String endpoint() {
        return config.baseUrl() + config.redeployPath()
                .replace(ENVIRONMENT_PLACEHOLDER, config.environment())
                .replace(PROJECT_PLACEHOLDER, config.project());
    }

    /**
     * Asks Arcane to redeploy the project.
     *
     * @return whether the request was accepted, and the sentence to record either way. Never throws:
     *         this is called at the end of a request the caller has to answer, and an exception
     *         escaping here would leave a row that says nothing at all
     */
    public @NotNull RedeployResult redeploy() {
        if (!configured()) {
            return RedeployResult.refused(
                    "Arcane is not configured (arcane.base-url is empty), so nothing was restarted."
                            + " Everything else in this run is done - open Arcane and click Redeploy"
                            + " on the project yourself.");
        }

        final URI uri;
        try {
            uri = new URI(endpoint());
        } catch (final URISyntaxException broken) {
            return RedeployResult.refused("arcane.base-url and arcane.redeploy-path do not form a"
                    + " valid URL: " + endpoint());
        }

        final HttpRequest request = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.noBody())
                .header(API_KEY_HEADER, config.apiKey())
                .header("Accept", "application/x-ndjson, application/json")
                .header("User-Agent", "nordtal-season-2/updater")
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();

        log.info("Asking Arcane to redeploy: POST {}", uri);
        try {
            // ofLines() is lazy: send() returns as soon as the status line and headers are in, and
            // the stream behind it is never read. That is deliberate - see the class comment.
            final HttpResponse<?> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
            return interpret(response.statusCode());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return RedeployResult.refused("Interrupted while asking Arcane to redeploy.");
        } catch (final IOException failure) {
            // Includes the case where the connection dies because the redeploy has already begun
            // and taken this container's network with it. Reported as refused rather than
            // triggered, on purpose: the row is then left RUNNING or FAILED, and a person looks -
            // which is the right way round for "the restart may or may not be happening".
            return RedeployResult.refused(unreachable(uri, failure, config.baseUrl()));
        }
    }

    /**
     * The exception and everything under it, innermost last.
     * <p>
     * Not decoration. {@code HttpClient} answers a DNS failure with a {@code ConnectException}
     * carrying no message, so {@code failure.toString()} on its own is the string
     * {@code "java.net.ConnectException"} and nothing else - the same eleven characters for a
     * refused connection, a name that does not exist and a route that goes nowhere. The cause is
     * where the answer is.
     * </p>
     */
    static @NotNull String causeChain(final @NotNull Throwable failure) {
        final StringBuilder text = new StringBuilder(failure.toString());
        Throwable cause = failure.getCause();
        int depth = 0;
        // Bounded, and self-referencing causes do exist: a loop here would hang the request that
        // is trying to explain why something else failed.
        while (cause != null && cause != cause.getCause() && depth++ < 5) {
            text.append(" <- ").append(cause);
            cause = cause.getCause();
        }
        return text.toString();
    }

    /**
     * The whole sentence written into {@code update_request.result} when the call did not connect.
     * <p>
     * Static, and taking the base URL rather than reading the field, so that the sentence a person
     * will read weeks later can be asserted from a test with no network and no Arcane. The two
     * halves it can add are the two wrong values that have actually been typed into this setting.
     * </p>
     */
    static @NotNull String unreachable(final @NotNull URI uri, final @NotNull Throwable failure,
                                       final String baseUrl) {
        final StringBuilder text = new StringBuilder("Could not reach Arcane at ")
                .append(uri).append(": ").append(causeChain(failure));
        final String suggestion = transposedDockerHost(baseUrl).orElse(null);
        if (suggestion != null) {
            text.append(" -- that host does not exist: Docker publishes the gateway as ")
                    .append(suggestion)
                    .append(", and arcane.base-url has the three labels in the wrong order.");
        } else if (loopback(baseUrl)) {
            text.append(" -- arcane.base-url is a loopback address, and inside this container that")
                    .append(" is this container rather than the host Arcane runs on. Use")
                    .append(" http://host.docker.internal:").append(portOf(baseUrl))
                    .append(", Arcane's container name if it shares a network with this one, or")
                    .append(" the host's address.");
        }
        return text.toString();
    }

    /**
     * The one misspelling worth naming: Docker's three labels in the wrong order.
     * <p>
     * {@code docker.host.internal} is what a person types while reaching for
     * {@code host.docker.internal}, and it is the value the first production restart actually
     * carried. Nothing in the failure itself tells it apart from a firewall - the name simply does
     * not resolve - so it is checked on the string, by name, without a network. A host that is
     * already correct returns empty.
     * </p>
     *
     * @param baseUrl the configured origin, may be blank
     * @return the host to use instead, if the configured one is a permutation of Docker's own
     */
    public static @NotNull Optional<String> transposedDockerHost(final String baseUrl) {
        final String host = hostOf(baseUrl);
        if (host == null || host.equals(DOCKER_GATEWAY)) {
            return Optional.empty();
        }
        final List<String> labels = List.of(host.split("\\.", -1));
        if (labels.size() != DOCKER_GATEWAY_LABELS.size()
                || !Set.copyOf(labels).equals(DOCKER_GATEWAY_LABELS)) {
            return Optional.empty();
        }
        return Optional.of(DOCKER_GATEWAY);
    }

    /** The host of a base URL, lower-cased, or {@code null} if there is not one. */
    private static @Nullable String hostOf(final String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        final String host;
        try {
            host = URI.create(baseUrl.trim()).getHost();
        } catch (final IllegalArgumentException notAUrl) {
            return null;
        }
        return host == null ? null : host.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a base URL points at the machine making the request.
     * <p>
     * Static and string-only so that it can be asserted without a container and without a network:
     * the whole value of this check is that it fires on a URL nobody can test from a laptop, where
     * {@code localhost} is correct and means something else entirely.
     * </p>
     *
     * @param baseUrl the configured origin, may be blank
     * @return whether its host is a loopback name or address
     */
    public static boolean loopback(final String baseUrl) {
        final String lower = hostOf(baseUrl);
        if (lower == null) {
            return false;
        }
        return lower.equals("localhost")
                || lower.endsWith(".localhost")
                || lower.equals("::1")
                || lower.equals("[::1]")
                || lower.startsWith("127.");
    }

    /** The port out of a base URL, or Arcane's own default, for the sentence that suggests a fix. */
    private static String portOf(final String baseUrl) {
        try {
            final int port = URI.create(baseUrl.trim()).getPort();
            return port > 0 ? Integer.toString(port) : "3552";
        } catch (final IllegalArgumentException notAUrl) {
            return "3552";
        }
    }

    private RedeployResult interpret(final int status) {
        if (status / 100 == 2) {
            return RedeployResult.triggered("Redeploy accepted by Arcane (HTTP " + status + ")."
                    + " The whole stack goes down and comes back up, this updater included.");
        }
        return switch (status) {
            case 401, 403 -> RedeployResult.refused("Arcane refused the token (HTTP " + status
                    + "). Check arcane.api-key - it is generated under Settings -> API Keys.");
            case 404 -> RedeployResult.refused("Arcane answered 404 for " + endpoint() + "."
                    + " Both path segments are ids, not names: arcane.environment is 0 for Arcane's"
                    + " own host and a UUID for an agent, and arcane.project is a UUID Arcane"
                    + " generated - the compose project name is not accepted there. Read them from"
                    + " GET " + config.baseUrl() + "/api/environments/" + config.environment()
                    + "/projects, or from /api/docs if this version has moved the path.");
            default -> RedeployResult.refused("Arcane answered HTTP " + status + " for "
                    + endpoint() + ". Nothing was restarted.");
        };
    }
}

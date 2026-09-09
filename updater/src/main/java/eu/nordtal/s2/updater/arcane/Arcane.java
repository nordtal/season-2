package eu.nordtal.s2.updater.arcane;

import eu.nordtal.s2.updater.config.UpdaterSpec;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The restart: one redeploy of the whole compose project, through Arcane's REST API.
 *
 * <p>Arcane's API rather than {@code /var/run/docker.sock}, because a container with the socket can
 * do anything on the host - and this container's whole job is downloading files from the internet
 * and putting them where servers will execute them.</p>
 *
 * <p>The redeploy call is <em>expected to be killed</em>: Arcane streams newline-delimited JSON and
 * this container is one of the things it takes down, so the call waits only for the status line and
 * headers and never reads the stream out. Being killed is the successful outcome - the
 * {@code update_request} row is left {@code RUNNING}, and the next start reads a {@code RESTART} in
 * that state as "the redeploy happened".</p>
 *
 * <p>The redeploy path is a setting because Arcane does not publish it, so a version that moves it
 * is one line of config rather than a release. Both of its segments are <b>ids</b>: putting the
 * compose project's name there answers 404.</p>
 *
 * <p>Two base-url values look right and are not, so both are named by {@link #loopback(String)} and
 * {@link #transposedDockerHost(String)}, on the string and without a network: {@code localhost},
 * which inside this container is this container, and {@code docker.host.internal}, which is
 * {@code host.docker.internal} with its labels transposed. Neither is distinguishable from a
 * firewall in the exception, since the JDK wraps a DNS failure in a message-less
 * {@code ConnectException} whose real cause is one level down.</p>
 *
 * <p><a href="https://github.com/getarcaneapp/arcane/issues/1943">arcane#1943</a>: a redeploy of an
 * already-running project may answer success and do nothing. Nothing here can detect it, because
 * the stream that would say so is the one this container is killed while reading.</p>
 */
@Slf4j
public final class Arcane implements ArcaneOps {

    /** The header Arcane's documentation names for token authentication. */
    private static final String API_KEY_HEADER = "X-Api-Key";

    /** Replaced in {@code redeploy-path} by the configured environment id. */
    private static final String ENVIRONMENT_PLACEHOLDER = "{environment}";

    /** Replaced in {@code redeploy-path} by the configured project id. */
    private static final String PROJECT_PLACEHOLDER = "{project}";

    /**
     * Whether an API key would travel in the clear. String-only and static so it can be asserted
     * without a network: it is a property of what an operator typed, not of any request.
     *
     * @param baseUrl the configured origin
     * @param apiKey  the configured key
     * @return whether the two together put a credential on an unencrypted connection
     */
    static boolean cleartextWithKey(final String baseUrl, final String apiKey) {
        return baseUrl != null && apiKey != null && !apiKey.isBlank()
                && baseUrl.stripLeading().toLowerCase(java.util.Locale.ROOT).startsWith("http://");
    }

    /** The name compose.yml maps for this service, and the only spelling Docker publishes. */
    private static final String DOCKER_GATEWAY = "host.docker.internal";

    /** Its three labels as a set, so any order of them can be recognised as the same mistake. */
    private static final Set<String> DOCKER_GATEWAY_LABELS = Set.of("host", "docker", "internal");

    private final UpdaterSpec.ArcaneSpec config;
    private final HttpClient client;

    public Arcane(final @NotNull UpdaterSpec.ArcaneSpec config) {
        this.config = config;
        if (configured() && loopback(config.baseUrl())) {
            // Warned, not refused: the updater is the bootstrap of the whole deployment and the
            // only process that migrates, so it must not fail to start over a restart button.
            log.warn("arcane.base-url is {}, and inside this container that is THIS CONTAINER, not"
                    + " the host Arcane runs on. Every restart will fail with a connection error."
                    + " Use http://host.docker.internal:{} (the updater service maps it), Arcane's"
                    + " own container name if it shares a network with this one, or the host's"
                    + " address on the network.", config.baseUrl(), portOf(config.baseUrl()));
        }
        if (configured() && cleartextWithKey(config.baseUrl(), config.apiKey())) {
            // Warned, not refused: on the local stack, container to host over Docker's own bridge,
            // plain HTTP is defensible. Anywhere the request leaves the machine it is a redeploy
            // credential in the clear.
            log.warn("arcane.base-url is {} - plain HTTP - and an API key is configured. The"
                    + " {} header travels unencrypted, so anything that can see the connection can"
                    + " redeploy every project in that Arcane. That is only acceptable while Arcane"
                    + " is on this same host; in production use an https:// origin.",
                    config.baseUrl(), API_KEY_HEADER);
        }
        transposedDockerHost(config.baseUrl()).ifPresent(suggestion -> log.warn(
                "arcane.base-url is {}, and that host does not exist. Docker publishes the gateway"
                        + " as {} - the labels are transposed. Every restart will fail with a"
                        + " connection error that names DNS only in its cause.",
                config.baseUrl(), suggestion));
        this.client = HttpClient.newBuilder()
                // Follow nothing: every request here carries X-Api-Key, and the JDK keeps that
                // header across a redirect, so following one would hand a redeploy credential to
                // whatever the Location names (CWE-522). There is no legitimate redirect here -
                // base-url is a setting - so a 3xx is reported as a configuration fault instead.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }

    /**
     * Whether a restart can be performed at all. An unconfigured Arcane is a supported state - every
     * other part of the updater works - so every surface asks this before offering the button.
     */
    public boolean configured() {
        return !config.baseUrl().isBlank();
    }

    /**
     * The URL that would be called, for a log line and for a failure message. Both substituted
     * segments are <em>ids</em>, never names - the mistake this class reports by name on a 404.
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
        final java.util.Optional<String> refused = refusedForCleartext();
        if (refused.isPresent()) {
            return RedeployResult.refused(refused.get());
        }
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
            // ofLines() is lazy: send() returns on the status line and headers, and the stream is
            // never read - this container is killed part way through the redeploy it just asked for.
            final HttpResponse<?> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
            final String redirected = redirect(response.statusCode(), response.headers(), endpoint());
            return redirected != null ? RedeployResult.refused(redirected)
                    : interpret(response.statusCode());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return RedeployResult.refused("Interrupted while asking Arcane to redeploy.");
        } catch (final IOException failure) {
            // Includes the redeploy having already taken this container's network with it. Reported
            // as refused rather than triggered so that a person looks at an ambiguous restart.
            return RedeployResult.refused(unreachable(uri, failure, config.baseUrl()));
        }
    }

    /**
     * The exception and everything under it, innermost last. {@code HttpClient} answers a DNS
     * failure with a message-less {@code ConnectException}, so without the causes a refused
     * connection, a name that does not exist and a dead route all print the same eleven characters.
     */
    static @NotNull String causeChain(final @NotNull Throwable failure) {
        final StringBuilder text = new StringBuilder(failure.toString());
        Throwable cause = failure.getCause();
        int depth = 0;
        // Bounded: self-referencing causes exist, and a loop here would hang the request that is
        // trying to explain why something else failed.
        while (cause != null && cause != cause.getCause() && depth++ < 5) {
            text.append(" <- ").append(cause);
            cause = cause.getCause();
        }
        return text.toString();
    }

    /**
     * The sentence written into {@code update_request.result} when the call did not connect. Static
     * and taking the base URL, so it can be asserted from a test with no network and no Arcane.
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
     * Docker's three labels in the wrong order. Nothing in the failure tells {@code
     * docker.host.internal} apart from a firewall - the name simply does not resolve - so it is
     * checked on the string, without a network.
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
     * Whether a base URL points at the machine making the request. String-only so it can be
     * asserted without a container: it has to fire on a URL that works from a laptop and cannot
     * work here.
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

    /**
     * Whether this origin is the machine this container is already on - the one place plain HTTP
     * with an API key is defensible, because no network segment carries it. Anywhere else an
     * {@code X-Api-Key} on an unencrypted connection is a redeploy credential in the clear.
     */
    static boolean sameHost(final String baseUrl) {
        final String host = hostOf(baseUrl);
        return host != null && (loopback(baseUrl) || DOCKER_GATEWAY.equals(host));
    }

    /**
     * Why this Arcane must not be called at all, or empty when it may be. Every request carries the
     * key, so a remote origin over {@code http://} is refused here rather than merely warned about.
     *
     * <p>Deliberately not a constructor failure: the updater is the bootstrap of the whole
     * deployment and the only process that migrates, so a misconfigured restart button must not
     * stop it starting.</p>
     */
    private java.util.Optional<String> refusedForCleartext() {
        if (!cleartextWithKey(config.baseUrl(), config.apiKey()) || sameHost(config.baseUrl())) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("arcane.base-url is " + config.baseUrl() + " - plain HTTP to a"
                + " host that is not this one - and an API key is configured. Nothing was done,"
                + " because sending the " + API_KEY_HEADER + " header over that connection hands"
                + " anything on the wire the ability to redeploy every project in that Arcane. Use"
                + " an https:// origin. The only exception is the local stack, where"
                + " http://" + DOCKER_GATEWAY + " reaches the host across Docker's own bridge and"
                + " leaves the machine at no point.");
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

    // ---------------------------------------------------------------- the update sequence

    /**
     * Every service of the project, with its container id, its status and its health.
     *
     * <p>Called three times in a run, for reasons that look identical: as proof that Arcane is
     * reachable before a jar is touched, because a run that cannot stop anything must not swap
     * anything; to turn service names into the container ids stop and start are addressed to; and
     * repeatedly at the end until every stopped service says it is back.</p>
     *
     * @return the services, or empty with the reason in {@code message}
     */
    @Override
    public @NotNull RuntimeResult runtime() {
        final java.util.Optional<String> refused = refusedForCleartext();
        if (refused.isPresent()) {
            return RuntimeResult.unreachable(refused.get());
        }
        if (!configured()) {
            return RuntimeResult.unreachable("Arcane is not configured (arcane.base-url is empty),"
                    + " so this updater cannot stop or start anything. Nothing was touched. Set"
                    + " arcane.base-url, arcane.environment, arcane.project and arcane.api-key -"
                    + " or fill EMPTY volumes by hand with `docker compose run --rm updater"
                    + " bootstrap` on the host. That command does not upgrade anything: an upgrade"
                    + " needs the servers stopped first, and only this sequence does that.");
        }
        final String url = config.baseUrl() + substitute(config.runtimePath());
        final URI uri;
        try {
            uri = new URI(url);
        } catch (final URISyntaxException broken) {
            return RuntimeResult.unreachable("arcane.base-url and arcane.runtime-path do not form a"
                    + " valid URL: " + url);
        }

        try {
            final HttpResponse<String> response = client.send(get(uri),
                    HttpResponse.BodyHandlers.ofString());
            final String redirected = redirect(response.statusCode(), response.headers(), url);
            if (redirected != null) {
                return RuntimeResult.unreachable(redirected);
            }
            if (response.statusCode() / 100 != 2) {
                return RuntimeResult.unreachable("Arcane answered HTTP " + response.statusCode()
                        + " for " + url + ", so this updater cannot see the project's services."
                        + " Nothing was touched." + (response.statusCode() == 401
                        || response.statusCode() == 403
                        ? " Check arcane.api-key." : " Check arcane.environment and arcane.project"
                        + " - both are ids, not names."));
            }
            return RuntimeResult.of(ArcaneRuntime.parse(response.body()));
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return RuntimeResult.unreachable("Interrupted while reading the project's services.");
        } catch (final IOException failure) {
            return RuntimeResult.unreachable(unreachable(uri, failure, config.baseUrl()));
        }
    }

    /**
     * Stops one container and waits for Arcane to say it did.
     *
     * <p><b>Arcane's own stop timeout is thirty seconds and it does not honour
     * {@code stop_grace_period}</b>, and there is no way to widen it from here. A server that ever
     * needs longer than that to save is killed, and that surfaces as a damaged region file rather
     * than as an error on this call.</p>
     */
    @Override
    public @NotNull RedeployResult stop(final @NotNull String containerId) {
        return container(containerId, "stop");
    }

    /** Starts one container again. Being started is not being back - see {@link #runtime()}. */
    @Override
    public @NotNull RedeployResult start(final @NotNull String containerId) {
        return container(containerId, "start");
    }

    /**
     * Asks Arcane to snapshot one volume.
     *
     * <p>The body is empty on purpose, so Arcane uses the volume's own backup policy: sending a
     * destination from here would be a second copy of a decision taken in Arcane's interface.</p>
     *
     * <p>A 409 ("a backup of this volume is already running") is reported as a refusal rather than
     * retried: the servers are already down, and waiting on somebody else's snapshot of unknown age
     * is a longer outage for a volume this run did not save.</p>
     */
    @Override
    public @NotNull BackupResult backup(final @NotNull String volume) {
        final java.util.Optional<String> refused = refusedForCleartext();
        if (refused.isPresent()) {
            return BackupResult.refused(refused.get());
        }
        if (!configured()) {
            return BackupResult.refused("Arcane is not configured, so nothing could be backed up."
                    + " Set arcane.base-url, arcane.environment, arcane.project and"
                    + " arcane.api-key.");
        }
        final URI uri = backupUri(volume);
        if (uri == null) {
            return BackupResult.refused("arcane.base-url and arcane.backup-path do not form a valid"
                    + " URL for volume " + volume);
        }

        log.info("Asking Arcane to back up volume {}", volume);
        try {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .header(API_KEY_HEADER, config.apiKey())
                            .header("Accept", "application/json")
                            .header("User-Agent", "nordtal-season-2/updater")
                            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 409) {
                return BackupResult.refused("Arcane says a backup of " + volume + " is already"
                        + " running, so this run did not start a second one.");
            }
            if (response.statusCode() / 100 != 2) {
                final String redirected =
                        redirect(response.statusCode(), response.headers(), uri.toString());
                return BackupResult.refused(redirected != null ? redirected
                        : "Arcane answered HTTP " + response.statusCode() + " to back up " + volume
                        + " (" + uri + "). A 404 here is usually the volume name: it is the name"
                        + " `docker volume ls` prints, project prefix included.");
            }
            final String id = ArcaneBackups.startedId(response.body());
            if (id == null) {
                // Worse than refused: the snapshot is probably happening with no way to ask about
                // it, so the run cannot tell saved from failed.
                return BackupResult.refused("Arcane accepted the backup of " + volume + " but its"
                        + " answer carried no backup id, so this run cannot tell whether it"
                        + " finished. Its shape has changed - see arcane.backup-path.");
            }
            return BackupResult.running(id, "started");
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return BackupResult.refused("Interrupted while asking Arcane to back up " + volume);
        } catch (final IOException failure) {
            return BackupResult.refused(unreachable(uri, failure, config.baseUrl()));
        }
    }

    /**
     * Reads one started snapshot back out of the volume's backup list.
     *
     * <p>Every unreadable answer here is {@code RUNNING}, never {@code FAILED}: a poll that times
     * out says nothing about a snapshot happening inside Arcane, and treating it as a failure would
     * start the servers again on top of a half-written volume.</p>
     */
    @Override
    public @NotNull BackupResult backupState(final @NotNull String volume,
                                             final @NotNull String backupId) {
        if (!configured()) {
            return BackupResult.refused("Arcane is not configured.");
        }
        // Before get(uri), which puts X-Api-Key on the wire. backup() already refuses a cleartext
        // origin, but that is a property of one caller and the next would not know.
        final java.util.Optional<String> refused = refusedForCleartext();
        if (refused.isPresent()) {
            return BackupResult.refused(refused.get());
        }
        final URI uri = backupUri(volume);
        if (uri == null) {
            return BackupResult.running(backupId, "the backup URL for " + volume + " is not valid");
        }
        try {
            final HttpResponse<String> response = client.send(get(uri),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return BackupResult.running(backupId, "Arcane answered HTTP "
                        + response.statusCode() + " while this backup was being watched");
            }
            final ArcaneBackups.Entry entry = ArcaneBackups.find(response.body(), backupId);
            if (entry == null) {
                // Arcane lists newest first and this run started the newest one, so a miss is a
                // shape change or a very busy volume - either way, keep waiting.
                return BackupResult.running(backupId,
                        "Arcane's backup list does not carry this backup yet");
            }
            if (entry.succeeded()) {
                return BackupResult.succeeded(backupId, "saved");
            }
            if (entry.failed()) {
                return BackupResult.failed(backupId, entry.error() == null
                        ? "Arcane reported the backup as failed" : entry.error());
            }
            return BackupResult.running(backupId, entry.status());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return BackupResult.running(backupId, "interrupted while watching this backup");
        } catch (final IOException failure) {
            return BackupResult.running(backupId, "Arcane could not be read: " + failure);
        }
    }

    /** The backup endpoint for one volume, or {@code null} when the two settings do not form one. */
    private URI backupUri(final String volume) {
        final String url = config.baseUrl() + substitute(config.backupPath())
                .replace("{volume}", URLEncoder.encode(volume, StandardCharsets.UTF_8));
        try {
            return new URI(url);
        } catch (final URISyntaxException broken) {
            return null;
        }
    }

    private RedeployResult container(final String containerId, final String action) {
        final java.util.Optional<String> refused = refusedForCleartext();
        if (refused.isPresent()) {
            return RedeployResult.refused(refused.get());
        }
        if (!configured()) {
            // Not action + "ped": that reads "stopped" for a stop and "startped" for a start.
            return RedeployResult.refused("Arcane is not configured, so nothing could be "
                    + ("stop".equals(action) ? "stopped" : "started") + ".");
        }
        final String url = config.baseUrl() + substitute(config.containerPath())
                .replace("{container}", containerId)
                .replace("{action}", action);
        final URI uri;
        try {
            uri = new URI(url);
        } catch (final URISyntaxException broken) {
            return RedeployResult.refused("arcane.container-path does not form a valid URL: " + url);
        }

        log.info("Asking Arcane to {} container {}", action, containerId);
        try {
            final HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .header(API_KEY_HEADER, config.apiKey())
                            .header("Accept", "application/json")
                            .header("User-Agent", "nordtal-season-2/updater")
                            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 == 2) {
                return RedeployResult.triggered("HTTP " + response.statusCode());
            }
            final String redirected = redirect(response.statusCode(), response.headers(), url);
            if (redirected != null) {
                return RedeployResult.refused(redirected);
            }
            return RedeployResult.refused("Arcane answered HTTP " + response.statusCode() + " to "
                    + action + " " + containerId + " (" + url + ")");
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return RedeployResult.refused("Interrupted while asking Arcane to " + action + ".");
        } catch (final IOException failure) {
            return RedeployResult.refused(unreachable(uri, failure, config.baseUrl()));
        }
    }

    private HttpRequest get(final URI uri) {
        return HttpRequest.newBuilder(uri)
                .GET()
                .header(API_KEY_HEADER, config.apiKey())
                .header("Accept", "application/json")
                .header("User-Agent", "nordtal-season-2/updater")
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }

    /** Both ids into a configured path template. */
    private String substitute(final String path) {
        return path.replace(ENVIRONMENT_PLACEHOLDER, config.environment())
                .replace(PROJECT_PLACEHOLDER, config.project());
    }

    /**
     * What a redirect means here: nothing follows one, so a 3xx is a configuration fact - the base
     * URL is not where Arcane is, and the key must not be handed to whatever the Location names.
     *
     * @return the sentence, or {@code null} when this is not a redirect
     */
    private static String redirect(final int status, final java.net.http.HttpHeaders headers,
                                   final String url) {
        if (status / 100 != 3) {
            return null;
        }
        return "Arcane answered HTTP " + status + " for " + url + " - a redirect to "
                + headers.firstValue("Location").orElse("somewhere it did not name") + ". This"
                + " updater does not follow redirects on a request carrying an API key, because"
                + " the header would travel to wherever the redirect points. Nothing was done."
                + " Point arcane.base-url at the address Arcane actually answers on.";
    }

    private RedeployResult interpret(final int status) {
        if (status / 100 == 2) {
            return RedeployResult.triggered("Redeploy accepted by Arcane (HTTP " + status + ")."
                    + " The whole stack goes down and comes back up, this updater included - which"
                    + " is why nothing can report whether it did. An update or a restart cycles"
                    + " services one at a time instead and stays alive to say.");
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

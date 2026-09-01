package eu.nordtal.s2.updater.arcane;

import eu.nordtal.s2.updater.config.UpdaterSpec;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
 * Arcane's public documentation describes {@code X-Api-Key} authentication and says that project
 * deploy, redeploy, pull and build exist as streaming operations; it does <b>not</b> publish the
 * paths (read 2026-09-01). Those are on {@code /api/docs} on our own instance, which no agent can
 * reach. So {@code arcane.redeploy-path} carries a documented guess, a 404 from it is reported
 * with that sentence attached, and correcting it is one line in {@code updater.yml}.
 */
@Slf4j
public final class Arcane {

    /** The header Arcane's documentation names for token authentication. */
    private static final String API_KEY_HEADER = "X-Api-Key";

    /** Replaced in {@code redeploy-path} by the configured project name. */
    private static final String PROJECT_PLACEHOLDER = "{project}";

    private final UpdaterSpec.ArcaneSpec config;
    private final HttpClient client;

    public Arcane(final @NotNull UpdaterSpec.ArcaneSpec config) {
        this.config = config;
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

    /** The URL that would be called, for a log line and for the message on a failure. */
    public @NotNull String endpoint() {
        return config.baseUrl() + config.redeployPath().replace(PROJECT_PLACEHOLDER, config.project());
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
            return RedeployResult.refused("Could not reach Arcane at " + uri + ": " + failure);
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
                    + " THIS PATH IS A GUESS: Arcane's public documentation does not publish it."
                    + " Read /api/docs on the instance and set arcane.redeploy-path to what it says."
                    + " Check arcane.project as well - it must be the project's name in Arcane.");
            default -> RedeployResult.refused("Arcane answered HTTP " + status + " for "
                    + endpoint() + ". Nothing was restarted.");
        };
    }
}

package eu.nordtal.s2.steward.worker.docker;

import com.google.gson.JsonArray;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RedeployResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;

/**
 * {@link ContainerOps#recreate}, asked of {@code steward-deployer} over HTTP instead of refused.
 *
 * <h2>The boundary this decorates rather than removes</h2>
 * {@link DockerOps#recreate} still refuses every time: it has no compose file, and a container
 * rebuilt from an {@code inspect} would drift from it silently (season-2-ops/22). That refusal is
 * correct and stays exactly where it is. What was missing was a way to ask <em>across</em> the
 * boundary rather than a hole in it - {@code steward-deployer} carries the compose file and exposes
 * it over HTTP, so that a service whose image the registry has moved past is actually renewed
 * rather than reported {@code FAILED} on every single run until a person runs
 * {@code docker compose up} by hand.
 *
 * <h2>It asks for a deploy, not a recreate, and that is season-2-ops/140</h2>
 * The deployer has two routes and season-2-ops/134 put a real difference between them:
 * {@code POST /api/recreate/{service}} rebuilds the container <b>from the image already on this
 * host</b>, and {@code POST /api/deploy} pulls first. That split is right - the button an admin
 * presses to un-wedge a container must not silently replace a locally built image with the
 * published one - but this class went on asking for the recreate, and an update run's whole reason
 * to touch a container is that the registry has something the host does not.
 *
 * <p>The result was a run that reported "pulling its image and recreating the container", pulled
 * nothing, came back healthy on the same stale image, and therefore found the same service
 * {@code OUTDATED} on the next run: measured on this host on 2026-09-20, two consecutive update
 * runs stopped and recreated all four Minecraft services and the image on disk was the one from
 * 2026-09-18 both times, while a plain {@code docker pull} by hand fetched a newer one immediately.
 * A run that takes the network down to change nothing is the defect this project has regressed
 * into before, so the direction of the fix is fixed: <b>the update run takes the fetching
 * route.</b></p>
 *
 * <p>A pull that fails is not a server left off. The deployer tolerates a failed pull when the
 * image is already here, and when it does not, {@code UpdateRun#start} starts the old container
 * again and settles the line {@code FAILED} - the same fallback that was already there for a
 * refused recreate.</p>
 *
 * <h2>Everything else passes through unchanged</h2>
 * {@link #runtime}, {@link #stop}, {@link #start} and {@link #images} are the delegate's, untouched
 * - this class only ever speaks to the deployer for the one thing {@code DockerOps} cannot do.
 *
 * <h2>Why a poll and not the SSE stream</h2>
 * steward-deployer also serves {@code GET /api/jobs/{id}/stream}, which is what steward-ui's
 * console reads from. An update run has no console to draw into: {@code UpdateRun} calls
 * {@link #recreate} once and needs exactly one verdict - triggered, refused or unverified - so a
 * plain {@code GET /api/jobs/{id}} asked every few seconds is the whole answer, with no connection
 * to keep alive underneath a bigger retry loop.
 */
public final class DeployerRecreate implements ContainerOps {

    private static final Gson GSON = new Gson();

    /** How often the job is asked about. Cheap: one small request to a service on this network. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

    private final ContainerOps delegate;
    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final Duration requestTimeout;
    private final Duration patience;
    private final Waiting waiting;

    public DeployerRecreate(final @NotNull ContainerOps delegate, final @NotNull String baseUrl,
                            final @NotNull String token, final @NotNull Duration requestTimeout,
                            final @NotNull Duration patience) {
        this(delegate, baseUrl, token, requestTimeout, patience, Waiting.real());
    }

    /** Package-visible so a test can drive the poll loop without sleeping through it. */
    DeployerRecreate(final @NotNull ContainerOps delegate, final @NotNull String baseUrl,
                     final @NotNull String token, final @NotNull Duration requestTimeout,
                     final @NotNull Duration patience, final @NotNull Waiting waiting) {
        this.delegate = delegate;
        this.baseUrl = plaintextOnlyInside(trimmed(baseUrl));
        this.token = token;
        this.requestTimeout = requestTimeout;
        this.patience = patience;
        this.waiting = waiting;
        this.http = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    }

    @Override
    public @NotNull RuntimeResult runtime() {
        return delegate.runtime();
    }

    @Override
    public @NotNull RedeployResult stop(final @NotNull String containerId) {
        return delegate.stop(containerId);
    }

    @Override
    public @NotNull RedeployResult start(final @NotNull String containerId) {
        return delegate.start(containerId);
    }

    @Override
    public @NotNull ImageResult images() {
        return delegate.images();
    }

    /**
     * {@code POST /api/deploy} on steward-deployer naming this one service, then polls the job it
     * hands back until it settles or this call's patience runs out.
     *
     * <p>The service is named, never left out. An empty list means <em>every</em> service to
     * compose, and the deployer's own {@code servicesToDeploy} exists because that mistake has
     * been made here before.</p>
     */
    @Override
    public @NotNull RedeployResult recreate(final @NotNull String service) {
        final Instant deadline = waiting.now().plus(patience);

        final HttpResponse<String> accepted;
        try {
            accepted = http.send(request("/api/deploy")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(deployBody(service)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (final HttpTimeoutException slow) {
            return RedeployResult.refused("steward-deployer did not accept the recreate of "
                    + service + " within " + requestTimeout.toSeconds() + "s");
        } catch (final IOException unreachable) {
            return RedeployResult.refused("could not reach steward-deployer to recreate " + service
                    + ": " + unreachable.getMessage());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return RedeployResult.refused(
                    "interrupted while asking steward-deployer to recreate " + service);
        }
        if (accepted.statusCode() != 202) {
            return RedeployResult.refused("steward-deployer answered " + accepted.statusCode()
                    + " for the recreate of " + service + ": " + accepted.body());
        }

        final String jobId;
        try {
            jobId = GSON.fromJson(accepted.body(), JsonObject.class).get("id").getAsString();
        } catch (final RuntimeException malformed) {
            return RedeployResult.unverified("steward-deployer accepted the recreate of " + service
                    + " but its answer named no job id to follow: " + accepted.body());
        }
        return poll(service, jobId, deadline);
    }

    private RedeployResult poll(final String service, final String jobId, final Instant deadline) {
        while (true) {
            final JsonObject job;
            try {
                final HttpResponse<String> response = http.send(
                        request("/api/jobs/" + jobId).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return RedeployResult.unverified("steward-deployer accepted the recreate of "
                            + service + " (job " + jobId + ") but answered " + response.statusCode()
                            + " when asked how it went");
                }
                job = GSON.fromJson(response.body(), JsonObject.class);
            } catch (final IOException failure) {
                return RedeployResult.unverified("steward-deployer accepted the recreate of "
                        + service + " (job " + jobId + "), and whether it finished could not be"
                        + " read back: " + failure.getMessage());
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return RedeployResult.unverified("interrupted while waiting for steward-deployer's"
                        + " recreate of " + service + " (job " + jobId + ") to finish");
            }

            final String state = job.has("state") ? job.get("state").getAsString() : "";
            if ("DONE".equals(state)) {
                return RedeployResult.triggered(
                        "steward-deployer recreated " + service + " (job " + jobId + ")");
            }
            if ("FAILED".equals(state)) {
                return RedeployResult.refused("steward-deployer's recreate of " + service
                        + " failed (job " + jobId + "): " + lastLine(job));
            }

            if (!waiting.now().isBefore(deadline)) {
                return RedeployResult.unverified("steward-deployer's recreate of " + service
                        + " (job " + jobId + ") had not finished after " + patience.toSeconds()
                        + "s; it may still be running - check `docker logs"
                        + " nordtal-s2-steward-deployer-1` or GET /api/jobs/" + jobId);
            }
            if (!waiting.sleep(POLL_INTERVAL)) {
                return RedeployResult.unverified("interrupted while waiting for steward-deployer's"
                        + " recreate of " + service + " (job " + jobId + ") to finish");
            }
        }
    }

    private static String lastLine(final JsonObject job) {
        if (!job.has("lines")) {
            return "no output";
        }
        final JsonArray lines = job.getAsJsonArray("lines");
        return lines.isEmpty() ? "no output" : lines.get(lines.size() - 1).getAsString();
    }

    /**
     * The body of {@code POST /api/deploy} for exactly one service.
     *
     * <p>Built with Gson rather than by concatenation so a service name can never end the JSON
     * string early, and package-visible so a test can read the bytes that go out - "it names one
     * service" is the assertion that separates this from the empty list compose reads as "all".</p>
     */
    static String deployBody(final @NotNull String service) {
        final JsonArray services = new JsonArray();
        services.add(service);
        final JsonObject body = new JsonObject();
        body.add("services", services);
        return GSON.toJson(body);
    }

    private HttpRequest.Builder request(final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-Steward-Token", token)
                .timeout(requestTimeout);
    }

    private static String trimmed(final String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Refuses to send the token in clear to an address outside this deployment.
     *
     * <p>{@code deployer.url} lives in {@code steward.yml}, which is one of the files the config
     * editor offers - so it is one careless save away from being pointed at a public address, with
     * nothing stopping this token going out in clear the next time a recreate is asked for. Plain
     * {@code http} is therefore only allowed to a name with no dot in it (a compose service, which
     * cannot be a public DNS name) or to loopback; anything else has to be {@code https}. Mirrors
     * steward-ui's {@code InternalClient}, which guards the same token for the same reason on the
     * other side of this same call.</p>
     */
    private static String plaintextOnlyInside(final String baseUrl) {
        final URI uri = URI.create(baseUrl);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return baseUrl;
        }
        final String host = uri.getHost();
        if ("http".equalsIgnoreCase(uri.getScheme()) && host != null
                && (!host.contains(".") || "127.0.0.1".equals(host))) {
            return baseUrl;
        }
        throw new IllegalArgumentException("deployer.url is " + baseUrl + ", and steward-worker"
                + " will not send its token there in clear. It is https, or plain http to a"
                + " compose service name on the internal network - which is what the default"
                + " http://steward-deployer:8081 is.");
    }

    /** How "now" and "wait a bit" are told, so the poll loop can be driven in a test without sleeping. */
    interface Waiting {

        Instant now();

        /** @return false when interrupted, which ends the wait rather than swallowing it. */
        boolean sleep(Duration duration);

        static Waiting real() {
            return new Waiting() {
                @Override
                public Instant now() {
                    return Instant.now();
                }

                @Override
                public boolean sleep(final Duration duration) {
                    try {
                        Thread.sleep(duration.toMillis());
                        return true;
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            };
        }
    }
}

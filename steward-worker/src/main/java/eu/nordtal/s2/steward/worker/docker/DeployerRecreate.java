package eu.nordtal.s2.steward.worker.docker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RedeployResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;

/**
 * {@link ContainerOps#deploy} and {@link ContainerOps#recreate}, asked of {@code steward-deployer} over HTTP.
 *
 * The boundary this decorates rather than removes: {@link DockerOps#deploy} still refuses every time: it has no
 * compose file, and a container rebuilt from an {@code inspect} would drift from it silently. That refusal is
 * correct and stays exactly where it is. What was missing was a way to ask across the boundary rather than a hole
 * in it - {@code steward-deployer} carries the compose file and exposes it over HTTP, so that a service whose image
 * the registry has moved past is actually renewed rather than reported {@code FAILED} on every single run until a
 * person runs {@code docker compose up} by hand.
 *
 * An update run asks for a deploy, not a recreate. The deployer has two routes with a real difference between them:
 * {@code POST /api/recreate/{service}} rebuilds the container from the image already on this host, and
 * {@code POST /api/deploy} pulls first. That split is right - the button an admin presses to un-wedge a container
 * must not silently replace a locally built image with the published one - but an update run's whole reason to
 * touch a container is that the registry has something the host does not, so it has to take the fetching route: a
 * run that reports "pulling its image and recreating the container" while asking for a recreate pulls nothing,
 * comes back healthy on the same stale image, and finds the same service {@code OUTDATED} again on the next run. A
 * run that takes the network down to change nothing is the defect this project has regressed into before.
 *
 * A pull that fails is not a server left off. The deployer tolerates a failed pull when the image is already here,
 * and when it does not, {@code UpdateRun#start} starts the old container again and settles the line {@code FAILED} -
 * the same fallback that was already there for a refused recreate.
 *
 * The recreate route is not dead, it has a different caller: a standby is started with it, precisely because it
 * must not fetch. See {@link #recreate}.
 *
 * Everything else passes through unchanged: {@link #runtime}, {@link #stop}, {@link #start} and {@link #images} are
 * the delegate's, untouched - this class only ever speaks to the deployer for the one thing {@code DockerOps} cannot
 * do.
 *
 * Why a poll and not the SSE stream: steward-deployer also serves {@code GET /api/jobs/{id}/stream}, which is what
 * steward-ui's console reads from. An update run has no console to draw into: {@code UpdateRun} calls
 * {@link #deploy} once and needs exactly one verdict - triggered, refused or unverified - so a plain
 * {@code GET /api/jobs/{id}} asked every few seconds is the whole answer, with no connection to keep alive
 * underneath a bigger retry loop.
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

    public DeployerRecreate(
            final ContainerOps delegate,
            final String baseUrl,
            final String token,
            final Duration requestTimeout,
            final Duration patience) {
        this(delegate, baseUrl, token, requestTimeout, patience, Waiting.real());
    }

    /** Package-visible so a test can drive the poll loop without sleeping through it. */
    DeployerRecreate(
            final ContainerOps delegate,
            final String baseUrl,
            final String token,
            final Duration requestTimeout,
            final Duration patience,
            final Waiting waiting) {
        this.delegate = delegate;
        this.baseUrl = plaintextOnlyInside(trimmed(baseUrl));
        this.token = token;
        this.requestTimeout = requestTimeout;
        this.patience = patience;
        this.waiting = waiting;
        this.http = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    }

    @Override
    public RuntimeResult runtime() {
        return delegate.runtime();
    }

    @Override
    public RedeployResult stop(final String containerId) {
        return delegate.stop(containerId);
    }

    @Override
    public RedeployResult start(final String containerId) {
        return delegate.start(containerId);
    }

    @Override
    public ImageResult images() {
        return delegate.images();
    }

    /**
     * {@code POST /api/deploy} on steward-deployer naming this one service.
     *
     * Then polls the job it hands back until it settles or this call's patience runs out.
     *
     * The service is named, never left out. An empty list means every service to compose, and the deployer's own
     * {@code servicesToDeploy} exists because that mistake has been made here before.
     */
    @Override
    public RedeployResult deploy(final String service) {
        return submit(
                service,
                "deploy",
                () -> request("/api/deploy")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(deployBody(service)))
                        .build());
    }

    /**
     * {@code POST /api/recreate/{service}}: the container again, from the image already here.
     *
     * The route a standby needs, as opposed to the one that pulls first. A standby exists to stand in
     * for a live service for a minute, so it has to run the same image that service is running - and on this deployment
     * that image is very often one built on the host and pushed to no registry. A pull here would put the published
     * image under the standby while the live proxy runs the local one, which is the same silent downgrade
     * kept off the admin's Recreate button, arriving through a different door.
     */
    @Override
    public RedeployResult recreate(final String service) {
        return submit(
                service,
                "recreate",
                () -> request("/api/recreate/" + service)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build());
    }

    /**
     * Sends one of the two requests and follows the job it hands back.
     *
     * @param what the word every message uses for what was asked - "deploy" or "recreate". Both
     *             routes fail in exactly the same ways and a reader of the report has to be able to
     *             tell which one was asked, because whether an image was fetched is the difference
     *             between the two
     */
    private RedeployResult submit(
            final String service, final String what, final java.util.function.Supplier<HttpRequest> build) {
        final Instant deadline = waiting.now().plus(patience);

        final HttpResponse<String> accepted;
        try {
            accepted = http.send(build.get(), HttpResponse.BodyHandlers.ofString());
        } catch (final HttpTimeoutException slow) {
            return RedeployResult.refused("steward-deployer did not accept the " + what + " of " + service + " within "
                    + requestTimeout.toSeconds() + "s");
        } catch (final IOException unreachable) {
            return RedeployResult.refused(
                    "could not reach steward-deployer to " + what + " " + service + ": " + unreachable.getMessage());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return RedeployResult.refused("interrupted while asking steward-deployer to " + what + " " + service);
        }
        if (accepted.statusCode() != 202) {
            return RedeployResult.refused("steward-deployer answered " + accepted.statusCode() + " for the " + what
                    + " of " + service + ": " + accepted.body());
        }

        final String jobId;
        try {
            jobId = GSON.fromJson(accepted.body(), JsonObject.class).get("id").getAsString();
        } catch (final RuntimeException malformed) {
            return RedeployResult.unverified("steward-deployer accepted the " + what + " of " + service
                    + " but its answer named no job id to follow: " + accepted.body());
        }
        return poll(service, what, jobId, deadline);
    }

    private RedeployResult poll(final String service, final String what, final String jobId, final Instant deadline) {
        while (true) {
            final JsonObject job;
            try {
                final HttpResponse<String> response =
                        http.send(request("/api/jobs/" + jobId).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return RedeployResult.unverified("steward-deployer accepted the " + what + " of "
                            + service + " (job " + jobId + ") but answered " + response.statusCode()
                            + " when asked how it went");
                }
                job = GSON.fromJson(response.body(), JsonObject.class);
            } catch (final IOException failure) {
                return RedeployResult.unverified("steward-deployer accepted the " + what + " of "
                        + service + " (job " + jobId + "), and whether it finished could not be"
                        + " read back: " + failure.getMessage());
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return RedeployResult.unverified("interrupted while waiting for steward-deployer's " + what + " of "
                        + service + " (job " + jobId + ") to finish");
            }

            final String state = job.has("state") ? job.get("state").getAsString() : "";
            if ("DONE".equals(state)) {
                return RedeployResult.triggered(
                        "steward-deployer finished the " + what + " of " + service + " (job " + jobId + ")");
            }
            if ("FAILED".equals(state)) {
                return RedeployResult.refused("steward-deployer's " + what + " of " + service + " failed (job " + jobId
                        + "): " + lastLine(job));
            }

            if (!waiting.now().isBefore(deadline)) {
                return RedeployResult.unverified("steward-deployer's " + what + " of " + service
                        + " (job " + jobId + ") had not finished after " + patience.toSeconds()
                        + "s; it may still be running - check `docker logs"
                        + " nordtal-s2-steward-deployer-1` or GET /api/jobs/" + jobId);
            }
            if (!waiting.sleep(POLL_INTERVAL)) {
                return RedeployResult.unverified("interrupted while waiting for steward-deployer's " + what + " of "
                        + service + " (job " + jobId + ") to finish");
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
     * Built with Gson rather than by concatenation so a service name can never end the JSON string early, and
     * package-visible so a test can read the bytes that go out - "it names one service" is the assertion that separates
     * this from the empty list compose reads as "all".
     */
    static String deployBody(final String service) {
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
     * {@code deployer.url} lives in {@code steward.yml}, which is one of the files the config editor offers - so it
     * is one careless save away from being pointed at a public address, with nothing stopping this token going out
     * in clear the next time a recreate is asked for. Plain {@code http} is therefore only allowed to a name with
     * no dot in it (a compose service, which cannot be a public DNS name) or to loopback; anything else has to be
     * {@code https}. Mirrors steward-ui's {@code InternalClient}, which guards the same token for the same reason
     * on the other side of this same call.
     */
    private static String plaintextOnlyInside(final String baseUrl) {
        final URI uri = URI.create(baseUrl);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return baseUrl;
        }
        final String host = uri.getHost();
        if ("http".equalsIgnoreCase(uri.getScheme())
                && host != null
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

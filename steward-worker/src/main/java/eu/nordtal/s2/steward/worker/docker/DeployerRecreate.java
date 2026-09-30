package eu.nordtal.s2.steward.worker.docker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.http.Reply;
import eu.nordtal.s2.common.http.WebClient;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.steward.worker.ops.ContainerOps;
import eu.nordtal.s2.steward.worker.ops.ImageResult;
import eu.nordtal.s2.steward.worker.ops.RedeployResult;
import eu.nordtal.s2.steward.worker.ops.RuntimeResult;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;

/**
 * {@link ContainerOps#deploy} and {@link ContainerOps#recreate}, asked of {@code steward-deployer} over HTTP.
 *
 * An update run deploys, which pulls first; a standby recreates, which must not. Everything else is the delegate's.
 */
public final class DeployerRecreate implements ContainerOps {

    /** How often the job is asked about. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

    private final ContainerOps delegate;
    private final WebClient web;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final Duration patience;
    private final Waiting waiting;

    public DeployerRecreate(
            final ContainerOps delegate,
            final String baseUrl,
            final String token,
            final Duration requestTimeout,
            final Duration patience,
            final Waiting waiting) {
        this.delegate = delegate;
        this.baseUrl = WebClient.tokenSafe("deployer.url", baseUrl);
        this.requestTimeout = requestTimeout;
        this.patience = patience;
        this.waiting = waiting;
        this.web = WebClient.create(requestTimeout).header("X-Steward-Token", token);
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
     * {@code POST /api/deploy} naming this one service, then polls the job until it settles or patience runs out.
     *
     * The service is always named: an empty list means every service to compose.
     */
    @Override
    public RedeployResult deploy(final String service) {
        return submit(
                service,
                "deploy",
                () -> web.post(URI.create(baseUrl + "/api/deploy"), "application/json", deployBody(service)));
    }

    /**
     * {@code POST /api/recreate/{service}}: the container again, from the image already here.
     *
     * A standby runs the image its live service runs, often built on the host, so a pull would downgrade it.
     */
    @Override
    public RedeployResult recreate(final String service) {
        return submit(service, "recreate", () -> web.postEmpty(URI.create(baseUrl + "/api/recreate/" + service)));
    }

    /**
     * Sends one of the two requests and follows the job it hands back.
     *
     * @param what "deploy" or "recreate", named in every message since only a deploy fetches an image
     */
    private RedeployResult submit(final String service, final String what, final Submission send) {
        final Instant deadline = waiting.now().plus(patience);

        final Reply accepted;
        try {
            accepted = send.send();
        } catch (final HttpTimeoutException slow) {
            return RedeployResult.refused("steward-deployer did not accept the " + what + " of " + service + " within "
                    + requestTimeout.toSeconds() + "s");
        } catch (final InterruptedIOException interrupted) {
            return RedeployResult.refused("interrupted while asking steward-deployer to " + what + " " + service);
        } catch (final IOException unreachable) {
            return RedeployResult.refused(
                    "could not reach steward-deployer to " + what + " " + service + ": " + unreachable.getMessage());
        }
        if (accepted.status() != 202) {
            return RedeployResult.refused("steward-deployer answered " + accepted.status() + " for the " + what + " of "
                    + service + ": " + accepted.body());
        }

        final String jobId;
        try {
            jobId = Json.decode(accepted.body(), JsonObject.class).get("id").getAsString();
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
                final Reply response = web.get(URI.create(baseUrl + "/api/jobs/" + jobId));
                if (response.status() != 200) {
                    return RedeployResult.unverified("steward-deployer accepted the " + what + " of "
                            + service + " (job " + jobId + ") but answered " + response.status()
                            + " when asked how it went");
                }
                job = Json.decode(response.body(), JsonObject.class);
            } catch (final InterruptedIOException interrupted) {
                return RedeployResult.unverified("interrupted while waiting for steward-deployer's " + what + " of "
                        + service + " (job " + jobId + ") to finish");
            } catch (final IOException failure) {
                return RedeployResult.unverified("steward-deployer accepted the " + what + " of "
                        + service + " (job " + jobId + "), and whether it finished could not be"
                        + " read back: " + failure.getMessage());
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
     * The body of {@code POST /api/deploy} for exactly one service, built with Gson so a name cannot end the string.
     */
    static String deployBody(final String service) {
        final JsonArray services = new JsonArray();
        services.add(service);
        final JsonObject body = new JsonObject();
        body.add("services", services);
        return Json.encode(body);
    }

    /** One of the two requests, sent when {@link #submit} asks for it. */
    @FunctionalInterface
    private interface Submission {
        Reply send() throws IOException;
    }
}

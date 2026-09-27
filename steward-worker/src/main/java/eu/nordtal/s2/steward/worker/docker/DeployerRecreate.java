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
 * An update run deploys, which pulls first; a standby recreates, which must not. Everything else is the delegate's.
 */
public final class DeployerRecreate implements ContainerOps {

    private static final Gson GSON = new Gson();

    /** How often the job is asked about. */
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
     * {@code POST /api/deploy} naming this one service, then polls the job until it settles or patience runs out.
     *
     * The service is always named: an empty list means every service to compose.
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
     * A standby runs the image its live service runs, often built on the host, so a pull would downgrade it.
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
     * @param what "deploy" or "recreate", named in every message since only a deploy fetches an image
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
     * The body of {@code POST /api/deploy} for exactly one service, built with Gson so a name cannot end the string.
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
     * Refuses to send the token in clear outside this deployment.
     *
     * Plain {@code http} only to a dotless compose name or loopback, as steward-ui's {@code InternalClient} does.
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

    /** How "now" and "wait a bit" are told, so a test can drive the poll loop without sleeping. */
    interface Waiting {

        Instant now();

        /** Returns false when interrupted, which ends the wait rather than swallowing it. */
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

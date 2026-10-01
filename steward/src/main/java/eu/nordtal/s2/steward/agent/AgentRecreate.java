package eu.nordtal.s2.steward.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.steward.ops.ContainerOps;
import eu.nordtal.s2.steward.ops.ImageResult;
import eu.nordtal.s2.steward.ops.RedeployResult;
import eu.nordtal.s2.steward.ops.RuntimeResult;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * {@link ContainerOps#deploy} and {@link ContainerOps#recreate}, asked of {@code steward-agent} over HTTP.
 *
 * An update run deploys, which pulls first; a standby recreates, which must not. Everything else is the delegate's.
 */
public final class AgentRecreate implements ContainerOps {

    /** How often the job is asked about. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

    private final ContainerOps delegate;
    private final AgentClient agent;
    private final Duration patience;
    private final Waiting waiting;

    public AgentRecreate(
            final ContainerOps delegate, final AgentClient agent, final Duration patience, final Waiting waiting) {
        this.delegate = delegate;
        this.agent = agent;
        this.patience = patience;
        this.waiting = waiting;
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
        return submit(service, "deploy", () -> agent.post("/api/deploy", deployBody(service)));
    }

    /**
     * {@code POST /api/recreate/{service}}: the container again, from the image already here.
     *
     * A standby runs the image its live service runs, often built on the host, so a pull would downgrade it.
     */
    @Override
    public RedeployResult recreate(final String service) {
        return submit(service, "recreate", () -> agent.post("/api/recreate/" + service, ""));
    }

    /**
     * Sends one of the two requests and follows the job it hands back.
     *
     * @param what "deploy" or "recreate", named in every message since only a deploy fetches an image
     */
    private RedeployResult submit(final String service, final String what, final Supplier<String> send) {
        final Instant deadline = waiting.now().plus(patience);

        final String accepted;
        try {
            accepted = send.get();
        } catch (final AgentClient.Failure refused) {
            final String body = refused.body();
            return RedeployResult.refused("the " + what + " of " + service + " was not accepted: "
                    + refused.getMessage() + (body == null || body.isBlank() ? "" : ": " + body));
        }

        final String jobId;
        try {
            jobId = Json.decode(accepted, JsonObject.class).get("id").getAsString();
        } catch (final RuntimeException malformed) {
            return RedeployResult.unverified(AgentClient.NAME + " accepted the " + what + " of " + service
                    + " but its answer named no job id to follow: " + accepted);
        }
        return poll(service, what, jobId, deadline);
    }

    private RedeployResult poll(final String service, final String what, final String jobId, final Instant deadline) {
        final String job = " (job " + jobId + ")";
        while (true) {
            final JsonObject state;
            try {
                state = Json.decode(agent.get("/api/jobs/" + jobId), JsonObject.class);
            } catch (final AgentClient.Failure unread) {
                return RedeployResult.unverified(AgentClient.NAME + " accepted the " + what + " of " + service + job
                        + ", and how it went could not be read back: " + unread.getMessage());
            }

            final String settled = state.has("state") ? state.get("state").getAsString() : "";
            if ("DONE".equals(settled)) {
                return RedeployResult.triggered(AgentClient.NAME + " finished the " + what + " of " + service + job);
            }
            if ("FAILED".equals(settled)) {
                return RedeployResult.refused(
                        AgentClient.NAME + "'s " + what + " of " + service + " failed" + job + ": " + lastLine(state));
            }

            if (!waiting.now().isBefore(deadline)) {
                return RedeployResult.unverified(AgentClient.NAME + "'s " + what + " of " + service + job
                        + " had not finished after " + patience.toSeconds() + "s; it may still be running - check"
                        + " `docker logs nordtal-s2-steward-agent-1` or GET /api/jobs/" + jobId);
            }
            if (!waiting.sleep(POLL_INTERVAL)) {
                return RedeployResult.unverified(
                        "interrupted while waiting for " + AgentClient.NAME + "'s " + what + " of " + service + job);
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
}

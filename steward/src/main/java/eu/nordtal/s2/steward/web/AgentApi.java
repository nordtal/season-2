package eu.nordtal.s2.steward.web;

import eu.nordtal.s2.steward.agent.AgentClient;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ServiceUnavailableResponse;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks steward-agent to recreate one service's container, after checking the caller and the name.
 *
 * The name is checked because it goes into a URL path; the journal row is written here.
 */
public final class AgentApi {

    private static final Logger log = LoggerFactory.getLogger(AgentApi.class);

    /** steward-agent does not recreate itself: the request runs through it and would never come back. */
    private static final String SELF = "steward-agent";

    private final AgentClient agent;

    private final @Nullable Data data;

    private final Function<Context, DiscordAuth.Account> accounts;
    private final boolean configured;

    /** {@code configured} is whether a secret was given at all; a stack not set up yet is not a fault. */
    public AgentApi(
            final AgentClient agent,
            final @Nullable Data data,
            final Function<Context, DiscordAuth.Account> accounts,
            final boolean configured) {
        this.agent = agent;
        this.data = data;
        this.accounts = accounts;
        this.configured = configured;
    }

    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    /** Whether the button may be drawn, so a page can decide before anybody clicks. */
    public void state(final Context ctx) {
        if (!configured) {
            ctx.json(Map.of(
                    "available",
                    false,
                    "reason",
                    "agent.token is empty in steward.yml, so this interface cannot ask "
                            + "steward-agent for anything. The setup script writes that secret."));
            return;
        }
        ctx.json(Map.of("available", true, "reachable", agent.isReachable()));
    }

    /** Every service the live compose file defines, with the image each one runs. */
    public void services(final Context ctx) {
        require();
        ctx.contentType("application/json").result(agent.get("/api/services"));
    }

    /** {@code POST /api/agent/recreate/{service}}: accepted, and then it is a job. */
    public void recreate(final Context ctx) {
        require();
        final String service = serviceOf(ctx);
        final DiscordAuth.Account who = accounts.apply(ctx);

        // Written before the call, with the id, since audit_log.actor is varchar(32).
        data().audit()
                .record(
                        "RECREATE",
                        who.id(),
                        service,
                        null,
                        // "requested", not "recreated": the call can still be refused or stall.
                        "recreation from the current image requested by " + who.name() + " from the web interface");
        log.info("{} asked steward-agent to recreate {}", who.name(), service);

        final String answer = agent.post("/api/recreate/" + service, "");
        ctx.status(202).contentType("application/json").result(answer);
    }

    /** {@code GET /api/agent/jobs/{id}}: the job with its output so far. */
    public void job(final Context ctx) {
        require();
        ctx.contentType("application/json").result(agent.get("/api/jobs/" + jobId(ctx)));
    }

    /** {@code GET /api/agent/jobs}: the jobs this agent has run since it started. */
    public void jobs(final Context ctx) {
        require();
        ctx.contentType("application/json").result(agent.get("/api/jobs"));
    }

    private void require() {
        if (!configured) {
            throw new ServiceUnavailableResponse("agent.token is empty in steward.yml, so nothing can be asked of "
                    + "steward-agent. The setup script writes that secret.");
        }
    }

    /** The service name out of the path: lowercase letters, digits, hyphens and underscores only. */
    private static String serviceOf(final Context ctx) {
        final String service = ctx.pathParam("service").trim().toLowerCase(Locale.ROOT);
        if (service.isEmpty() || !service.matches("[a-z0-9][a-z0-9_-]{0,62}")) {
            throw new BadRequestResponse("\"" + ctx.pathParam("service") + "\" is not a compose service name");
        }
        if (service.equals(SELF)) {
            throw new BadRequestResponse("steward-agent does not recreate itself: it is the container this request "
                    + "is travelling through, so the answer would never come back. The setup "
                    + "script on the host renews that one.");
        }
        return service;
    }

    private static String jobId(final Context ctx) {
        final String id = ctx.pathParam("id").trim();
        if (id.isEmpty() || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BadRequestResponse("\"" + ctx.pathParam("id") + "\" is not a job id");
        }
        return id;
    }
}

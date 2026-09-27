package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
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
 * The one thing the interface asks steward-deployer for: make this service's container again.
 *
 * A recreate is a compose operation on one container, unlike an update's countable, cancellable
 * {@code update_request} row; only steward-deployer may create a container. This class checks who
 * is asking, refuses a name that is not a service name, writes the journal row, and hands the rest
 * over. The name shape is checked because it goes into a URL path on the way out.
 */
public final class DeployerApi {

    private static final Logger log = LoggerFactory.getLogger(DeployerApi.class);

    /**
     * The deployer refuses to recreate itself, and so does this end.
     *
     * It is the container the request runs through on the way to compose, so recreating it would
     * take the recreate down mid-flight. Renewing this one is the setup script's job on the host.
     */
    private static final String SELF = "steward-deployer";

    private final InternalClient deployer;

    /** Null only in a test that never calls a route on this class. */
    private final @Nullable Data data;

    private final Function<Context, DiscordAuth.Account> accounts;
    private final boolean configured;

    /**
     * @param configured whether a secret was given at all. A stack that has not been set up yet is
     *                   the ordinary case, and it has to read as one rather than as a fault
     */
    public DeployerApi(
            final InternalClient deployer,
            final @Nullable Data data,
            final Function<Context, DiscordAuth.Account> accounts,
            final boolean configured) {
        this.deployer = deployer;
        this.data = data;
        this.accounts = accounts;
        this.configured = configured;
    }

    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    /**
     * Whether the button may be drawn at all.
     *
     * Answered separately from doing it, so a page can decide what to show before anybody clicks.
     */
    public void state(final Context ctx) {
        if (!configured) {
            ctx.json(Map.of(
                    "available",
                    false,
                    "reason",
                    "deployer.token is empty in steward-ui.yml, so this interface cannot ask "
                            + "steward-deployer for anything. The setup script writes that secret."));
            return;
        }
        ctx.json(Map.of("available", true, "reachable", deployer.isReachable()));
    }

    /** Every service the live compose file defines, with the image each one runs. */
    public void services(final Context ctx) {
        require();
        ctx.contentType("application/json").result(deployer.get("/api/services"));
    }

    /** {@code POST /api/deployer/recreate/{service}} - accepted, and then it is a job. */
    public void recreate(final Context ctx) {
        require();
        final String service = serviceOf(ctx);
        final DiscordAuth.Account who = accounts.apply(ctx);

        // Written before the call, using the id (audit_log.actor is varchar(32)), not "name (id)".
        data().audit()
                .record(
                        "RECREATE",
                        who.id(),
                        service,
                        null,
                        // "requested", not "recreated": the call can still be refused, redirected or stall.
                        "recreation from the current image requested by " + who.name() + " from the web interface");
        log.info("{} asked steward-deployer to recreate {}", who.name(), service);

        final String answer = deployer.post("/api/recreate/" + service, "");
        ctx.status(202).contentType("application/json").result(answer);
    }

    /** {@code GET /api/deployer/jobs/{id}} - the job with its output so far. */
    public void job(final Context ctx) {
        require();
        ctx.contentType("application/json").result(deployer.get("/api/jobs/" + jobId(ctx)));
    }

    /** {@code GET /api/deployer/jobs} - the jobs this deployer has run since it started. */
    public void jobs(final Context ctx) {
        require();
        ctx.contentType("application/json").result(deployer.get("/api/jobs"));
    }

    private void require() {
        if (!configured) {
            throw new ServiceUnavailableResponse(
                    "deployer.token is empty in steward-ui.yml, so nothing can be asked of "
                            + "steward-deployer. The setup script writes that secret.");
        }
    }

    /** The service name out of the path, checked: lowercase letters, digits, hyphens and underscores only. */
    private static String serviceOf(final Context ctx) {
        final String service = ctx.pathParam("service").trim().toLowerCase(Locale.ROOT);
        if (service.isEmpty() || !service.matches("[a-z0-9][a-z0-9_-]{0,62}")) {
            throw new BadRequestResponse("\"" + ctx.pathParam("service") + "\" is not a compose service name");
        }
        if (service.equals(SELF)) {
            throw new BadRequestResponse("steward-deployer does not recreate itself: it is the container this request "
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

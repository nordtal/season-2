package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ServiceUnavailableResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The one thing the interface asks steward-deployer for: make this service's container again
 * (§10a.4, "Einzelne Dienste bei Image-Drift neu erzeugen lassen").
 *
 * <h2>Why this is not the same door as an update</h2>
 * An update is a row in {@code update_request}: countable, cancellable, counted down in front of
 * every player online, and carried out by steward-worker. A recreate is none of those things - it
 * is a compose operation on one container, and the only process in the stack allowed to create one
 * is steward-deployer. So the two live apart, and this class is thin on purpose: it checks who is
 * asking, refuses a name that is not a service name, writes the journal row, and hands the rest
 * over.
 *
 * <h2>What it refuses, and why each refusal is here rather than over there</h2>
 * The deployer refuses {@code steward-deployer} itself, and it would refuse an unknown service with
 * whatever compose prints. Both are repeated here because this end is where the message is read: a
 * person who clicked a button deserves a sentence, and {@code service "x" has no container to
 * recreate} in a compose stderr is not one. The shape check is not cosmetic either - the name goes
 * into a URL path on the way out, so anything with a slash in it would address something else
 * entirely.
 */
public final class DeployerApi {

    private static final Logger log = LoggerFactory.getLogger(DeployerApi.class);

    /**
     * The deployer refuses to recreate itself, and so does this end.
     *
     * <p>It is the container the request is running through on the way to compose, so the recreate
     * would take down the thing carrying out the recreate and nobody would learn how it ended.
     * Renewing this one is the setup script's job on the host (§9c).</p>
     */
    private static final String SELF = "steward-deployer";

    private final InternalClient deployer;
    private final Data data;
    private final Function<Context, DiscordAuth.Account> accounts;
    private final boolean configured;

    /**
     * @param configured whether a secret was given at all. A stack that has not been set up yet is
     *                   the ordinary case, and it has to read as one rather than as a fault
     */
    public DeployerApi(final @NotNull InternalClient deployer, final @NotNull Data data,
                       final @NotNull Function<Context, DiscordAuth.Account> accounts,
                       final boolean configured) {
        this.deployer = deployer;
        this.data = data;
        this.accounts = accounts;
        this.configured = configured;
    }

    /**
     * Whether the button may be drawn at all.
     *
     * <p>Answered separately from doing it, because a page has to decide what to show before
     * anybody clicks. A missing token is the ordinary case on a stack that has not been set up
     * yet, and it is a sentence rather than a disabled button with no explanation.</p>
     */
    public void state(final @NotNull Context ctx) {
        if (!configured) {
            ctx.json(Map.of("available", false, "reason",
                    "deployer.token is empty in steward-ui.yml, so this interface cannot ask "
                    + "steward-deployer for anything. The setup script writes that secret."));
            return;
        }
        ctx.json(Map.of("available", true, "reachable", deployer.isReachable()));
    }

    /** Every service the live compose file defines, with the image each one runs. */
    public void services(final @NotNull Context ctx) {
        require();
        ctx.contentType("application/json").result(deployer.get("/api/services"));
    }

    /** {@code POST /api/deployer/recreate/{service}} - accepted, and then it is a job. */
    public void recreate(final @NotNull Context ctx) {
        require();
        final String service = serviceOf(ctx);
        final DiscordAuth.Account who = accounts.apply(ctx);

        // Written BEFORE the call, not after. A recreate that takes the interface's own container
        // down - steward-ui is in this file like anything else - would otherwise be the one action
        // that never reached the journal, and it is exactly the one somebody will ask about.
        data.audit().record("RECREATE", who.name() + " (" + who.id() + ")", service, null,
                "container recreated from the current image, asked from the web interface");
        log.info("{} asked steward-deployer to recreate {}", who.name(), service);

        final String answer = deployer.post("/api/recreate/" + service, "");
        ctx.status(202).contentType("application/json").result(answer);
    }

    /** {@code GET /api/deployer/jobs/{id}} - the job with its output so far. */
    public void job(final @NotNull Context ctx) {
        require();
        ctx.contentType("application/json")
                .result(deployer.get("/api/jobs/" + jobId(ctx)));
    }

    /** {@code GET /api/deployer/jobs} - the jobs this deployer has run since it started. */
    public void jobs(final @NotNull Context ctx) {
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

    /**
     * The service name out of the path, checked.
     *
     * <p>Compose service names are lowercase letters, digits, hyphens and underscores; anything
     * else is either a typo or an attempt to address a different endpoint by putting a slash in
     * it.</p>
     */
    private static String serviceOf(final Context ctx) {
        final String service = ctx.pathParam("service").trim().toLowerCase(Locale.ROOT);
        if (service.isEmpty() || !service.matches("[a-z0-9][a-z0-9_-]{0,62}")) {
            throw new BadRequestResponse(
                    "\"" + ctx.pathParam("service") + "\" is not a compose service name");
        }
        if (service.equals(SELF)) {
            throw new BadRequestResponse(
                    "steward-deployer does not recreate itself: it is the container this request "
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

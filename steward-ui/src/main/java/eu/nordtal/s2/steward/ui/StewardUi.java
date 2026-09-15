package eu.nordtal.s2.steward.ui;

import com.google.gson.Gson;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessSource;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.auth.Credentials;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.Gate;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.exception.Base64UrlException;
import eu.nordtal.s2.steward.ui.auth.Sessions;
import eu.nordtal.s2.steward.ui.auth.WebAuthn;
import eu.nordtal.s2.steward.ui.discord.DiscordApi;
import eu.nordtal.s2.steward.ui.discord.DiscordDirectory;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.steward.ui.config.Configs;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinGson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Nordtal Steward - the web interface.
 *
 * <p><b>This process never touches Docker.</b> §3 of the concept draws that line and this module
 * keeps it: the container runs without the socket, and everything about a container - state, logs,
 * a console line, an image digest - is a call to {@code steward-worker}. Creating containers is
 * {@code steward-deployer}'s, and nobody else's. What is left here is a session, a proxy and the
 * database.</p>
 */
public final class StewardUi {

    private static final Logger log = LoggerFactory.getLogger(StewardUi.class);
    private static final Gson GSON = new Gson();

    /**
     * Where this request's session is parked once it has been read.
     *
     * <p>{@code account(ctx)} is asked at least twice on every call - once by the filter in front
     * of {@code /api/*} and again by whatever handler needs a name for the journal - and a session
     * now costs a round trip to PostgreSQL rather than a map lookup in the heap. Parked on the
     * request it is one query per request, and it also makes the two answers the same answer,
     * which they were not obliged to be when each went to the database on its own.</p>
     */
    private static final String PARKED = "steward.session";

    /** How often expired rows are swept out of {@code steward_session}. */
    private static final Duration SWEEP = Duration.ofHours(1);

    /**
     * How long one touch of the security key covers.
     *
     * <p>Till's decision, 2026-09-14: five minutes, one tap covering everything inside it. Here and
     * not in {@code steward-ui.yml}, because a deployment that could raise it to a day would be a
     * deployment where the second factor is a setting - and jcore preserves what is already in a
     * file, so the day somebody typed would outlive every later opinion about it.</p>
     */
    private static final Duration STEP_UP = Duration.ofMinutes(5);

    /**
     * The longest access anybody may be granted from here, in days.
     *
     * <p>A decade is nine seasons more than a season lasts, so it refuses nothing real. What it
     * does refuse is a slip of the keyboard reaching PostgreSQL, where the interval is built as
     * {@code hours => days * 24} and overflows an integer long before {@code Integer.MAX_VALUE}.
     * A ceiling here is a sentence the operator can read; the overflow there is a 500.</p>
     */
    private static final int MOST_DAYS = 3650;

    /** The default: serve. Named so that spelling it out is not an error. */
    private static final String SERVE = "serve";

    /**
     * The way back, and the only one there is.
     *
     * <p>It is on the host and not in the interface because there is no privilege inside Steward
     * that could be allowed to clear somebody's second factor - a page that could do it would make
     * the factor worth exactly as much as the cookie in front of it. What stands in front of this
     * is a shell on the machine the stack runs on.</p>
     */
    private static final String FORGET = "forget-factors";

    private final UiSpec config;
    private final DiscordAuth discord;
    private final InternalClient worker;

    /**
     * Signed-in browsers, in PostgreSQL.
     *
     * <p>There used to be a seam here - a {@code Function<Context, Account>} a test could stand in
     * front of - and it is gone: the integration tests drive the real {@code /auth/login} and
     * {@code /auth/callback} against a stand-in Discord, so the only thing the seam still bought
     * was a way to start this service with the authentication replaced. That is not a thing worth
     * keeping available.</p>
     *
     * <p>Null only in a test that is about the proxy and never signs anybody in.</p>
     */
    private final Sessions sessions;

    /**
     * The registered security keys, and the ceremonies that create them.
     *
     * <p>Two objects rather than one because they are two different things: {@link Credentials} is
     * rows and {@link WebAuthn} is the protocol, and the protocol half is the one that carries
     * Jackson. Null exactly when {@link #sessions} is - a test about the proxy has no database and
     * signs nobody in.</p>
     */
    private final Credentials credentials;
    private final WebAuthn webauthn;

    /** The database. Null only in tests that are about the proxy and never touch a row. */
    private final Data data;

    /** The other services' config files, mounted into this container. */

    /** What the guild's roles and channels are CALLED, so an id can be picked rather than typed. */
    private final DiscordApi guild;

    /** The admin commands that also exist in the game, over `command_request`. */
    private final CommandApi commands;

    /** The one service allowed to create a container, asked for exactly one thing (10a.4). */
    private final DeployerApi deployments;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * How often an open log follow says something into the browser's connection.
     *
     * <p><b>Nothing else ever notices a closed tab.</b> Javalin's {@code keepAlive()} does not send
     * anything - it holds the request open with an unfinished future - and a disconnected browser
     * is discovered only by a write that fails. A container that logs every three seconds therefore
     * hid the problem; one that is quiet for an hour, which is what a healthy server is, held a
     * connection to the worker and a thread here for that hour, per tab anybody had ever opened.
     * A comment line every ten seconds is also what keeps a reverse proxy from dropping an idle
     * stream, so it pays for itself twice.</p>
     */
    private static final Duration HEARTBEAT = Duration.ofSeconds(10);

    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "steward-ui-sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    private Javalin app;

    public StewardUi(final UiSpec config, final DiscordAuth discord, final InternalClient worker,
                     final InternalClient deployer, final Data data) {
        this.config = config;
        this.discord = discord;
        this.worker = worker;
        this.data = data;
        this.sessions = data == null
                ? null
                : new Sessions(data.dataSource(), Duration.ofDays(config.sessionDays()));
        this.credentials = data == null ? null : new Credentials(data.dataSource());
        this.webauthn = data == null ? null : new WebAuthn(config.webauthn().relyingPartyId(),
                config.publicUrl(), credentials);
        this.guild = new DiscordApi(
                new DiscordDirectory(config.discord(), DiscordAuth.DISCORD_API));
        this.commands = new CommandApi(data, ctx -> account(ctx).orElseThrow());
        this.deployments = new DeployerApi(deployer, data, ctx -> account(ctx).orElseThrow(),
                !config.deployer().token().isBlank());
    }

    /**
     * The container's entry point: read two files, build three things, serve.
     *
     * <p>It refuses to start on a configuration it cannot use, rather than starting and failing on
     * whoever clicks first.</p>
     */
    public static void main(final String[] args) {
        final Path directory = Path.of(System.getenv().getOrDefault(
                "NORDTAL_STEWARD_UI_CONFIG_DIR", "config"));
        // ONE PROGRAM WITH A SUBCOMMAND, not a second tool. `forget-factors` needs this jar's
        // database configuration, its DAOs and its journal, and a separate binary would be a
        // second copy of all three that nothing keeps in step.
        if (args.length > 0 && FORGET.equals(args[0])) {
            System.exit(forgetFactors(directory, args));
            return;
        }
        if (args.length > 0 && !SERVE.equals(args[0])) {
            System.err.println("`" + args[0] + "` is not a command. This program serves the web"
                    + " interface when given none, and knows `" + FORGET + " <discord-id>`.");
            System.exit(2);
            return;
        }
        final UiSpec config;
        final Data data;
        try {
            config = Configs.ui(directory, log).get();
            // Opened here rather than on the first request: a wrong URL or a missing password is a
            // startup failure that lands on whoever is deploying, not a blank page at three in the
            // morning that lands on whoever is on call.
            data = new Data(Configs.database(directory, log).get());
        } catch (ConfigException failure) {
            log.error("The configuration in {} could not be read, so nothing is being served.",
                    directory.toAbsolutePath(), failure);
            System.exit(1);
            return;
        }

        final InternalClient worker = new InternalClient("steward-worker",
                config.worker().baseUrl(), config.worker().token(), Duration.ofSeconds(10));
        if (config.worker().token().isBlank()) {
            log.warn("worker.token is empty, so nothing about a container can be read. Every page"
                    + " that would show one says so instead of drawing an empty table.");
        }
        // A SECOND CLIENT AND A SECOND SECRET, deliberately. The deployer may create containers and
        // the worker may not; one token for both would make that boundary a comment.
        final InternalClient deployer = new InternalClient("steward-deployer",
                config.deployer().baseUrl(), config.deployer().token(), Duration.ofSeconds(10));
        if (config.deployer().token().isBlank()) {
            log.warn("deployer.token is empty, so no container can be recreated from here. The"
                    + " button is not drawn and the page says why.");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(data::close, "steward-ui-shutdown"));
        new StewardUi(config, new DiscordAuth(config.discord(), config.publicUrl()), worker,
                deployer, data).start(config.port());
    }

    /**
     * {@code forget-factors <discord-id>} - the way back in after a lost authenticator.
     *
     * <p>Removes the account's keys <b>and</b> its sessions, which is one thing and not two:
     * clearing the keys of an account whose browser is still signed in would leave that browser
     * inside with no key at all. The next sign-in lands on the setup page, which is where somebody
     * who has lost their key needs to be.</p>
     *
     * <h2>It writes a journal row, and that is not decoration</h2>
     * Lifting a second factor is the one operation in this stack that happens nowhere a person can
     * see it - no Discord message, no page, no audit trail of its own. The actor is the literal
     * {@code host}, because that is exactly what is known: somebody had a shell on this machine.
     * Naming a person there would be inventing one.
     *
     * <p>It is deliberately quiet about accounts that do not exist: a Discord id nobody has
     * registered a key for is answered with "nothing to forget" and exit code 0, because the state
     * afterwards is the state that was asked for. A non-zero exit there would send somebody
     * hunting for a fault when the account is simply already open.</p>
     *
     * @return the process exit status
     */
    private static int forgetFactors(final Path directory, final String[] args) {
        if (args.length != 2 || args[1].isBlank()) {
            System.err.println("Usage: " + FORGET + " <discord-id>");
            System.err.println("Clears the security keys and the sessions of one account, so that"
                    + " its next sign-in starts at the setup page.");
            return 2;
        }
        final String discordId = args[1].trim();
        // The same check Credentials.accountOf performs on a user handle, for the same reason: a
        // Discord id is decimal digits, and anything else is a typo that would otherwise run as a
        // DELETE matching nothing and report success.
        if (!discordId.chars().allMatch(Character::isDigit)) {
            System.err.println("`" + discordId + "` is not a Discord id - those are digits only."
                    + " Take it from the journal or from the account list.");
            return 2;
        }
        try (Data data = new Data(Configs.database(directory, log).get())) {
            final Credentials credentials = new Credentials(data.dataSource());
            final Sessions sessions = new Sessions(data.dataSource(), Duration.ofDays(1));
            final int keys = credentials.forget(discordId);
            final int signedOut = sessions.endAllOf(discordId);
            if (keys == 0 && signedOut == 0) {
                System.out.println("Nothing to forget: " + discordId + " has no security key and"
                        + " no session. Its next sign-in already starts at the setup page.");
                return 0;
            }
            // Written AFTER the deletes, so a row can never claim something that did not happen.
            // The other order would record an intention.
            data.audit().record("FORGET_FACTORS", "host", discordId, null,
                    keys + " security key(s) and " + signedOut + " session(s) of " + discordId
                            + " were cleared from the host");
            System.out.println("Cleared " + keys + " security key(s) and " + signedOut
                    + " session(s) of " + discordId + ".");
            System.out.println("Its next sign-in will ask for Discord and then register a new key.");
            return 0;
        } catch (ConfigException failure) {
            log.error("The database configuration in {} could not be read, so nothing was cleared.",
                    directory.toAbsolutePath(), failure);
            return 1;
        }
    }

    public Javalin start(final int port) {
        app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinGson(new Gson(), true));
            cfg.startup.showJavalinBanner = false;

            // The built frontend, out of the jar. `/web` is where Gradle's vite build lands.
            cfg.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/web";
                staticFiles.location = Location.CLASSPATH;
                // THE BUNDLE CARRIES ITS DECISION TOO, AND IT HAS TO BE SAID HERE.
                //
                // `guard` hangs on `beforeMatched`, which runs in front of static files as well -
                // and a static file is not registered through `cfg.routes`, so it cannot pick up a
                // Gate the way a route does. Left empty, `gateOf` finds zero decisions and refuses
                // `/` with a 500: no sign-in page, no Discord button, a white screen apologising
                // for itself. That is what happened on 2026-09-15, the first day this service ran
                // an image built after the gatekeeper landed.
                //
                // ANYONE is not a concession, it is the same answer `/auth/login` and `/api/me`
                // give: whoever cannot sign in yet has to be able to read the page that offers it.
                // The bundle holds no data - every byte it shows arrives later, through /api, and
                // every one of those doors is shut.
                staticFiles.roles = Set.of(Gate.ANYONE);
            });
            // A client-side router owns every path that is not an API call or a file, so an
            // unknown path is index.html rather than a 404 - which is what makes a reload of
            // /operations/runs/27 land on the page it names instead of on nothing.
            //
            // It carries no Gate and cannot: `SinglePageHandler` has no roles field (checked
            // against javalin 7.2.3). `gateOf` answers for it, and for the static files, by the
            // one rule below - not by guessing.
            cfg.spaRoot.addFile("/", "/web/index.html", Location.CLASSPATH);

            cfg.routes.get("/api/health", ctx -> ctx.json(Map.of(
                    "status", "ok",
                    "worker", worker.isReachable())), Gate.ANYONE);

            // WHO MAY SIGN IN IS ANSWERED BEFORE ANYTHING ELSE IS SERVED. The sign-in page itself
            // needs to be readable without a session, and so does the static bundle - everything
            // under /api that is not health or this does not.
            cfg.routes.get("/api/me", this::whoAmI, Gate.ANYONE);

            // THE ONE DOOR, AND IT READS THE DECISION OFF THE ROUTE IT IS ABOUT TO RUN.
            //
            // `beforeMatched` rather than `before("/api/*")`: the old filter guarded a path
            // prefix, which meant every route outside it - signing out, both ceremonies - repeated
            // the same two checks by hand, and a new route outside /api would have repeated
            // nothing at all. This one runs in front of EVERY matched endpoint and asks the
            // endpoint itself what it requires, so a route cannot be outside the arrangement; it
            // can only be inside it with a value somebody chose. See Gate.
            cfg.routes.beforeMatched(this::guard);

            cfg.routes.get("/auth/login", this::login, Gate.ANYONE);
            cfg.routes.get("/auth/callback", this::callback, Gate.ANYONE);
            cfg.routes.post("/auth/logout", ctx -> {
                // The CSRF token is `guard`'s, on every write, including this one - it used to be
                // repeated here because the old filter only covered /api/*. It matters as much as
                // it ever did: the cookie is SameSite=Lax, which allows exactly the kind of
                // top-level POST a form on another site performs, so without it a stranger's page
                // could sign an admin out in the middle of a deployment they are watching.
                sessions.end(ctx.cookie(Sessions.COOKIE));
                ctx.removeCookie(Sessions.COOKIE, "/");
                ctx.status(204);
            }, Gate.SIGNED_IN);

            // --- the second factor (§10a) ------------------------------------------------------
            //
            // SIGNED_IN AND NOT KEY_HELD, which is the whole exception list the plan asks for: a
            // door cannot ask for the key it exists to hand out, and a person part-way through the
            // key ceremony must still be able to sign out. Everything else in this service is
            // above that line.
            cfg.routes.post("/auth/webauthn/register/start", this::beginRegistration, Gate.SIGNED_IN);
            cfg.routes.post("/auth/webauthn/register/finish", this::finishRegistration, Gate.SIGNED_IN);
            cfg.routes.post("/auth/webauthn/authenticate/start", this::beginAssertion, Gate.SIGNED_IN);
            cfg.routes.post("/auth/webauthn/authenticate/finish", this::finishAssertion, Gate.SIGNED_IN);

            // --- the keys themselves (package F) ----------------------------------------------
            //
            // KEY_FRESH, which is the plan's own §8: managing the keys is one of the dangerous
            // things, and it is the most dangerous of them - a stolen session that could add a key
            // would be a stolen session that has made itself permanent. Adding one goes through
            // the ceremony above and is checked in the handler; these two are the rest of it.
            //
            // The list they act on is in /api/me, not in a route of its own: it is part of the
            // answer to "who am I", the shell already has it, and a second endpoint would be a
            // second thing to keep in step.
            cfg.routes.put("/api/keys/{id}", this::renameKey, Gate.KEY_FRESH);
            cfg.routes.delete("/api/keys/{id}", this::removeKey, Gate.KEY_FRESH);

            // --- everything about a container comes from steward-worker -----------------------
            cfg.routes.get("/api/services", ctx -> passThrough(ctx, "/api/services"), Gate.KEY_HELD);
            cfg.routes.get("/api/services/{name}", ctx ->
                    passThrough(ctx, "/api/services/" + ctx.pathParam("name")), Gate.KEY_HELD);
            cfg.routes.get("/api/services/{name}/logs/search", ctx -> passThrough(ctx,
                    "/api/services/" + ctx.pathParam("name") + "/logs/search"
                            + forwardedQuery(ctx.queryString())), Gate.KEY_HELD);
            cfg.routes.post("/api/services/{name}/console", ctx -> {
                final String answer = worker.post(
                        "/api/services/" + ctx.pathParam("name") + "/console", ctx.body());
                ctx.status(202).contentType("application/json").result(answer);
            }, Gate.KEY_FRESH);
            // --- and creating one comes from steward-deployer, which is a different service ---
            //
            // Not the same door as an update: an update is a countable, cancellable row that
            // steward-worker carries out with a countdown in front of every player online. This is
            // one container, made again from the image that is already on the host, and the only
            // process in the stack allowed to do it is the deployer (8a).
            cfg.routes.get("/api/deployer", deployments::state, Gate.KEY_HELD);
            cfg.routes.get("/api/deployer/services", deployments::services, Gate.KEY_HELD);
            cfg.routes.post("/api/deployer/recreate/{service}", deployments::recreate, Gate.KEY_FRESH);
            cfg.routes.get("/api/deployer/jobs", deployments::jobs, Gate.KEY_HELD);
            cfg.routes.get("/api/deployer/jobs/{id}", deployments::job, Gate.KEY_HELD);

            cfg.routes.get("/api/host", ctx -> passThrough(ctx, "/api/host"), Gate.KEY_HELD);
            // What "tonight" means on the host, rather than in whatever zone the browser is in.
            cfg.routes.get("/api/schedule", ctx -> passThrough(ctx, "/api/schedule"), Gate.KEY_HELD);
            cfg.routes.get("/api/backups", ctx -> passThrough(ctx, "/api/backups"), Gate.KEY_HELD);

            // The log follow, proxied line by line. A redirect would be simpler and would hand the
            // browser the worker's address and its token, which is the one thing this whole split
            // exists to avoid.
            cfg.routes.sse("/api/services/{name}/logs", client -> {
                if (account(client.ctx()).isEmpty()) {
                    client.close();
                    return;
                }
                final String name = client.ctx().pathParam("name");
                final String query = client.ctx().queryString();
                // TWO SOCKETS, AND ONLY ONE OF THEM NOTICES A CLOSED TAB. The browser's end going
                // away tells this process nothing about the worker's end, which stays blocked in
                // readLine() until the container it is following stops - so every reload left a
                // connection and a thread behind, and a person clicking through four services left
                // four. Registered BEFORE the follow is submitted, because the other order has a
                // gap in it: a browser that leaves in that gap would find nothing to cancel.
                final Upstream upstream = new Upstream();
                final ScheduledFuture<?> heartbeat = heartbeats.scheduleWithFixedDelay(
                        () -> client.sendComment("open"), HEARTBEAT.toSeconds(),
                        HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
                client.onClose(() -> {
                    heartbeat.cancel(false);
                    upstream.close();
                });
                client.keepAlive();
                streams.submit(() -> follow(client, upstream, name, query));
            }, Gate.KEY_HELD);

            // --- what is in the database, which is where the curves and the runs live ---------
            //
            // §10c: the interface reads its graphs OUT OF POSTGRES, never out of Docker. Docker
            // answers "now" and has no history behind it, so a page drawing its curves from the
            // daemon would draw one point and call it a line.
            cfg.routes.get("/api/metrics", ctx -> {
                final String subject = ctx.queryParamAsClass("subject", String.class)
                        .getOrDefault("host");
                final String metric = ctx.queryParam("metric");
                if (metric == null || metric.isBlank()) {
                    throw new BadRequestResponse("metric is which number to draw");
                }
                final int hours = ctx.queryParamAsClass("hours", Integer.class).getOrDefault(6);
                final Instant now = Instant.now();
                ctx.json(Map.of(
                        "subject", subject,
                        "metric", metric,
                        "from", now.minus(Duration.ofHours(hours)).toString(),
                        "points", data.metrics().range(subject, metric,
                                now.minus(Duration.ofHours(hours)), now)));
            }, Gate.KEY_HELD);

            cfg.routes.get("/api/updates", ctx -> {
                // The same helper every other list on this class uses. It used to clamp only the
                // top end, which left `?limit=0` and `?limit=-5` to be reinterpreted three layers
                // down in the directory - so one endpoint had its floor somewhere else than all
                // the others, and nothing said where.
                ctx.json(data.updates().recent(limit(ctx, 20, 200)).stream()
                        .map(StewardUi::describe).toList());
            }, Gate.KEY_HELD);

            cfg.routes.get("/api/updates/{id}", ctx -> {
                final long id = Long.parseLong(ctx.pathParam("id"));
                ctx.json(data.updates().find(id)
                        .map(StewardUi::describe)
                        .orElseThrow(() -> new NotFoundResponse("no request " + id)));
            }, Gate.KEY_HELD);

            // Asking for an update, a backup or a restart is writing a row - the same row /update
            // in Discord writes. Nothing here talks to a container.
            cfg.routes.post("/api/updates", ctx -> {
                final Ask ask = ctx.bodyAsClass(Ask.class);
                if (ask == null || ask.kind == null) {
                    throw new BadRequestResponse("kind is UPDATE, BACKUP or RESTART");
                }
                final UpdateKind kind;
                try {
                    kind = UpdateKind.valueOf(ask.kind.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new BadRequestResponse(ask.kind + " is not a kind of run");
                }
                final DiscordAuth.Account who = account(ctx).orElseThrow();
                // `not_before` is what §10a.4 calls scheduling: the worker refuses to claim the row
                // until then, so "tonight at 04:00" is a value in a column rather than somebody
                // staying awake.
                final Duration delay = ask.delaySeconds == null || ask.delaySeconds <= 0
                        ? Duration.ZERO : Duration.ofSeconds(ask.delaySeconds);
                final var written = data.updates().submit(kind, UpdateSource.CONSOLE,
                        who.name() + " (" + who.id() + ")", delay);
                log.info("{} asked for {} as request {}", who.name(), kind, written.id());
                ctx.status(202).json(describe(written));
            }, Gate.KEY_FRESH);

            // --- the thresholds the start page judges by ---------------------------------------
            //
            // Read from this service's own config rather than kept in the browser, because the
            // traffic light has to be able to fire into Discord as well, and a number in
            // somebody's localStorage cannot be read by anything that is not that browser.
            // --- the five admin commands that stayed in the game (§10b) ------------------------
            //
            // A row in `command_request`, not a connection to a server: the interface holds none.
            // `source = WEB` rather than CONSOLE, because V11 pins a CONSOLE row to having no
            // identity at all - every admin command from here would otherwise be anonymous, which
            // is the question the journal exists to answer. V18 adds the value and the CHECK.
            cfg.routes.get("/api/commands", commands::list, Gate.KEY_HELD);
            cfg.routes.post("/api/commands", commands::ask, Gate.KEY_FRESH);
            cfg.routes.get("/api/commands/{id}", commands::outcome, Gate.KEY_HELD);

            // --- the configuration of every service in the stack ------------------------------
            //
            // Till's decision, 2026-09-13: every config in the stack is editable from here, with
            // labels a person can read. What makes that possible without steward-ui depending on
            // six other modules - one of which would drag a Paper API onto a web server's
            // classpath - is that jcore writes its comments into the YAML. The file is the model.
            cfg.routes.get("/api/config", ctx -> forwardConfig(ctx, "/api/config", null),
                    Gate.KEY_HELD);
            cfg.routes.get("/api/config/<file>",
                    ctx -> forwardConfig(ctx, configPath(ctx), null), Gate.KEY_HELD);
            cfg.routes.put("/api/config/<file>",
                    ctx -> forwardConfig(ctx, configPath(ctx), ctx.body()), Gate.KEY_FRESH);

            // The names behind the ids, so the editor above can offer a list instead of a field.
            // Never a failure: an unreachable Discord is `available: false` and a typed id.
            cfg.routes.get("/api/discord/roles", guild::roles, Gate.KEY_HELD);
            cfg.routes.get("/api/discord/channels", guild::channels, Gate.KEY_HELD);

            cfg.routes.get("/api/settings", ctx -> ctx.json(Map.of(
                    "disk", config.alerts().diskPercent(),
                    "memory", config.alerts().memoryPercent(),
                    "backupAgeHours", config.alerts().backupAgeHours())), Gate.KEY_HELD);

            // --- who is in the guild, what they paid, what they may ----------------------------
            cfg.routes.get("/api/people", ctx -> ctx.json(
                    data.roster().people(limit(ctx, 500, 2000))), Gate.KEY_HELD);

            cfg.routes.get("/api/people/{id}/grants", ctx -> ctx.json(
                    data.roster().grantsOf(ctx.pathParam("id"))), Gate.KEY_HELD);

            cfg.routes.get("/api/payments", ctx -> ctx.json(
                    data.roster().payments(limit(ctx, 200, 1000))), Gate.KEY_HELD);

            // What `access settle` may be pointed at. A list and not a limit: see
            // RosterDirectory#openPayments for why, and CommandApi for what draws from it.
            cfg.routes.get("/api/payments/open", ctx -> ctx.json(
                    data.roster().openPayments()), Gate.KEY_HELD);

            cfg.routes.get("/api/journal", ctx -> ctx.json(data.audit().search(
                    ctx.queryParam("action"), ctx.queryParam("subject"),
                    limit(ctx, 200, 1000))), Gate.KEY_HELD);

            // Granting and revoking - the only writing here that is not an update_request row.
            //
            // Till decided on 2026-09-13 that the interface may do both, so there are now two doors
            // into one room: this and /access in Discord. The price of the second door is paid in
            // the journal, one row per click, naming the admin - because "who let this person in"
            // has to stay answerable when the answer is no longer "the only person who could".
            cfg.routes.post("/api/access/grant", ctx -> {
                final Grant ask = ctx.bodyAsClass(Grant.class);
                if (ask == null || ask.discordId == null || ask.discordId.isBlank()) {
                    throw new BadRequestResponse("discordId is whose access this is");
                }
                if (ask.days == null || ask.days <= 0 || ask.days > MOST_DAYS) {
                    // The ceiling is not decoration. `make_interval(hours => :days * 24)` in
                    // AccessDao overflows a PostgreSQL integer well before Integer.MAX_VALUE, and
                    // what comes back is a 500 blaming this program for a number somebody typed.
                    throw new BadRequestResponse("days is between 1 and " + MOST_DAYS);
                }
                final DiscordAuth.Account who = account(ctx).orElseThrow();
                // ensureUser first: a grant against a Discord id the bot has never seen would fail
                // on the foreign key, and "this person has not spoken to the bot yet" is a worse
                // error message than simply making the row.
                data.access().ensureUser(ask.discordId);
                final var granted = data.access().grantAccess(
                        ask.discordId, ask.days, AccessSource.ADMIN, null);
                // The id. `audit_log.actor` is varchar(32) and holds a Discord id - the composed
                // "name (id)" overflowed it for any display name of 11 characters or more, and
                // this insert then took the grant's own answer down with a 500. The name is in
                // the detail, which is `text`.
                data.audit().record("GRANT_ACCESS", who.id(), ask.discordId, null,
                        ask.days + " days granted by " + who.name() + " from the web interface,"
                                + " until " + granted.validUntil());
                log.info("{} granted {} {} days of access", who.name(), ask.discordId, ask.days);
                ctx.status(201).json(granted);
            }, Gate.KEY_FRESH);

            cfg.routes.post("/api/access/revoke", ctx -> {
                final Grant ask = ctx.bodyAsClass(Grant.class);
                if (ask == null || ask.discordId == null || ask.discordId.isBlank()) {
                    throw new BadRequestResponse("discordId is whose access this is");
                }
                final DiscordAuth.Account who = account(ctx).orElseThrow();
                final int revoked = data.access().revokeAccess(ask.discordId);
                data.audit().record("REVOKE_ACCESS", who.id(), ask.discordId, null,
                        revoked + " grant(s) revoked by " + who.name()
                                + " from the web interface");
                log.info("{} revoked {} grants of {}", who.name(), revoked, ask.discordId);
                ctx.json(Map.of("revoked", revoked));
            }, Gate.KEY_FRESH);

            // --- the season ------------------------------------------------------------------
            //
            // PhaseDirectory writes its own audit_log row inside the statement that performs the
            // change, so nothing is recorded twice here. That is also why the actor has to be
            // passed in rather than recorded afterwards - and why it is the Discord id: the
            // parameter is documented as one, and the SQL casts it to varchar(32), which
            // truncates silently.
            cfg.routes.post("/api/season/phase", ctx -> {
                final SeasonChange ask = ctx.bodyAsClass(SeasonChange.class);
                if (ask == null || ask.phase == null) {
                    throw new BadRequestResponse("phase is which phase to switch to");
                }
                final SeasonPhase phase;
                try {
                    phase = SeasonPhase.valueOf(ask.phase.trim().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new BadRequestResponse(ask.phase + " is not a phase");
                }
                final DiscordAuth.Account who = account(ctx).orElseThrow();
                final var change = data.phase().switchPhase(phase, who.id(),
                        ask.reason == null ? "" : ask.reason);
                ctx.json(change);
            }, Gate.KEY_FRESH);

            cfg.routes.post("/api/season/date", ctx -> {
                final SeasonChange ask = ctx.bodyAsClass(SeasonChange.class);
                if (ask == null || ask.at == null || ask.at.isBlank()) {
                    throw new BadRequestResponse("at is the instant, as ISO-8601");
                }
                final Instant at;
                try {
                    at = Instant.parse(ask.at.trim());
                } catch (java.time.format.DateTimeParseException e) {
                    throw new BadRequestResponse(ask.at + " is not an ISO-8601 instant");
                }
                final DiscordAuth.Account who = account(ctx).orElseThrow();
                // PhaseDirectory documents this parameter as the admin's Discord id, and its SQL
                // casts it to varchar(32) - an explicit cast, which PostgreSQL TRUNCATES rather
                // than refusing. The composed "name (id)" therefore did not fail here; it wrote a
                // half-name into the journal and said nothing. The id is what the column means.
                final String actor = who.id();
                // NOT A TERNARY. An `equals` and an `else` made "smpstart", "launchh" and a
                // missing field all mean "launch", so a typo overwrote the wrong one of the two
                // dates the whole season hangs off - and answered 200 while doing it.
                final var change = switch (ask.which == null ? "" : ask.which.trim()) {
                    case "smpStart" -> data.phase().setSmpStart(at, actor);
                    case "launch" -> data.phase().setLaunch(at, actor);
                    default -> throw new BadRequestResponse("which is smpStart or launch");
                };
                ctx.json(change);
            }, Gate.KEY_FRESH);

            cfg.routes.get("/api/season", ctx -> {
                final Map<String, Object> season = new LinkedHashMap<>();
                season.put("phase", data.phase().currentPhase().name());
                data.phase().launch().ifPresent(at -> season.put("launch", at.toString()));
                data.phase().smpStart().ifPresent(at -> season.put("smpStart", at.toString()));
                ctx.json(season);
            }, Gate.KEY_HELD);

            // --- a call to an endpoint that is not there, and these two go LAST ---------------
            //
            // Javalin takes the first route that matches, so these greedy ones have to come after
            // every real one; registered earlier they would swallow the lot.
            //
            // WHY THEY EXIST AT ALL. The single-page fallback claims every unmatched GET, /api
            // included, and it carries no Gate - so a mistyped or retired endpoint came out of
            // `gateOf` as a 500 reading "this route was built without a decision", which is a
            // sentence about a route nobody ever wrote. It put an ERROR in the log Till reads and
            // sent the next person after a fault that was not there. A 404 is the true answer.
            //
            // ANYONE is right for the same reason 404 is: this hands out no data and reaches no
            // database. It says one thing, that there is nothing at this address, and somebody
            // who is not signed in may hear that as readily as anybody else.
            cfg.routes.get("/api/<path>", ctx -> {
                throw new NotFoundResponse("no such endpoint: " + ctx.path());
            }, Gate.ANYONE);
            cfg.routes.get("/auth/<path>", ctx -> {
                throw new NotFoundResponse("no such endpoint: " + ctx.path());
            }, Gate.ANYONE);

            cfg.routes.exception(SecondFactorMissing.class, (missing, ctx) ->
                    ctx.status(403).json(Map.of(
                            "error", missing.getMessage(),
                            "code", "SECOND_FACTOR_MISSING")));

            // The refusal the interface RECOVERS FROM rather than reports: it opens the key
            // dialog, runs the ceremony and sends the same request again. `retryable` is not
            // decoration - it is the difference between "hold your key and this will go through"
            // and "hold your key and then find this page again yourself".
            cfg.routes.exception(SecondFactorRequired.class, (required, ctx) ->
                    ctx.status(403).json(Map.of(
                            "error", required.getMessage(),
                            "code", "SECOND_FACTOR_REQUIRED",
                            "retryable", true)));

            cfg.routes.exception(InternalClient.Failure.class, (failure, ctx) -> {
                // The interface has to say which half is down, BY NAME. "steward-worker is not
                // answering" and "steward-deployer is not answering" are two different evenings -
                // the first is a stack nobody can see, the second is a stack nobody can change -
                // and an empty table says neither.
                log.warn("{} did not answer: {}", failure.where(), failure.getMessage());
                ctx.status(failure.status() == 0 ? 502 : failure.status())
                        .json(Map.of("error", failure.getMessage(),
                                "where", failure.where(),
                                "detail", failure.body() == null ? "" : failure.body()));
            });
        }).start(port);

        if (sessions != null) {
            // Once at startup and then hourly, on the scheduler that is already here. Not a
            // second thread pool for one DELETE an hour - and not a cron either, because a sweep
            // that only runs at 04:00 is a sweep that never runs on a service restarted daily.
            //
            // It is housekeeping and nothing depends on it: an expired session is refused by the
            // lookup itself, whether this has ever run or not. See Sessions#sweep.
            heartbeats.scheduleWithFixedDelay(this::sweepSessions, 0,
                    SWEEP.toSeconds(), TimeUnit.SECONDS);
        }
        discord.whatIsMissing().ifPresent(missing -> log.warn(
                "Nobody can sign in yet: {} is empty. Everything else is running.", missing));
        log.info("Nordtal Steward is on {} - public address {}", port, config.publicUrl());
        return app;
    }

    /**
     * The session cookie, spelled out - because every default anybody might rely on is wrong here.
     *
     * <h2>It has to survive the app being closed</h2>
     * A cookie written without a {@code Max-Age} is a <em>browser session</em> cookie: it lives as
     * long as the browser does. On a desktop that is a day. Added to an iPhone's home screen,
     * Steward is its own app with its own cookie jar, and iOS ends that app whenever it wants the
     * memory - so every second or third opening began at the Discord sign-in, which is four seconds
     * of redirects to read one number. The cookie carries the same lifetime the row does, and since
     * {@code V19} the row outlives a restart of this container too. Closing the app is not signing
     * out and neither is a deployment.
     *
     * <h2>SameSite=Lax, said out loud</h2>
     * The sign-in is a top-level redirect back from discord.com, which Lax allows, and every write
     * is a {@code POST} from this origin, which Lax also allows. {@code Strict} would break the
     * first of those - the callback would arrive without the cookie and so without the state, and
     * every sign-in would fail with "this sign-in did not start in this browser".
     *
     * <h2>Secure follows the browser's connection, which Caddy has to tell us about</h2>
     * Jetty's {@code setSecureRequestOnly} asked the socket, and the socket behind a reverse proxy
     * is plain HTTP on an internal network - so in this deployment the flag never appeared at all.
     * {@code X-Forwarded-Proto} is what Caddy's {@code reverse_proxy} sets by default and is the
     * only thing in the request that knows how the browser actually connected.
     *
     * <p><b>Trusting a header sounds worse than it is, in this one direction.</b> The header can
     * only ever turn the flag <em>on</em>, and a cookie marked {@code Secure} is a cookie the
     * browser is more careful with, never less. A forged one costs its forger their own session and
     * nothing else. Leaving it out gives exactly the old behaviour.
     *
     * <p>The alternative that was tried and rejected: deriving it from {@link UiSpec#publicUrl()},
     * which cannot be spoofed at all. It marks the cookie {@code Secure} on a deployment whose
     * public address is https - including for anybody reaching port 8080 directly over plain HTTP,
     * who then gets a cookie the browser will not send back and a sign-in that never finishes and
     * never says why. That is the test fixture, a developer on 127.0.0.1, and anybody debugging
     * this container from inside the network.
     */
    private void setSessionCookie(final Context ctx, final String id) {
        final Cookie cookie = new Cookie(Sessions.COOKIE, id, "/", (int) lifetime().toSeconds(),
                overTls(ctx), true, null, SameSite.LAX);
        ctx.cookie(cookie);
    }

    private static boolean overTls(final Context ctx) {
        // The header may carry a list when there is more than one proxy; the first entry is the
        // browser's own hop, which is the one this is about.
        final String forwarded = ctx.header("X-Forwarded-Proto");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim().equalsIgnoreCase("https");
        }
        return "https".equalsIgnoreCase(ctx.scheme());
    }

    private Duration lifetime() {
        return Duration.ofDays(config.sessionDays());
    }

    /**
     * The hourly sweep, with its failure swallowed on purpose.
     *
     * <p>{@code scheduleWithFixedDelay} cancels the schedule for good the first time the task
     * throws - silently, because nothing reads the future. A database that is briefly away during
     * a deployment would therefore stop the sweep for the lifetime of the process, and the only
     * symptom would be a table quietly growing. Catching here keeps the schedule alive and says
     * what happened once per hour rather than never.</p>
     */
    private void sweepSessions() {
        try {
            sessions.sweep();
        } catch (RuntimeException e) {
            log.warn("could not sweep expired sessions - trying again in {}: {}",
                    SWEEP, e.toString());
        }
    }

    // --- sign-in ---------------------------------------------------------------------------

    private void login(final Context ctx) {
        final Optional<String> missing = discord.whatIsMissing();
        if (missing.isPresent()) {
            ctx.status(503).json(Map.of("error", "sign-in is not configured: " + missing.get()));
            return;
        }
        // A one-time value tied to this browser's session. Discord hands it back, and a callback
        // that carries anything else is somebody else's callback.
        final String state = random();
        setSessionCookie(ctx, sessions.begin(state));
        ctx.redirect(discord.authorizeUrl(state).toString());
    }

    private void callback(final Context ctx) {
        final String started = ctx.cookie(Sessions.COOKIE);
        // Read once and cleared in the same statement, so a replayed callback matches nothing.
        final Optional<String> expected = sessions.consumeState(started);
        final String state = ctx.queryParam("state");
        if (expected.isEmpty() || !expected.get().equals(state)) {
            ctx.status(400).json(Map.of("error",
                    "this sign-in did not start in this browser - try again from the start"));
            return;
        }
        final String code = ctx.queryParam("code");
        if (code == null || code.isBlank()) {
            ctx.status(400).json(Map.of("error", "Discord sent no code"));
            return;
        }
        final DiscordAuth.Outcome outcome = discord.signIn(code);
        if (!outcome.ok()) {
            ctx.status(403).json(Map.of("error", outcome.refusal()));
            return;
        }
        // A NEW ROW WITH A NEW ID, and the one the sign-in started in is dropped. The row that
        // held the OAuth state is not promoted into a signed-in session, because its id was in
        // this browser before anybody proved who they were - which is session fixation, and the
        // cheapest place to make it impossible is here. See Sessions' class note.
        final DiscordAuth.Account who = outcome.account();
        final String id = sessions.signIn(who.id(), who.name(), who.roles());
        sessions.end(started);
        setSessionCookie(ctx, id);
        ctx.redirect("/");
    }

    // --- the second factor -------------------------------------------------------------------

    /**
     * The one door, in front of every matched endpoint.
     *
     * <h2>It reads the decision off the route rather than off the path</h2>
     * {@link Gate} is a {@code RouteRole} carried in the same line that registers the handler, and
     * this reads it back out. A route with no value is a programming error and is refused with a
     * 500 that names it - not with a pass, which is the failure this whole arrangement exists to
     * make impossible. {@code GateTest} catches it at build time; this catches the case where
     * somebody added a route after the test was last taught to look.
     *
     * <h2>The order is the point, and it is the order a person experiences</h2>
     * Who are you, then have you a key at all, then have you held it here, then have you held it
     * recently. Asking about the CSRF token before the session is what made a quietly expired
     * session report itself as a cross-site request - true of nothing that happened.
     */
    private void guard(final Context ctx) {
        final Gate gate = gateOf(ctx);
        if (gate == Gate.ANYONE) {
            return;
        }
        final Sessions.Session who = session(ctx)
                .orElseThrow(() -> new UnauthorizedResponse("sign in first"));
        if (isWrite(ctx)) {
            requireCsrfToken(ctx);
        }
        if (gate == Gate.SIGNED_IN) {
            return;
        }
        requireAKey(who);
        requireKeyHeld(who);
        if (gate == Gate.KEY_FRESH) {
            requireKeyRecently(who);
        }
    }

    /**
     * The one decision this route carries.
     *
     * <p>Exactly one. Two would be a route whose requirement depends on which the reader noticed
     * first, and none is a route nobody decided about - both are refused here rather than
     * interpreted, because every interpretation of "no decision" is somebody's guess.</p>
     */
    private static Gate gateOf(final Context ctx) {
        final List<Gate> decided = ctx.routeRoles().stream()
                .filter(Gate.class::isInstance)
                .map(Gate.class::cast)
                .toList();
        if (decided.size() == 1) {
            return decided.getFirst();
        }
        if (decided.isEmpty() && !isOurs(ctx.path())) {
            return Gate.ANYONE;
        }
        log.error("{} {} carries {} of Steward's own route decisions and has to carry exactly"
                + " one - refusing it rather than guessing.", ctx.method(), ctx.path(),
                decided.size());
        throw new UndecidedRoute();
    }

    /**
     * The two prefixes every endpoint this service registers lives under.
     *
     * <p>Everything else that reaches {@code guard} is the frontend bundle - a static file out of
     * the jar, or the single-page fallback for a path the client-side router owns. Neither can
     * carry a {@link Gate}: static files are registered through {@code cfg.staticFiles} (which
     * does have a roles field, and has one set) and the fallback through {@code cfg.spaRoot}
     * (which has none, checked against javalin 7.2.3). So the decision for them is made here,
     * once, in the open: <b>ANYONE</b>, the same answer {@code /auth/login} gives, because
     * whoever cannot sign in yet has to be able to read the page that offers it. The bundle holds
     * no data - every byte it shows arrives later through /api, and those doors are shut.</p>
     *
     * <p><b>This is not the guess {@link UndecidedRoute} refuses.</b> That refusal protects an
     * endpoint somebody registered and forgot to decide about, and it still does: a new route
     * under either prefix without a Gate is a 500 on its first call, loudly. What makes the rest
     * safe is that the premise is tested rather than believed -
     * {@code GateTest#everyEndpointLivesUnderOneOfTheTwoPrefixes} reads the real routing table
     * and fails the build the day somebody registers {@code /webhooks/bunq}. On that day this
     * method is what has to change, and the test says so by name.</p>
     */
    private static boolean isOurs(final String path) {
        return path.startsWith("/api/") || path.startsWith("/auth/") || path.equals("/api")
                || path.equals("/auth");
    }

    /**
     * A route registered without a {@link Gate}, refused at the door.
     *
     * <p>It is a 500 and not a 403, because nothing the person in front of the browser did is
     * wrong: this service was built with a route nobody decided about. The sentence says so.</p>
     */
    private static final class UndecidedRoute extends InternalServerErrorResponse {

        private UndecidedRoute() {
            super("This route was built without a decision about whether it needs a security key,"
                    + " so Steward is refusing it rather than guessing. That is a fault in this"
                    + " service and not in what you did.");
        }
    }

    /**
     * The door in front of everything this interface can do.
     *
     * <p>An account with no registered key reaches {@code /api/me} and nothing else. That is what
     * "the first sign-in forces the setup" means on this end: there is no page to fall back to and
     * no call that quietly still works, so the interface has exactly one thing it can draw.</p>
     *
     * <p><b>It answers 403 with a code rather than 401.</b> 401 is what the shell turns into the
     * sign-in page, and sending somebody back to Discord would be sending them round a loop they
     * have already completed - they ARE signed in; they are one ceremony short of being allowed
     * in. The code is machine-readable because the browser has to tell this apart from an ordinary
     * refusal without reading a sentence.</p>
     */
    private void requireAKey(final Sessions.Session who) {
        if (credentials == null || credentials.any(who.discordId())) {
            return;
        }
        throw new SecondFactorMissing();
    }

    /**
     * Package C: the key has to have been held in <em>this</em> session, not merely registered.
     *
     * <p>This is the sentence "Discord alone is not enough" in code. Before it, a browser that had
     * completed the Discord redirect saw everything this service can show, for thirty days; the key
     * was asked for once, when it was created, and never again. A stolen cookie was a month of
     * being able to stop a Minecraft server.</p>
     *
     * <p>It refuses with {@link SecondFactorRequired}, the same refusal the step-up uses, and that
     * is deliberate: the interface recovers from both the same way - run the ceremony, send the
     * request again. The full-page version of it is drawn from {@code /api/me}, which says
     * {@code verified: false} before anything else has been called.</p>
     */
    private void requireKeyHeld(final Sessions.Session who) {
        if (who.verified()) {
            return;
        }
        throw new SecondFactorRequired("This sign-in has not used its security key yet.");
    }

    /**
     * Package D: held within the last five minutes.
     *
     * <p>Till's number, 2026-09-14, and the reason for a number at all rather than "every time" is
     * on the screen of anybody who has ever configured four services in a row. One touch covers
     * everything done inside the window; the window does not slide, so it is five minutes from the
     * ceremony and not five minutes from the last click.</p>
     *
     * <p><b>Not sliding is the decision.</b> A window that renewed itself on every request would be
     * indistinguishable from no window at all for anybody working continuously - which is exactly
     * the person whose browser is most worth stealing.</p>
     */
    private void requireKeyRecently(final Sessions.Session who) {
        final Instant held = who.verifiedAt();
        if (held != null && held.isAfter(Instant.now().minus(STEP_UP))) {
            return;
        }
        throw new SecondFactorRequired("This is one of the things Steward asks for the key before"
                + " doing, and it has not been held in the last "
                + STEP_UP.toMinutes() + " minutes.");
    }

    /**
     * The refusal the interface recovers from: hold the key, then send the same request again.
     *
     * <p>Its own type and its own code, {@code SECOND_FACTOR_REQUIRED}, told apart from
     * {@link SecondFactorMissing} because the two need different pages: one account has no key at
     * all and has to register one, the other has a key and has to hold it. Answering both with the
     * same code would send somebody with a key to the setup page - and the setup page for an
     * account that already has a key is a page that refuses.</p>
     */
    private static final class SecondFactorRequired extends RuntimeException {

        private SecondFactorRequired(final String message) {
            super(message);
        }
    }

    /**
     * The one shape of 403 the interface recovers from rather than reports.
     *
     * <p>Its own type, so that it can be answered with this service's own body - {@code error} and
     * {@code code}, the shape everything else here uses - instead of Javalin's {@code title} /
     * {@code details} envelope. The browser has to tell this apart from an ordinary refusal without
     * reading English.</p>
     */
    private static final class SecondFactorMissing extends RuntimeException {

        private SecondFactorMissing() {
            super("This account has no security key yet, and Steward cannot be used without one."
                    + " Register a key and this request will work.");
        }
    }

    /**
     * Hands this browser a registration challenge.
     *
     * <h2>The first key is different from every one after it</h2>
     * The first is reachable with a Discord session alone, because there is nothing else to reach
     * it with - that is the bootstrap, and its window is exactly as long as the time between
     * deploying this and signing in once (see the plan's open risks). Every further key requires
     * that this session has already held one: adding a second authenticator is exactly as powerful
     * as having the first, so a stolen cookie must not be able to do it.
     */
    /** Who is asking, or the answer the filter would have given. */
    private Sessions.Session requireSession(final Context ctx) {
        return session(ctx).orElseThrow(() -> new UnauthorizedResponse("sign in first"));
    }

    private void beginRegistration(final Context ctx) {
        // The session and the CSRF token are `guard`'s now, in that order and for that reason -
        // asking about the token first answers a browser whose session has quietly expired with a
        // sentence about cross-site requests, which is true of nothing that happened. What is left
        // here is the one condition that is about the ACCOUNT rather than the route.
        final Sessions.Session who = requireSession(ctx);
        if (credentials.any(who.discordId()) && !who.verified()) {
            throw new ForbiddenResponse("This account already has a key, so adding another one"
                    + " needs the key you already have. Sign in again and use it first.");
        }
        final WebAuthn.Ceremony ceremony = webauthn.startRegistration(
                who.discordId(), who.displayName());
        sessions.startCeremony(who.id(), ceremony.parked());
        // The library's own JSON, straight through. Nothing on this side parses it and Gson never
        // sees it - see WebAuthn's class note on why that boundary is one class wide.
        ctx.contentType("application/json").result(ceremony.forBrowser());
    }

    /**
     * Takes the browser's answer, verifies it and writes the key down.
     *
     * <h2>Why the credential arrives as a string inside the body</h2>
     * The envelope is this service's own JSON and Gson parses it. The credential inside it is the
     * library's JSON and only the library may parse it - so it travels as a {@code String} field
     * rather than as a nested object. Handing the nested object to Gson and then re-serialising it
     * for Jackson is the same data through two mappers, and the fields the two disagree about are
     * precisely the optional ones that differ between brands of authenticator.
     */
    private void finishRegistration(final Context ctx) {
        final Sessions.Session who = requireSession(ctx);
        final Answer answer = ctx.bodyAsClass(Answer.class);
        if (answer == null || answer.credential == null || answer.credential.isBlank()) {
            throw new BadRequestResponse("no credential in that answer");
        }
        final String label = answer.label == null ? "" : answer.label.trim();
        if (label.isEmpty() || label.length() > 64) {
            throw new BadRequestResponse("a key needs a name of 1 to 64 characters, so that it can"
                    + " be told apart from the next one");
        }
        final String parked = sessions.consumeCeremony(who.id()).orElseThrow(
                () -> new BadRequestResponse("that registration was not started in this browser,"
                        + " or it was already finished, or it sat unanswered for ten minutes -"
                        + " start it again"));

        final WebAuthn.Registered key;
        try {
            key = webauthn.finishRegistration(parked, answer.credential, label, who.discordId());
        } catch (WebAuthn.Refused refused) {
            ctx.status(400).json(Map.of("error", refused.getMessage()));
            return;
        }
        // Registering a key IS holding it - the ceremony that just passed is the same proof an
        // authentication would be. Marking the session verified here is what lets somebody set a
        // key up and carry straight on, rather than being asked for it again one second later.
        sessions.markVerified(who.id());
        // The journal, in the same shape as GRANT_ACCESS beside it: the actor is the Discord id
        // and never the composed "name (id)", because `audit_log.actor` is varchar(32) and the
        // composed form silently truncated for any display name of eleven characters or more.
        data.audit().record("REGISTER_KEY", who.discordId(), who.discordId(), null,
                "registered the security key \"" + key.label() + "\"");
        ctx.json(Map.of("label", key.label(), "userVerified", key.userVerified(),
                "backedUp", key.backedUp()));
    }

    /** The body of {@code /auth/webauthn/register/finish}. See the method's note on the string. */
    private static final class Answer {
        private String label;
        private String credential;
    }

    /**
     * Hands this browser a challenge for a key it already has.
     *
     * <p>The account's own keys are the only ones allowed to answer - {@link WebAuthn} builds that
     * list out of the repository, so nothing here passes it and nothing here can widen it.</p>
     *
     * <p><b>An account with no key is refused here rather than offered an empty dialog.</b> That
     * is the setup page's job, and a browser that reaches this route without a key has got itself
     * into a state the shell does not draw - so the sentence points at the way out rather than at
     * the fault.</p>
     */
    private void beginAssertion(final Context ctx) {
        final Sessions.Session who = requireSession(ctx);
        final WebAuthn.Ceremony ceremony;
        try {
            ceremony = webauthn.startAssertion(who.discordId());
        } catch (WebAuthn.Refused refused) {
            throw new SecondFactorMissing();
        }
        sessions.startCeremony(who.id(), ceremony.parked());
        ctx.contentType("application/json").result(ceremony.forBrowser());
    }

    /**
     * Takes the answer, verifies it, and stamps this session as one that has held its key.
     *
     * <p>{@code verified_at = now()} is the whole product of this route. Everything that reads it -
     * the door in front of every page (package C) and the five-minute window in front of every
     * write (package D) - reads that one column, which is why it is a column and not a field on an
     * object in this process's heap: a restart of this container must not be a way to be asked
     * less.</p>
     *
     * <p>The journal gets a row, and it is worth the row: "this browser held a key at 21:14" is the
     * only record that a person and not a cookie was here, and it is the record somebody will want
     * on the evening they are asking whether a session was stolen.</p>
     */
    private void finishAssertion(final Context ctx) {
        final Sessions.Session who = requireSession(ctx);
        final Answer answer = ctx.bodyAsClass(Answer.class);
        if (answer == null || answer.credential == null || answer.credential.isBlank()) {
            throw new BadRequestResponse("no credential in that answer");
        }
        final String parked = sessions.consumeCeremony(who.id()).orElseThrow(
                () -> new BadRequestResponse("that sign-in was not started in this browser, or it"
                        + " was already finished, or it sat unanswered for ten minutes - start it"
                        + " again"));
        final WebAuthn.Held held;
        try {
            held = webauthn.finishAssertion(parked, answer.credential, who.discordId());
        } catch (WebAuthn.Refused refused) {
            ctx.status(400).json(Map.of("error", refused.getMessage()));
            return;
        }
        sessions.markVerified(who.id());
        data.audit().record("HELD_KEY", who.discordId(), who.discordId(), null,
                "held the security key \"" + held.label() + "\""
                        + (held.userVerified() ? " and unlocked it" : ""));
        ctx.json(Map.of("label", held.label(), "userVerified", held.userVerified()));
    }

    /**
     * The credential id out of the path, or a 400 that says what it should have been.
     *
     * <p>It is base64url in the URL because that is how it left this service in {@code /api/me},
     * and what goes out is what comes back. A value that is not base64url at all is somebody
     * typing, not the interface calling.</p>
     */
    private static ByteArray keyIdOf(final Context ctx) {
        try {
            return ByteArray.fromBase64Url(ctx.pathParam("id"));
        } catch (Base64UrlException malformed) {
            throw new BadRequestResponse("that is not the id of a key - the list in /api/me is"
                    + " where those come from");
        }
    }

    /** {@code PUT /api/keys/{id}} - what this key is called, so two can be told apart. */
    private void renameKey(final Context ctx) {
        final Sessions.Session who = requireSession(ctx);
        final Answer body = ctx.bodyAsClass(Answer.class);
        final String label = body == null || body.label == null ? "" : body.label.trim();
        if (label.isEmpty() || label.length() > 64) {
            throw new BadRequestResponse("a key needs a name of 1 to 64 characters, so that it can"
                    + " be told apart from the next one");
        }
        if (!credentials.rename(who.discordId(), keyIdOf(ctx), label)) {
            throw new NotFoundResponse("this account has no key of that id");
        }
        data.audit().record("RENAME_KEY", who.discordId(), who.discordId(), null,
                "renamed a security key to \"" + label + "\"");
        ctx.json(Map.of("label", label));
    }

    /**
     * {@code DELETE /api/keys/{id}} - one key, gone.
     *
     * <p>Removing the last one is allowed; see {@link Credentials#remove}. The journal row is
     * written with the label the key HAD, because afterwards there is nothing to read it off.</p>
     */
    private void removeKey(final Context ctx) {
        final Sessions.Session who = requireSession(ctx);
        final ByteArray id = keyIdOf(ctx);
        final String label = credentials.of(who.discordId()).stream()
                .filter(key -> new ByteArray(key.credentialId()).equals(id))
                .map(Credentials.Key::label)
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse("this account has no key of that id"));
        if (!credentials.remove(who.discordId(), id)) {
            throw new NotFoundResponse("this account has no key of that id");
        }
        final int left = credentials.of(who.discordId()).size();
        data.audit().record("REMOVE_KEY", who.discordId(), who.discordId(), null,
                "removed the security key \"" + label + "\" - " + left + " left on this account");
        ctx.json(Map.of("removed", label, "left", left));
    }

    /** The keys of one account, as {@code /api/me} lists them. */
    private List<Map<String, Object>> keysOf(final String discordId) {
        final List<Map<String, Object>> listed = new ArrayList<>();
        for (final Credentials.Key key : credentials.of(discordId)) {
            final Map<String, Object> one = new LinkedHashMap<>();
            // THE ID IS NOT A SECRET and never was: it is handed to any browser that starts a
            // sign-in, because it is what tells the authenticator which credential to use. It is
            // here so that a key can be renamed or removed by naming it, and the routes that do
            // that check the account as well - see CredentialDao#remove.
            one.put("id", new ByteArray(key.credentialId()).getBase64Url());
            one.put("label", key.label());
            one.put("registeredAt", key.createdAt().toString());
            if (key.lastUsedAt() != null) {
                one.put("lastUsedAt", key.lastUsedAt().toString());
            }
            if (key.transports() != null && !key.transports().isBlank()) {
                one.put("transports", List.of(key.transports().split(",")));
            }
            // Absent rather than false when the authenticator did not say. "Not backed up" and
            // "did not answer the question" are different, and only one of them is a reason to
            // suggest registering a second key.
            if (key.backedUp() != null) {
                one.put("backedUp", key.backedUp());
            }
            listed.add(one);
        }
        return listed;
    }

    private void whoAmI(final Context ctx) {
        final Optional<Sessions.Session> session = session(ctx);
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("signedIn", session.isPresent());
        session.ifPresent(who -> {
            answer.put("id", who.discordId());
            answer.put("name", who.displayName());
            // Written with the row, never minted here: the column is NOT NULL from the moment a
            // session exists, so there is no longer a state in which a browser is signed in and
            // has no token to send back. This route is the one place it may be read.
            answer.put("csrf", who.csrf());
            answer.put("signedInAt", who.createdAt().toString());
            answer.put("expiresAt", who.expiresAt().toString());
            // THE THREE ANSWERS THE SHELL DECIDES WHAT TO DRAW FROM. `keys` empty is the forced
            // setup page and nothing else; `verified` is whether a key has been held in THIS
            // session, which is what the step-up will read (package D) and what makes registering
            // a second key allowed.
            answer.put("keys", keysOf(who.discordId()));
            answer.put("verified", who.verified());
            if (who.verifiedAt() != null) {
                answer.put("verifiedAt", who.verifiedAt().toString());
            }
            answer.put("relyingPartyId", webauthn.relyingPartyId());
        });
        discord.whatIsMissing().ifPresent(missing -> answer.put("signInUnavailable", missing));
        // Said out loud rather than in a footnote, and since packages C and D it is the whole of
        // §10a rather than half of it. A whole sentence, because several places print it as one.
        answer.put("webauthn", "A security key is required: it is asked for at every sign-in, and"
                + " again before anything that changes something - one touch covers the next "
                + STEP_UP.toMinutes() + " minutes.");
        // How long one touch lasts, as a number, so the dialog can say it rather than repeat a
        // literal that would then disagree with this service on the day somebody changes it.
        answer.put("stepUpMinutes", STEP_UP.toMinutes());
        ctx.json(answer);
    }

    private Optional<DiscordAuth.Account> account(final Context ctx) {
        return session(ctx).map(Sessions.Session::account);
    }

    /**
     * This request's session, read once and then parked on the request.
     *
     * <p>The park is a plain attribute rather than a cache with a lifetime, and that matters: it
     * lasts exactly one request, so signing out in one tab is visible to the next request from the
     * other. Anything longer would be a copy of the session outliving the row it copies.</p>
     */
    private Optional<Sessions.Session> session(final Context ctx) {
        if (sessions == null) {
            return Optional.empty();
        }
        final Optional<Sessions.Session> parked = Optional.ofNullable(ctx.attribute(PARKED));
        if (parked.isPresent()) {
            return parked;
        }
        final Optional<Sessions.Session> found = sessions.find(ctx.cookie(Sessions.COOKIE));
        found.ifPresent(session -> ctx.attribute(PARKED, session));
        return found;
    }

    /**
     * One request as the interface shows it.
     *
     * <p>The {@code result} column is JSON, and §10a.4 asks for it drawn rather than shown raw - so
     * it is parsed here and handed over as a structure. A report that cannot be parsed is reported
     * as such and its text kept: an old row written before the format existed is not a failure,
     * and neither is one from a newer version than this jar.</p>
     */
    private static Map<String, Object> describe(final UpdateRequest request) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", request.id());
        row.put("kind", request.kind().name());
        row.put("status", request.status().name());
        row.put("source", request.source().name());
        row.put("requestedBy", request.requestedBy());
        row.put("requested", String.valueOf(request.requested()));
        row.put("notBefore", String.valueOf(request.notBefore()));
        row.put("started", String.valueOf(request.started()));
        row.put("finished", String.valueOf(request.finished()));
        if (request.result() != null && !request.result().isBlank()) {
            UpdateReports.parse(request.result()).ifPresentOrElse(
                    report -> {
                        row.put("report", report);
                        row.put("savedSomething", report.savedSomething());
                    },
                    () -> row.put("resultText", request.result()));
        }
        return row;
    }

    /** The body of {@code POST /api/updates}. */
    private static final class Ask {
        private String kind;
        private Long delaySeconds;
    }

    /** The body of both access endpoints. {@code days} is unused by the revoke. */
    private static final class Grant {
        private String discordId;
        private Integer days;
    }

    /** The body of both season endpoints. Each uses the fields it needs. */
    private static final class SeasonChange {
        private String phase;
        private String reason;
        private String which;
        private String at;
    }

    /**
     * A {@code limit} query parameter, with a default and a ceiling.
     *
     * <p>The ceiling is not politeness. Every one of these endpoints reads rows into memory and
     * serialises them into one response, so a caller asking for a million of them is asking this
     * container to hold a million of them; the number is capped here rather than trusted.</p>
     */
    private static int limit(final Context ctx, final int fallback, final int ceiling) {
        return Math.min(ceiling, Math.max(1,
                ctx.queryParamAsClass("limit", Integer.class).getOrDefault(fallback)));
    }

    /**
     * Double submit: the browser reads the token out of its own session through {@code /api/me}
     * and sends it back in a header. A form posted from another site can carry the cookie but
     * cannot read that value.
     */
    private void requireCsrfToken(final Context ctx) {
        final String sent = ctx.header("X-Steward-CSRF");
        final String expected = session(ctx).map(Sessions.Session::csrf).orElse(null);
        if (expected == null || !expected.equals(sent)) {
            throw new ForbiddenResponse("missing or wrong CSRF token");
        }
    }

    private static boolean isWrite(final Context ctx) {
        final String method = ctx.method().name();
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH")
                || method.equals("DELETE");
    }

    // --- the worker ------------------------------------------------------------------------

    /**
     * The three configuration routes, answered by {@code steward-worker} rather than by this
     * process.
     *
     * <h2>Why they are a proxy and not a handler</h2>
     * Every configuration jcore writes is {@code 0600 root:root}, and this is the one service in
     * the stack that does not run as root. It could therefore neither read nor write any of them,
     * and the page Till opened answered {@code HTTP 400} with the file's path in it - a permission
     * error wearing a bad request's clothes. Loosening the files was the wrong repair, because
     * {@code database.yml} holds the Postgres password and {@code bot.yml} the Discord token. So
     * the seven mounts left this container on 2026-09-14 and the work moved next door, which is
     * what §3 already said about the docker socket: the part an attacker reaches first is not the
     * part that holds the rights.
     *
     * <p><b>This half still decides who may ask.</b> The gates above are unchanged - reading needs
     * the key held, saving needs it held in the last five minutes - and the worker's API is on an
     * internal network behind a shared secret. Neither half can do the other's job.</p>
     *
     * <h2>The worker's own answer is passed through, status and body</h2>
     * Not wrapped in {@code InternalClient.Failure}'s envelope, which would turn "this file was
     * changed while your form was open" into "steward-worker answered 409" and put the sentence a
     * person needs into a {@code detail} field. The interface's own 409 handling keys on the
     * status, and its error alert reads {@code error} - so both have to arrive as the worker wrote
     * them.
     */
    private void forwardConfig(final Context ctx, final String path, final String body) {
        try {
            final String answer = body == null ? worker.get(path) : worker.put(path, body);
            ctx.contentType("application/json").result(answer);
        } catch (final InternalClient.Failure failure) {
            // A refusal the worker composed - 400, 403, 404, 409, 500 - carries its own body, and
            // that body is already this interface's shape. Anything else (no answer at all, a
            // timeout) has no body, and then the envelope IS the message.
            if (failure.body() == null || failure.body().isBlank()) {
                throw failure;
            }
            log.info("steward-worker refused {} with {}", path, failure.status());
            ctx.status(failure.status()).contentType("application/json").result(failure.body());
        }
    }

    /**
     * {@code <file>} as the worker will read it.
     *
     * <p>Javalin decoded it on the way in, so it is encoded again on the way out - segment by
     * segment, because the slash between a service and its file is a path separator on both sides
     * and not part of a name. Without this a file called {@code plugins/My Config.yml} would be
     * sent as a request line with a space in it.</p>
     */
    private static String configPath(final Context ctx) {
        final StringBuilder path = new StringBuilder("/api/config");
        for (final String segment : ctx.pathParam("file").split("/", -1)) {
            path.append('/').append(java.net.URLEncoder
                    .encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return path.toString();
    }

    private void passThrough(final Context ctx, final String path) {
        ctx.contentType("application/json").result(worker.get(path));
    }

    private void follow(final io.javalin.http.sse.SseClient client, final Upstream upstream,
                        final String name, final String query) {
        final String path = "/api/services/" + name + "/logs" + forwardedQuery(query);
        try (InputStream stream = worker.stream(path);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            upstream.hold(stream);
            String line;
            while ((line = reader.readLine()) != null) {
                // The tab is gone. Javalin does not throw on a terminated client - it logs "Cannot
                // send data" and returns - so without this the proxy reads the worker's stream to
                // its end and writes every line of it to nobody, one warning each. Returning here
                // runs the `finally`, which closes the upstream.
                if (client.terminated()) {
                    return;
                }
                // AUTHORISATION IS NOT A THING THAT HAPPENED ONCE. The check at the top of the
                // route is made when the connection opens; a follow outlives it by hours, and a
                // logout or an expiry in between used to change nothing at all - the logs kept
                // arriving in a tab whose session no longer existed. Re-read per line, which is as
                // often as there is anything to withhold.
                if (!stillSignedIn(client)) {
                    client.sendEvent("gone", "this session ended - sign in again to keep watching");
                    return;
                }
                // The worker speaks SSE too, so this is re-emitting its events rather than
                // inventing a second format. `data:` lines are the payload; everything else is
                // framing that this end produces itself.
                if (line.startsWith("data:")) {
                    client.sendEvent("line", line.substring(5).stripLeading());
                }
            }
        } catch (IOException | InternalClient.Failure e) {
            // A stream this end closed on purpose fails the read that was in flight. That is the
            // cancellation working, not an outage, and telling the browser its logs "ended" would
            // be reporting our own hang-up as the worker's.
            if (!upstream.wasClosed()) {
                client.sendEvent("gone", "the log stream ended: " + e.getMessage());
            }
        } finally {
            client.close();
        }
    }

    /**
     * Whoever is watching, still allowed to.
     *
     * <p><b>It deliberately does not use the parked session.</b> Everywhere else one lookup per
     * request is the right trade; here the "request" is a log follow that stays open for hours, and
     * a parked copy would answer <em>yes</em> for as long as the tab was open however long ago the
     * session had ended. So this goes to the row, per line, which is as often as there is anything
     * to withhold.</p>
     *
     * <p>A failure is the answer <em>no</em> rather than an exception: this runs on a streaming
     * thread that is not serving a request and has nowhere to report one, and a database that has
     * stopped answering is not a reason to keep sending somebody logs.</p>
     */
    private boolean stillSignedIn(final io.javalin.http.sse.SseClient client) {
        if (sessions == null) {
            return false;
        }
        try {
            return sessions.find(client.ctx().cookie(Sessions.COOKIE)).isPresent();
        } catch (RuntimeException gone) {
            log.warn("could not re-check a log follower's session, so it is being ended: {}",
                    gone.getMessage());
            return false;
        }
    }

    /** A forwarded query string: {@code "?q=..."}, or nothing at all when there was none. */
    private static String forwardedQuery(final String query) {
        // `"?" + null` is the string "?null", which the worker then parses as a parameter named
        // null - so a search with no parameters arrived as a search for something.
        return query == null || query.isBlank() ? "" : "?" + query;
    }

    /**
     * The worker's end of one log follow, held so that whoever notices the browser has gone can
     * close it.
     *
     * <p>The two halves are opened by two different threads and either can finish first, which is
     * why this is a holder and not a field: a browser that disappears while the worker is still
     * being connected to must leave the stream closed <em>on arrival</em>, and one that leaves an
     * hour in must interrupt a read already in flight. Both are one lock and four lines.</p>
     */
    private static final class Upstream {

        private InputStream stream;
        private boolean closed;

        synchronized void hold(final InputStream open) {
            stream = open;
            if (closed) {
                shut(open);
            }
        }

        synchronized void close() {
            closed = true;
            shut(stream);
        }

        synchronized boolean wasClosed() {
            return closed;
        }

        private static void shut(final InputStream open) {
            if (open == null) {
                return;
            }
            try {
                open.close();
            } catch (IOException ignored) {
                // Closing to cancel a read; the read is what reports anything worth reporting.
            }
        }
    }

    private static String random() {
        final byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
        streams.shutdownNow();
        heartbeats.shutdownNow();
    }
}

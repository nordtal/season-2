package eu.nordtal.s2.steward.ui;

import com.google.gson.Gson;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessSource;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateReports;
import eu.nordtal.s2.common.update.UpdateRequest;
import eu.nordtal.s2.common.update.UpdateSource;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.discord.DiscordApi;
import eu.nordtal.s2.steward.ui.discord.DiscordDirectory;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.steward.ui.config.Configs;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ForbiddenResponse;
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
import java.util.function.Function;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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

    /** Session keys. Short names, one place. */
    private static final String ACCOUNT = "steward.account";
    private static final String CSRF = "steward.csrf";
    private static final String STATE = "steward.oauth-state";

    /**
     * The longest access anybody may be granted from here, in days.
     *
     * <p>A decade is nine seasons more than a season lasts, so it refuses nothing real. What it
     * does refuse is a slip of the keyboard reaching PostgreSQL, where the interval is built as
     * {@code hours => days * 24} and overflows an integer long before {@code Integer.MAX_VALUE}.
     * A ceiling here is a sentence the operator can read; the overflow there is a 500.</p>
     */
    private static final int MOST_DAYS = 3650;

    private final UiSpec config;
    private final DiscordAuth discord;
    private final InternalClient worker;

    /**
     * Where "who is this" is answered.
     *
     * <p>A seam with one production implementation - the session - and one reason to exist: a test
     * can stand in front of it and exercise everything behind the sign-in without a Discord
     * application, which is a thing only Till can create. It is a function of the request rather
     * than a flag, so there is no mode this service can be started in that skips authentication.
     * </p>
     */
    private final Function<Context, Optional<DiscordAuth.Account>> accounts;

    /** The database. Null only in tests that are about the proxy and never touch a row. */
    private final Data data;

    /** The other services' config files, mounted into this container. */
    private final ConfigApi configs;

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
        this(config, discord, worker, deployer, data, StewardUi::fromSession);
    }

    StewardUi(final UiSpec config, final DiscordAuth discord, final InternalClient worker,
              final InternalClient deployer, final Data data,
              final Function<Context, Optional<DiscordAuth.Account>> accounts) {
        this.config = config;
        this.discord = discord;
        this.worker = worker;
        this.data = data;
        this.accounts = accounts;
        this.configs = new ConfigApi(Path.of(config.configs().root()));
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

    public Javalin start(final int port) {
        app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinGson(new Gson(), true));
            cfg.startup.showJavalinBanner = false;

            // The built frontend, out of the jar. `/web` is where Gradle's vite build lands.
            cfg.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/web";
                staticFiles.location = Location.CLASSPATH;
            });
            // A client-side router owns every path that is not an API call or a file, so an
            // unknown path is index.html rather than a 404 - which is what makes a reload of
            // /operations/runs/27 land on the page it names instead of on nothing.
            cfg.spaRoot.addFile("/", "/web/index.html", Location.CLASSPATH);

            cfg.routes.get("/api/health", ctx -> ctx.json(Map.of(
                    "status", "ok",
                    "worker", worker.isReachable())));

            // WHO MAY SIGN IN IS ANSWERED BEFORE ANYTHING ELSE IS SERVED. The sign-in page itself
            // needs to be readable without a session, and so does the static bundle - everything
            // under /api that is not health or this does not.
            cfg.routes.get("/api/me", this::whoAmI);

            cfg.routes.before("/api/*", ctx -> {
                final String path = ctx.path();
                if (path.equals("/api/health") || path.equals("/api/me")) {
                    return;
                }
                if (account(ctx).isEmpty()) {
                    throw new UnauthorizedResponse("sign in first");
                }
                if (isWrite(ctx)) {
                    requireCsrfToken(ctx);
                }
            });

            cfg.routes.get("/auth/login", this::login);
            cfg.routes.get("/auth/callback", this::callback);
            cfg.routes.post("/auth/logout", ctx -> {
                // THE SAME CHECK THE API IS BEHIND, and it has to be repeated here because this
                // route is not under /api/* and the filter above therefore never sees it. Javalin
                // 7.2.3 leaves SameSite unset on JSESSIONID - that is Jetty's default and the
                // framework does not override it - so a form on any other site can post here
                // carrying the cookie. Signing somebody out in the middle of a deployment they are
                // watching is not a disaster, but it is a thing a stranger should not be able to do.
                requireCsrfToken(ctx);
                ctx.req().getSession().invalidate();
                ctx.status(204);
            });

            // --- everything about a container comes from steward-worker -----------------------
            cfg.routes.get("/api/services", ctx -> passThrough(ctx, "/api/services"));
            cfg.routes.get("/api/services/{name}", ctx ->
                    passThrough(ctx, "/api/services/" + ctx.pathParam("name")));
            cfg.routes.get("/api/services/{name}/logs/search", ctx -> passThrough(ctx,
                    "/api/services/" + ctx.pathParam("name") + "/logs/search"
                            + forwardedQuery(ctx.queryString())));
            cfg.routes.post("/api/services/{name}/console", ctx -> {
                final String answer = worker.post(
                        "/api/services/" + ctx.pathParam("name") + "/console", ctx.body());
                ctx.status(202).contentType("application/json").result(answer);
            });
            // --- and creating one comes from steward-deployer, which is a different service ---
            //
            // Not the same door as an update: an update is a countable, cancellable row that
            // steward-worker carries out with a countdown in front of every player online. This is
            // one container, made again from the image that is already on the host, and the only
            // process in the stack allowed to do it is the deployer (8a).
            cfg.routes.get("/api/deployer", deployments::state);
            cfg.routes.get("/api/deployer/services", deployments::services);
            cfg.routes.post("/api/deployer/recreate/{service}", deployments::recreate);
            cfg.routes.get("/api/deployer/jobs", deployments::jobs);
            cfg.routes.get("/api/deployer/jobs/{id}", deployments::job);

            cfg.routes.get("/api/host", ctx -> passThrough(ctx, "/api/host"));
            // What "tonight" means on the host, rather than in whatever zone the browser is in.
            cfg.routes.get("/api/schedule", ctx -> passThrough(ctx, "/api/schedule"));
            cfg.routes.get("/api/backups", ctx -> passThrough(ctx, "/api/backups"));

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
            });

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
            });

            cfg.routes.get("/api/updates", ctx -> {
                // The same helper every other list on this class uses. It used to clamp only the
                // top end, which left `?limit=0` and `?limit=-5` to be reinterpreted three layers
                // down in the directory - so one endpoint had its floor somewhere else than all
                // the others, and nothing said where.
                ctx.json(data.updates().recent(limit(ctx, 20, 200)).stream()
                        .map(StewardUi::describe).toList());
            });

            cfg.routes.get("/api/updates/{id}", ctx -> {
                final long id = Long.parseLong(ctx.pathParam("id"));
                ctx.json(data.updates().find(id)
                        .map(StewardUi::describe)
                        .orElseThrow(() -> new NotFoundResponse("no request " + id)));
            });

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
            });

            // --- the thresholds the start page judges by ---------------------------------------
            //
            // Read from this service's own config rather than kept in the browser, because the
            // Ampel has to be able to fire into Discord as well, and a number in somebody's
            // localStorage cannot be read by anything that is not that browser.
            // --- the five admin commands that stayed in the game (§10b) ------------------------
            //
            // A row in `command_request`, not a connection to a server: the interface holds none.
            // `source = WEB` rather than CONSOLE, because V11 pins a CONSOLE row to having no
            // identity at all - every admin command from here would otherwise be anonymous, which
            // is the question the journal exists to answer. V18 adds the value and the CHECK.
            cfg.routes.get("/api/commands", commands::list);
            cfg.routes.post("/api/commands", commands::ask);
            cfg.routes.get("/api/commands/{id}", commands::outcome);

            // --- the configuration of every service in the stack ------------------------------
            //
            // Till's decision, 2026-09-13: every config in the stack is editable from here, with
            // labels a person can read. What makes that possible without steward-ui depending on
            // six other modules - one of which would drag a Paper API onto a web server's
            // classpath - is that jcore writes its comments into the YAML. The file is the model.
            cfg.routes.get("/api/config", configs::list);
            cfg.routes.get("/api/config/<file>", configs::one);
            cfg.routes.put("/api/config/<file>", configs::save);

            // The names behind the ids, so the editor above can offer a list instead of a field.
            // Never a failure: an unreachable Discord is `available: false` and a typed id.
            cfg.routes.get("/api/discord/roles", guild::roles);
            cfg.routes.get("/api/discord/channels", guild::channels);

            cfg.routes.get("/api/settings", ctx -> ctx.json(Map.of(
                    "disk", config.alerts().diskPercent(),
                    "memory", config.alerts().memoryPercent(),
                    "backupAgeHours", config.alerts().backupAgeHours())));

            // --- who is in the guild, what they paid, what they may ----------------------------
            cfg.routes.get("/api/people", ctx -> ctx.json(
                    data.roster().people(limit(ctx, 500, 2000))));

            cfg.routes.get("/api/people/{id}/grants", ctx -> ctx.json(
                    data.roster().grantsOf(ctx.pathParam("id"))));

            cfg.routes.get("/api/payments", ctx -> ctx.json(
                    data.roster().payments(limit(ctx, 200, 1000))));

            cfg.routes.get("/api/journal", ctx -> ctx.json(data.audit().search(
                    ctx.queryParam("action"), ctx.queryParam("subject"),
                    limit(ctx, 200, 1000))));

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
            });

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
            });

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
            });

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
            });

            cfg.routes.get("/api/season", ctx -> {
                final Map<String, Object> season = new LinkedHashMap<>();
                season.put("phase", data.phase().currentPhase().name());
                data.phase().launch().ifPresent(at -> season.put("launch", at.toString()));
                data.phase().smpStart().ifPresent(at -> season.put("smpStart", at.toString()));
                ctx.json(season);
            });

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

        discord.whatIsMissing().ifPresent(missing -> log.warn(
                "Nobody can sign in yet: {} is empty. Everything else is running.", missing));
        log.info("Nordtal Steward is on {} - public address {}", port, config.publicUrl());
        return app;
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
        ctx.sessionAttribute(STATE, state);
        ctx.redirect(discord.authorizeUrl(state).toString());
    }

    private void callback(final Context ctx) {
        final String expected = ctx.consumeSessionAttribute(STATE);
        final String state = ctx.queryParam("state");
        if (expected == null || !expected.equals(state)) {
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
        ctx.sessionAttribute(ACCOUNT, outcome.account());
        ctx.sessionAttribute(CSRF, random());
        ctx.req().getSession().setMaxInactiveInterval(config.sessionHours() * 3600);
        ctx.redirect("/");
    }

    private void whoAmI(final Context ctx) {
        final Optional<DiscordAuth.Account> account = account(ctx);
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("signedIn", account.isPresent());
        account.ifPresent(who -> {
            answer.put("id", who.id());
            answer.put("name", who.name());
            // Minted here if the session does not have one yet. The browser cannot send a token it
            // was never given, and this is the one route it is allowed to read before it has one.
            String csrf = ctx.sessionAttribute(CSRF);
            if (csrf == null) {
                csrf = random();
                ctx.sessionAttribute(CSRF, csrf);
            }
            answer.put("csrf", csrf);
        });
        discord.whatIsMissing().ifPresent(missing -> answer.put("signInUnavailable", missing));
        // Said out loud rather than in a footnote: §10a wants a security key after Discord, and
        // this alpha does not have one.
        answer.put("webauthn", "not built in this alpha - a Discord session is the whole of the "
                + "authentication");
        ctx.json(answer);
    }

    private Optional<DiscordAuth.Account> account(final Context ctx) {
        return accounts.apply(ctx);
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

    private static Optional<DiscordAuth.Account> fromSession(final Context ctx) {
        return Optional.ofNullable(ctx.sessionAttribute(ACCOUNT));
    }

    /**
     * Double submit: the browser reads the token out of its own session through {@code /api/me}
     * and sends it back in a header. A form posted from another site can carry the cookie but
     * cannot read that value.
     */
    private void requireCsrfToken(final Context ctx) {
        final String sent = ctx.header("X-Steward-CSRF");
        final String expected = ctx.sessionAttribute(CSRF);
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
     * <p>An invalidated session is not a {@code false} from the servlet container - it is an
     * {@link IllegalStateException} on the next read of it, thrown from a thread that is not
     * serving a request and has nowhere to report it. That case is exactly the one this method
     * exists for, so it is the answer <em>no</em> rather than a failure.</p>
     */
    private boolean stillSignedIn(final io.javalin.http.sse.SseClient client) {
        try {
            return account(client.ctx()).isPresent();
        } catch (RuntimeException gone) {
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

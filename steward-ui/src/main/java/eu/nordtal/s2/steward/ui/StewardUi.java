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
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.steward.ui.config.Configs;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.worker.WorkerClient;
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

    private final UiSpec config;
    private final DiscordAuth discord;
    private final WorkerClient worker;

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
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

    private Javalin app;

    public StewardUi(final UiSpec config, final DiscordAuth discord, final WorkerClient worker,
                     final Data data) {
        this(config, discord, worker, data, StewardUi::fromSession);
    }

    StewardUi(final UiSpec config, final DiscordAuth discord, final WorkerClient worker,
              final Data data, final Function<Context, Optional<DiscordAuth.Account>> accounts) {
        this.config = config;
        this.discord = discord;
        this.worker = worker;
        this.data = data;
        this.accounts = accounts;
        this.configs = new ConfigApi(Path.of(config.configs().root()));
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

        final WorkerClient worker = new WorkerClient(config.worker().baseUrl(),
                config.worker().token(), Duration.ofSeconds(10));
        if (config.worker().token().isBlank()) {
            log.warn("worker.token is empty, so nothing about a container can be read. Every page"
                    + " that would show one says so instead of drawing an empty table.");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(data::close, "steward-ui-shutdown"));
        new StewardUi(config, new DiscordAuth(config.discord(), config.publicUrl()), worker, data)
                .start(config.port());
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
            // /betrieb/lauf/27 land on the page it names instead of on nothing.
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
                    // Double submit: the browser reads the token out of its own session through
                    // /api/me and sends it back in a header. A form posted from another site can
                    // carry the cookie but cannot read that value.
                    final String sent = ctx.header("X-Steward-CSRF");
                    final String expected = ctx.sessionAttribute(CSRF);
                    if (expected == null || !expected.equals(sent)) {
                        throw new ForbiddenResponse("missing or wrong CSRF token");
                    }
                }
            });

            cfg.routes.get("/auth/login", this::login);
            cfg.routes.get("/auth/callback", this::callback);
            cfg.routes.post("/auth/logout", ctx -> {
                ctx.req().getSession().invalidate();
                ctx.status(204);
            });

            // --- everything about a container comes from steward-worker -----------------------
            cfg.routes.get("/api/services", ctx -> passThrough(ctx, "/api/services"));
            cfg.routes.get("/api/services/{name}", ctx ->
                    passThrough(ctx, "/api/services/" + ctx.pathParam("name")));
            cfg.routes.get("/api/services/{name}/logs/search", ctx -> passThrough(ctx,
                    "/api/services/" + ctx.pathParam("name") + "/logs/search?"
                            + ctx.queryString()));
            cfg.routes.post("/api/services/{name}/console", ctx -> {
                final String answer = worker.post(
                        "/api/services/" + ctx.pathParam("name") + "/console", ctx.body());
                ctx.status(202).contentType("application/json").result(answer);
            });
            cfg.routes.get("/api/host", ctx -> passThrough(ctx, "/api/host"));
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
                client.keepAlive();
                streams.submit(() -> follow(client, name, query));
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
                final int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
                ctx.json(data.updates().recent(Math.min(200, limit)).stream()
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
            // --- the configuration of every service in the stack ------------------------------
            //
            // Till's decision, 2026-09-13: every config in the stack is editable from here, with
            // labels a person can read. What makes that possible without steward-ui depending on
            // six other modules - one of which would drag a Paper API onto a web server's
            // classpath - is that jcore writes its comments into the YAML. The file is the model.
            cfg.routes.get("/api/config", configs::list);
            cfg.routes.get("/api/config/<file>", configs::one);
            cfg.routes.put("/api/config/<file>", configs::save);

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
                if (ask.days == null || ask.days <= 0) {
                    throw new BadRequestResponse("days must be a positive number of days");
                }
                final DiscordAuth.Account who = account(ctx).orElseThrow();
                // ensureUser first: a grant against a Discord id the bot has never seen would fail
                // on the foreign key, and "this person has not spoken to the bot yet" is a worse
                // error message than simply making the row.
                data.access().ensureUser(ask.discordId);
                final var granted = data.access().grantAccess(
                        ask.discordId, ask.days, AccessSource.ADMIN, null);
                data.audit().record("GRANT_ACCESS", who.name() + " (" + who.id() + ")",
                        ask.discordId, null,
                        ask.days + " days from the web interface, until " + granted.validUntil());
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
                data.audit().record("REVOKE_ACCESS", who.name() + " (" + who.id() + ")",
                        ask.discordId, null, revoked + " grant(s) revoked from the web interface");
                log.info("{} revoked {} grants of {}", who.name(), revoked, ask.discordId);
                ctx.json(Map.of("revoked", revoked));
            });

            // --- the season ------------------------------------------------------------------
            //
            // PhaseDirectory writes its own audit_log row inside the statement that performs the
            // change, so nothing is recorded twice here. That is also why the actor has to be
            // passed in rather than recorded afterwards.
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
                final var change = data.phase().switchPhase(phase, who.name() + " (" + who.id() + ")",
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
                final String actor = who.name() + " (" + who.id() + ")";
                final var change = "smpStart".equals(ask.which)
                        ? data.phase().setSmpStart(at, actor)
                        : data.phase().setLaunch(at, actor);
                ctx.json(change);
            });

            cfg.routes.get("/api/season", ctx -> {
                final Map<String, Object> season = new LinkedHashMap<>();
                season.put("phase", data.phase().currentPhase().name());
                data.phase().launch().ifPresent(at -> season.put("launch", at.toString()));
                data.phase().smpStart().ifPresent(at -> season.put("smpStart", at.toString()));
                ctx.json(season);
            });

            cfg.routes.exception(WorkerClient.WorkerException.class, (failure, ctx) -> {
                // The interface has to say which half is down. "steward-worker is not answering"
                // is a sentence somebody can act on; an empty table is a stack that looks stopped.
                log.warn("the worker did not answer: {}", failure.getMessage());
                ctx.status(failure.status() == 0 ? 502 : failure.status())
                        .json(Map.of("error", failure.getMessage(),
                                "where", "steward-worker",
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

    private static boolean isWrite(final Context ctx) {
        final String method = ctx.method().name();
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH")
                || method.equals("DELETE");
    }

    // --- the worker ------------------------------------------------------------------------

    private void passThrough(final Context ctx, final String path) {
        ctx.contentType("application/json").result(worker.get(path));
    }

    private void follow(final io.javalin.http.sse.SseClient client, final String name,
                        final String query) {
        final String path = "/api/services/" + name + "/logs"
                + (query == null || query.isBlank() ? "" : "?" + query);
        try (InputStream stream = worker.stream(path);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // The worker speaks SSE too, so this is re-emitting its events rather than
                // inventing a second format. `data:` lines are the payload; everything else is
                // framing that this end produces itself.
                if (line.startsWith("data:")) {
                    client.sendEvent("line", line.substring(5).stripLeading());
                }
            }
        } catch (IOException | WorkerClient.WorkerException e) {
            client.sendEvent("gone", "the log stream ended: " + e.getMessage());
        } finally {
            client.close();
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
    }
}

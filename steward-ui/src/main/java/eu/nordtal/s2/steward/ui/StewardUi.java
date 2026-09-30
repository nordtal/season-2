package eu.nordtal.s2.steward.ui;

import com.google.gson.Gson;
import eu.nordtal.s2.database.access.AdminTree;
import eu.nordtal.s2.database.access.PackExemptions;
import eu.nordtal.s2.steward.ui.auth.Credentials;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.Gate;
import eu.nordtal.s2.steward.ui.auth.Sessions;
import eu.nordtal.s2.steward.ui.auth.WebAuthn;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.data.ExampleValues;
import eu.nordtal.s2.steward.ui.discord.DiscordApi;
import eu.nordtal.s2.steward.ui.discord.DiscordDirectory;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import eu.nordtal.s2.steward.ui.push.AlertWatch;
import eu.nordtal.s2.steward.ui.push.PushPreferences;
import eu.nordtal.s2.steward.ui.push.PushSubscriptions;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinGson;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nordtal Steward, the web interface.
 *
 * It never touches Docker: containers are steward-worker's business, and creating one is steward-deployer's.
 */
public final class StewardUi {

    private static final Logger log = LoggerFactory.getLogger(StewardUi.class);

    /** Where this request's session is parked once it has been read, so it is looked up once. */
    private static final String PARKED = "steward.session";

    /** How often expired rows are swept out of {@code steward_session}. */
    static final Duration SWEEP = Duration.ofHours(1);

    /** How long one touch of the security key covers; deliberately not configurable. */
    static final Duration STEP_UP = Duration.ofMinutes(5);

    /** The default command, named so that spelling it out is not an error. */
    static final String SERVE = "serve";

    /** Clears a signed-in admin's second factor. Reachable only from a shell on the host. */
    static final String FORGET = "forget-factors";

    /** Prints a fresh VAPID keypair for {@code steward-ui.yml}. */
    static final String GENERATE_VAPID_KEYS = "generate-vapid-keys";

    private final UiSpec config;
    private final DiscordAuth discord;

    private final WorkerProxy workerProxy;

    /** Signed-in browsers; null only in a test that signs nobody in. */
    private final @Nullable Sessions sessions;

    private final SecondFactor secondFactor;

    private final @Nullable ExampleValues exampleValues;

    private final PushEndpoints push;

    /** Pushes proactive alerts; null without a VAPID keypair. */
    private final @Nullable AlertWatch alertWatch;

    /** The names of the guild's roles and channels, so an id can be picked rather than typed. */
    private final DiscordApi guild;

    private final CommandApi commands;

    private final GameActions games;
    private final Announcements announcements;

    private final AccessApi access;

    private final RosterRoutes roster;

    private final Settings settings;
    /** Who may sign in; null only in a test without a database. */
    private final @Nullable AdminTree admins;

    private final @Nullable AdminApi adminApi;

    private final @Nullable PackExemptionApi packExemptionApi;
    private final AuthFlow authFlow;

    private final DeployerApi deployments;

    private final Updates updates;

    private final SeasonRoutes season;

    private final Profile profile;

    private final LogFollow logFollow;

    private final Gatekeeper gatekeeper;

    private final Metrics metrics;

    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

    /** How often {@link AlertWatch#poll} asks steward-worker for the traffic light's state. */
    private static final Duration ALERT_POLL = Duration.ofSeconds(30);

    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "steward-ui-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private Javalin app;

    public StewardUi(
            final UiSpec config,
            final DiscordAuth discord,
            final InternalClient worker,
            final InternalClient deployer,
            final @Nullable Data data) {
        this.config = config;
        this.discord = discord;
        this.workerProxy = new WorkerProxy(worker, ctx -> account(ctx).orElseThrow());
        this.sessions = data == null ? null : new Sessions(data.dataSource(), Duration.ofDays(config.sessionDays()));
        final @Nullable Credentials localCredentials = data == null ? null : new Credentials(data.dataSource());
        final @Nullable WebAuthn localWebauthn = localCredentials == null
                ? null
                : new WebAuthn(config.webauthn().relyingPartyId(), config.publicUrl(), localCredentials);
        this.secondFactor =
                new SecondFactor(this::requireSession, data, localCredentials, this.sessions, localWebauthn);
        this.gatekeeper = new Gatekeeper(this::session, this.secondFactor);
        this.metrics = new Metrics(data);
        this.guild = new DiscordApi(new DiscordDirectory(config.discord(), DiscordAuth.DISCORD_API));
        this.commands = new CommandApi(data, ctx -> account(ctx).orElseThrow());
        this.games = new GameActions(data == null ? null : data.dataSource(), commands);
        this.announcements = new Announcements(data == null ? null : data.dataSource(), commands);
        this.access = new AccessApi(data, ctx -> account(ctx).orElseThrow());
        this.roster = new RosterRoutes(data);
        this.settings = new Settings(config);
        final @Nullable AdminTree localAdmins = data == null ? null : AdminTree.using(data.dataSource());
        this.admins = localAdmins;
        this.adminApi = data == null
                ? null
                : new AdminApi(
                        Objects.requireNonNull(localAdmins),
                        data.audit(),
                        ctx -> account(ctx).orElseThrow());
        this.packExemptionApi = data == null
                ? null
                : new PackExemptionApi(
                        PackExemptions.using(data.dataSource()),
                        data.audit(),
                        ctx -> account(ctx).orElseThrow());
        this.authFlow = new AuthFlow(config, discord, data, this.sessions, localAdmins);
        this.updates = new Updates(data, worker, ctx -> account(ctx).orElseThrow());
        this.season = new SeasonRoutes(data, ctx -> account(ctx).orElseThrow());
        this.deployments = new DeployerApi(
                deployer,
                data,
                ctx -> account(ctx).orElseThrow(),
                !config.deployer().token().isBlank());
        final @Nullable PushSubscriptions localPushSubscriptions =
                data == null ? null : new PushSubscriptions(data.dataSource());
        final @Nullable PushPreferences localPushPreferences =
                data == null ? null : new PushPreferences(data.dataSource());
        this.exampleValues = data == null ? null : new ExampleValues(data.dataSource());
        this.profile = new Profile(
                this::requireSession,
                this::session,
                localCredentials,
                data,
                discord,
                localWebauthn,
                this.exampleValues);
        this.logFollow = new LogFollow(worker, this.sessions, this::account, streams, heartbeats);
        final PushWiring pushWiring = wirePush(config, worker, data, localPushSubscriptions, localPushPreferences);
        this.alertWatch = pushWiring.alertWatch();
        this.push = pushWiring.push();
    }

    private record PushWiring(@Nullable AlertWatch alertWatch, PushEndpoints push) {}

    private PushWiring wirePush(
            final UiSpec config,
            final InternalClient worker,
            final @Nullable Data data,
            final @Nullable PushSubscriptions localPushSubscriptions,
            final @Nullable PushPreferences localPushPreferences) {
        final com.interaso.webpush.@Nullable VapidKeys localVapidKeys = vapidKeysOf(config.webPush());
        // The same thresholds /api/settings answers, so a lock screen and a browser tab agree.
        final @Nullable AlertWatch localAlertWatch =
                (localPushSubscriptions == null || localPushPreferences == null || localVapidKeys == null)
                        ? null
                        : new AlertWatch(
                                worker,
                                localPushSubscriptions,
                                localPushPreferences,
                                config.webPush().subject(),
                                localVapidKeys,
                                config.alerts().diskPercent(),
                                config.alerts().memoryPercent(),
                                config.alerts().backupAgeHours());
        final PushEndpoints localPush = new PushEndpoints(
                this::requireSession,
                data,
                localPushSubscriptions,
                localPushPreferences,
                localAlertWatch,
                localVapidKeys);
        return new PushWiring(localAlertWatch, localPush);
    }

    /** Fails the route when this instance has no database. */
    private Sessions sessions() {
        return Objects.requireNonNull(sessions, "this route needs sessions, which this instance has none of");
    }

    /** Fails the route when this instance has no database. */
    private AdminTree admins() {
        return Objects.requireNonNull(admins, "this route needs admins, which this instance has none of");
    }

    /** Fails the route when this instance has no database. */
    private AdminApi adminApi() {
        return Objects.requireNonNull(adminApi, "this route needs adminApi, which this instance has none of");
    }

    /** {@link #packExemptionApi}, for the routes that only exist once a database does. */
    private PackExemptionApi packExemptionApi() {
        return Objects.requireNonNull(
                packExemptionApi, "this route needs packExemptionApi, which this instance has none of");
    }

    /**
     * The VAPID keypair from config, or null when {@code web-push} is not configured.
     *
     * Exactly one blank key is refused, since neither alone can sign or serve as an {@code applicationServerKey}.
     */
    private static com.interaso.webpush.@Nullable VapidKeys vapidKeysOf(final UiSpec.WebPushSpec webPush) {
        final String publicKey = webPush.publicKey();
        final String privateKey = webPush.privateKey();
        if (publicKey.isBlank() && privateKey.isBlank()) {
            return null;
        }
        if (publicKey.isBlank() || privateKey.isBlank()) {
            throw new IllegalArgumentException("web-push has only one of public-key/private-key set"
                    + " - both or neither. Run `steward-ui " + GENERATE_VAPID_KEYS + "` and paste"
                    + " both lines it prints into steward-ui.yml.");
        }
        return com.interaso.webpush.VapidKeys.create(publicKey, privateKey);
    }

    /** The container's entry point; see {@link Cli#run}. */
    public static void main(final String[] args) {
        Cli.run(args);
    }

    public Javalin start(final int port) {
        app = Javalin.create(cfg -> {
                    configureFrontend(cfg);
                    registerAuthCore(cfg);
                    registerSecondFactorAndKeys(cfg);
                    registerWebPushRoutes(cfg);
                    registerServiceRoutes(cfg);
                    registerPluginRoutes(cfg);
                    registerDeployerRoutes(cfg);
                    registerActionsAndBackupRoutes(cfg);
                    registerLogFollowRoute(cfg);
                    registerMetricsRoute(cfg);
                    registerUpdateListRoutes(cfg);
                    registerUpdateLookupRoutes(cfg);
                    registerUpdateAskRoute(cfg);
                    registerUpdateCancelRoute(cfg);
                    registerCommandAndGameRoutes(cfg);
                    registerConfigRoutes(cfg);
                    registerDiscordAndSettingsRoutes(cfg);
                    registerRosterRoutes(cfg);
                    registerAccessRoutes(cfg);
                    registerSeasonPhaseRoute(cfg);
                    registerSeasonDateRoute(cfg);
                    registerSeasonSummaryRoute(cfg);
                    registerFallbackRoutes(cfg);
                    ErrorHandlers.install(cfg);
                })
                .start(port);

        if (sessions != null) {
            // Runs on the existing scheduler; an expired session is refused by the lookup regardless.
            final var _ =
                    heartbeats.scheduleWithFixedDelay(authFlow::sweepSessions, 0, SWEEP.toSeconds(), TimeUnit.SECONDS);
        }
        if (alertWatch != null) {
            // Same scheduler as the sweep above: one small GET, not a workload of its own.
            final var _ =
                    heartbeats.scheduleWithFixedDelay(alertWatch::poll, 0, ALERT_POLL.toSeconds(), TimeUnit.SECONDS);
        }
        discord.whatIsMissing()
                .ifPresent(missing ->
                        log.warn("Nobody can sign in yet: {} is empty. Everything else is running.", missing));
        log.info("Nordtal Steward is on {} - public address {}", port, config.publicUrl());
        return app;
    }

    private void configureFrontend(final JavalinConfig cfg) {
        cfg.jsonMapper(new JavalinGson(new Gson(), true));
        cfg.startup.showJavalinBanner = false;

        // The built frontend, which Gradle packs into the jar under /web.
        cfg.staticFiles.add(staticFiles -> {
            staticFiles.hostedPath = "/";
            staticFiles.directory = "/web";
            staticFiles.location = Location.CLASSPATH;
            // Static files bypass cfg.routes, so `guard` cannot read a Gate off them; set here instead.
            staticFiles.roles = Set.of(Gate.ANYONE);
        });
        // Unmatched paths fall through to index.html so the client-side router can own them.
        cfg.spaRoot.addFile("/", "/web/index.html", Location.CLASSPATH);

        // The only hook that sees both static files and the single-page fallback.
        cfg.routes.after(Gatekeeper::cacheHeaders);
    }

    private void registerAuthCore(final JavalinConfig cfg) {
        cfg.routes.get("/api/health", workerProxy::health, Gate.ANYONE);
        // `guard` reads `ctx.routeRoles()` per HTTP method, so HEAD needs its own registration too.
        cfg.routes.head("/api/health", workerProxy::health, Gate.ANYONE);

        cfg.routes.get("/api/me", profile::whoAmI, Gate.ANYONE);

        // Runs in front of every matched endpoint and asks it what it requires; see Gate.
        cfg.routes.beforeMatched(gatekeeper::guard);

        cfg.routes.get("/auth/login", authFlow::login, Gate.ANYONE);
        cfg.routes.get("/auth/callback", authFlow::callback, Gate.ANYONE);
        cfg.routes.post(
                "/auth/logout",
                ctx -> {
                    // SameSite=Lax still allows a top-level POST, so the CSRF token check applies here too.
                    sessions().end(ctx.cookie(Sessions.COOKIE));
                    ctx.removeCookie(Sessions.COOKIE, "/");
                    ctx.status(204);
                },
                Gate.SIGNED_IN);
    }

    private void registerSecondFactorAndKeys(final JavalinConfig cfg) {
        // A door cannot ask for the key it exists to hand out.
        cfg.routes.post("/auth/webauthn/register/start", secondFactor::beginRegistration, Gate.SIGNED_IN);
        cfg.routes.post("/auth/webauthn/register/finish", secondFactor::finishRegistration, Gate.SIGNED_IN);
        cfg.routes.post("/auth/webauthn/authenticate/start", secondFactor::beginAssertion, Gate.SIGNED_IN);
        cfg.routes.post("/auth/webauthn/authenticate/finish", secondFactor::finishAssertion, Gate.SIGNED_IN);

        // The key list itself is part of /api/me, not a route of its own.
        cfg.routes.put("/api/keys/{id}", secondFactor::renameKey, Gate.KEY_FRESH);
        cfg.routes.delete("/api/keys/{id}", secondFactor::removeKey, Gate.KEY_FRESH);
    }

    private void registerWebPushRoutes(final JavalinConfig cfg) {
        cfg.routes.get("/api/web-push/public-key", push::publicKey, Gate.KEY_HELD);
        cfg.routes.post("/api/web-push/subscribe", push::subscribe, Gate.KEY_FRESH);
        cfg.routes.delete("/api/web-push/subscribe", push::unsubscribe, Gate.KEY_FRESH);

        // A test send is a write: it reaches out to a push service in somebody's name.
        cfg.routes.get("/api/web-push/devices", push::devices, Gate.KEY_HELD);
        cfg.routes.get("/api/web-push/preferences", push::preferences, Gate.KEY_HELD);
        cfg.routes.put("/api/web-push/preferences", push::setPreference, Gate.KEY_FRESH);
        cfg.routes.post("/api/web-push/test", push::test, Gate.KEY_FRESH);
    }

    private void registerServiceRoutes(final JavalinConfig cfg) {
        cfg.routes.get("/api/services", workerProxy::services, Gate.KEY_HELD);
        cfg.routes.get("/api/services/{name}", workerProxy::service, Gate.KEY_HELD);
        cfg.routes.post("/api/services/{name}/console", workerProxy::console, Gate.KEY_FRESH);
    }

    private void registerPluginRoutes(final JavalinConfig cfg) {
        // Not the door an update goes through: installing writes a row the next run picks up.
        cfg.routes.get("/api/services/{name}/plugins", workerProxy::plugins, Gate.KEY_HELD);
        cfg.routes.get("/api/services/{name}/plugins/search", workerProxy::pluginSearch, Gate.KEY_HELD);
        cfg.routes.post("/api/services/{name}/plugins", workerProxy::installPlugin, Gate.KEY_FRESH);
        cfg.routes.delete("/api/services/{name}/plugins/{artifact}", workerProxy::removePlugin, Gate.KEY_FRESH);
    }

    private void registerDeployerRoutes(final JavalinConfig cfg) {
        // Not the update door either: this recreates one container, and only the deployer may.
        cfg.routes.get("/api/deployer", deployments::state, Gate.KEY_HELD);
        cfg.routes.get("/api/deployer/services", deployments::services, Gate.KEY_HELD);
        cfg.routes.post("/api/deployer/recreate/{service}", deployments::recreate, Gate.KEY_FRESH);
        cfg.routes.get("/api/deployer/jobs", deployments::jobs, Gate.KEY_HELD);
        cfg.routes.get("/api/deployer/jobs/{id}", deployments::job, Gate.KEY_HELD);
    }

    private void registerActionsAndBackupRoutes(final JavalinConfig cfg) {
        cfg.routes.get("/api/actions", workerProxy::actions, Gate.KEY_HELD);
        cfg.routes.get("/api/host", workerProxy::host, Gate.KEY_HELD);
        cfg.routes.get("/api/schedule", workerProxy::schedule, Gate.KEY_HELD);
        cfg.routes.get("/api/backups", workerProxy::backups, Gate.KEY_HELD);

        // Reading a backup off the disk is a read, like watching its log.
        cfg.routes.get(
                "/api/backups/{name}/download",
                ctx -> workerProxy.downloadBackup(ctx, ctx.pathParam("name")),
                Gate.KEY_HELD);
    }

    private void registerLogFollowRoute(final JavalinConfig cfg) {
        cfg.routes.sse("/api/services/{name}/logs", logFollow::serve, Gate.KEY_HELD);
    }

    private void registerMetricsRoute(final JavalinConfig cfg) {
        cfg.routes.get("/api/metrics", metrics::range, Gate.KEY_HELD);
    }

    private void registerUpdateListRoutes(final JavalinConfig cfg) {
        cfg.routes.get("/api/updates", updates::list, Gate.KEY_HELD);
        cfg.routes.get("/api/updates/active", updates::active, Gate.KEY_HELD);
    }

    private void registerUpdateLookupRoutes(final JavalinConfig cfg) {
        // Before /api/updates/{id}: Javalin matches in registration order, and {id} takes a number.
        cfg.routes.get("/api/updates/available", updates::available, Gate.KEY_HELD);
        cfg.routes.get("/api/updates/{id}", updates::lookup, Gate.KEY_HELD);
    }

    private void registerUpdateAskRoute(final JavalinConfig cfg) {
        cfg.routes.post("/api/updates", updates::ask, Gate.KEY_FRESH);
    }

    private void registerUpdateCancelRoute(final JavalinConfig cfg) {
        cfg.routes.post("/api/updates/cancel", updates::cancel, Gate.KEY_FRESH);
    }

    private void registerCommandAndGameRoutes(final JavalinConfig cfg) {
        // A row in command_request, not a connection to a server: the interface holds none.
        cfg.routes.get("/api/commands/{id}", commands::outcome, Gate.KEY_HELD);

        // The same rows as above, asked for by what they act on rather than by command name.
        cfg.routes.get("/api/smp/track", games::track, Gate.KEY_HELD);
        cfg.routes.post("/api/smp/objective", games::completeObjective, Gate.KEY_FRESH);
        cfg.routes.post("/api/smp/milestone", games::unlockMilestone, Gate.KEY_FRESH);
        cfg.routes.get("/api/hunger-games/round", games::round, Gate.KEY_HELD);
        cfg.routes.post("/api/hunger-games/start", games::startRound, Gate.KEY_FRESH);
        cfg.routes.get("/api/announcements", announcements::recent, Gate.KEY_HELD);
        cfg.routes.post("/api/announcements", announcements::send, Gate.KEY_FRESH);
    }

    private void registerConfigRoutes(final JavalinConfig cfg) {
        cfg.routes.get("/api/config", workerProxy::configRoot, Gate.KEY_HELD);
        cfg.routes.get("/api/config/<file>", workerProxy::configFile, Gate.KEY_HELD);
        cfg.routes.put("/api/config/<file>", workerProxy::saveConfigFile, Gate.KEY_FRESH);
        cfg.routes.put("/api/config-raw/<file>", workerProxy::saveConfigRaw, Gate.KEY_FRESH);
        cfg.routes.get("/api/messages", workerProxy::messagesRoot, Gate.KEY_HELD);
        cfg.routes.get("/api/messages/<bundle>", workerProxy::messageBundle, Gate.KEY_HELD);
        cfg.routes.put("/api/messages/<bundle>", workerProxy::saveMessageBundle, Gate.KEY_FRESH);
        // Only this side knows which admin is asking, so the example values are answered here.
        cfg.routes.get("/api/message-examples", profile::messageExamples, Gate.KEY_HELD);
    }

    private void registerDiscordAndSettingsRoutes(final JavalinConfig cfg) {
        // An unreachable Discord answers available: false and a typed id, never a failure.
        cfg.routes.get("/api/discord/roles", guild::roles, Gate.KEY_HELD);
        cfg.routes.get("/api/discord/channels", guild::channels, Gate.KEY_HELD);
        cfg.routes.get("/api/settings", settings::get, Gate.KEY_HELD);
    }

    private void registerRosterRoutes(final JavalinConfig cfg) {
        cfg.routes.get("/api/people", roster::people, Gate.KEY_HELD);
        cfg.routes.get("/api/people/{id}/grants", roster::grants, Gate.KEY_HELD);
        cfg.routes.get("/api/payments", roster::payments, Gate.KEY_HELD);
        cfg.routes.get("/api/payments/open", roster::openPayments, Gate.KEY_HELD);
        cfg.routes.get("/api/journal", roster::journal, Gate.KEY_HELD);
    }

    private void registerAccessRoutes(final JavalinConfig cfg) {
        // Each of these is one access_request row the bot carries out; see AccessApi.
        cfg.routes.post("/api/access/grant", access::grant, Gate.KEY_FRESH);
        cfg.routes.post("/api/access/revoke", access::revoke, Gate.KEY_FRESH);
        cfg.routes.post("/api/access/unlink", access::unlink, Gate.KEY_FRESH);
        cfg.routes.post("/api/access/settle", access::settle, Gate.KEY_FRESH);
        cfg.routes.post("/api/people/{id}/playtime", access::playtime, Gate.KEY_FRESH);
        cfg.routes.get("/api/access/requests/{id}", access::outcome, Gate.KEY_HELD);

        // Admin is decided here, not by the bot: AdminApi writes it directly.
        cfg.routes.post("/api/admins/grant", ctx -> adminApi().grant(ctx), Gate.KEY_FRESH);
        cfg.routes.post("/api/admins/revoke", ctx -> adminApi().revoke(ctx), Gate.KEY_FRESH);
        cfg.routes.post("/api/pack-exemptions/exempt", ctx -> packExemptionApi().exempt(ctx), Gate.KEY_FRESH);
        cfg.routes.post(
                "/api/pack-exemptions/enforce", ctx -> packExemptionApi().enforce(ctx), Gate.KEY_FRESH);
    }

    private void registerSeasonPhaseRoute(final JavalinConfig cfg) {
        cfg.routes.post("/api/season/phase", season::phase, Gate.KEY_FRESH);
    }

    private void registerSeasonDateRoute(final JavalinConfig cfg) {
        cfg.routes.post("/api/season/date", season::date, Gate.KEY_FRESH);
    }

    private void registerSeasonSummaryRoute(final JavalinConfig cfg) {
        cfg.routes.get("/api/season", season::summary, Gate.KEY_HELD);
    }

    private void registerFallbackRoutes(final JavalinConfig cfg) {
        // Registered last and greedy: without them an unmatched call falls through as a 500, not a 404.
        cfg.routes.get(
                "/api/<path>",
                ctx -> {
                    throw new NotFoundResponse("no such endpoint: " + ctx.path());
                },
                Gate.ANYONE);
        cfg.routes.get(
                "/auth/<path>",
                ctx -> {
                    throw new NotFoundResponse("no such endpoint: " + ctx.path());
                },
                Gate.ANYONE);
    }

    /** Who is asking, or the answer the filter would have given. */
    private Sessions.Session requireSession(final Context ctx) {
        return session(ctx).orElseThrow(() -> new UnauthorizedResponse("sign in first"));
    }

    private Optional<DiscordAuth.Account> account(final Context ctx) {
        return session(ctx).map(Sessions.Session::account);
    }

    /** This request's session, read once and then parked on the request attribute {@link #PARKED}. */
    private Optional<Sessions.Session> session(final Context ctx) {
        if (sessions == null) {
            return Optional.empty();
        }
        final Optional<Sessions.Session> parked = Optional.ofNullable(ctx.attribute(PARKED));
        if (parked.isPresent()) {
            return parked;
        }
        Optional<Sessions.Session> found = sessions().find(ctx.cookie(Sessions.COOKIE));
        // Re-read on every request: a revocation ends the session at its next request, not later.
        if (found.isPresent()
                && found.get().signedIn()
                && admins != null
                && !admins().isAdmin(found.get().signedInDiscordId())) {
            final int ended = sessions().endAllOf(found.get().signedInDiscordId());
            log.info(
                    "ended {} session(s) of {}: no longer an admin",
                    ended,
                    found.get().signedInDiscordId());
            found = Optional.empty();
        }
        found.ifPresent(session -> ctx.attribute(PARKED, session));
        return found;
    }

    /** A {@code limit} query parameter, with a default and a ceiling the caller cannot raise. */
    static int limit(final Context ctx, final int fallback, final int ceiling) {
        return Math.min(
                ceiling,
                Math.max(1, ctx.queryParamAsClass("limit", Integer.class).getOrDefault(fallback)));
    }

    /** A forwarded query string: {@code "?tail=..."}, or nothing at all when there was none. */
    static String forwardedQuery(final String query) {
        // A literal "?" + null becomes the string "?null", which the worker parses as a parameter.
        return query == null || query.isBlank() ? "" : "?" + query;
    }

    public void stop() {
        // Follows first: one closed after Jetty recycled its request sends Javalin's error handling round in a loop.
        heartbeats.shutdownNow();
        streams.shutdownNow();
        try {
            if (!streams.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("a log follow did not end within five seconds of the stop");
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (app != null) {
            app.stop();
        }
    }
}

package eu.nordtal.season.steward.web;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.DatabaseMessages;
import eu.nordtal.season.database.access.AdminTree;
import eu.nordtal.season.database.access.PackExemptions;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.command.CommandTreeStore;
import eu.nordtal.season.database.game.GameDataStore;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.metric.Metric;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.steward.WireJson;
import eu.nordtal.season.steward.alert.AlertMonitor;
import eu.nordtal.season.steward.alert.AlertPreferences;
import eu.nordtal.season.steward.alert.AlertRouter;
import eu.nordtal.season.steward.alert.Thresholds;
import eu.nordtal.season.steward.api.Caller;
import eu.nordtal.season.steward.api.StackApi;
import eu.nordtal.season.steward.auth.Credentials;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.auth.Sessions;
import eu.nordtal.season.steward.auth.WebAuthn;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.data.ExampleValues;
import eu.nordtal.season.steward.discord.DiscordApi;
import eu.nordtal.season.steward.discord.DiscordDirectory;
import eu.nordtal.season.steward.live.LiveFeed;
import eu.nordtal.season.steward.live.Topic;
import eu.nordtal.season.steward.push.PushSubscriptions;
import eu.nordtal.season.steward.push.WebPushSender;
import eu.nordtal.season.steward.texts.WebTexts;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinGson;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nordtal Steward, the web interface: every route the browser calls, behind one gate.
 *
 * Creating a container is not here; only steward-agent may.
 */
public final class Web {

    private static final Logger log = LoggerFactory.getLogger(Web.class);

    /** Where this request's session is parked once it has been read, so it is looked up once. */
    private static final String PARKED = "steward.session";

    /** How often expired rows are swept out of {@code steward_session}. */
    static final Duration SWEEP = Duration.ofHours(1);

    /** How long one touch of the security key covers; deliberately not configurable. */
    static final Duration STEP_UP = Duration.ofMinutes(5);

    /** The command that prints a fresh VAPID keypair for the {@code web} group, named in a refusal below. */
    public static final String GENERATE_VAPID_KEYS = "generate-vapid-keys";

    private final WebSpec config;

    /** Services, logs, the console, config files, the host, backups and plugins. */
    private final StackApi stack;

    /** Signed-in browsers; null only in a test that signs nobody in. */
    private final @Nullable Sessions sessions;

    private final SecondFactor secondFactor;

    private final @Nullable ExampleValues exampleValues;

    private final PushEndpoints push;

    /** Measures the stack and raises what changed; null only in a test without a database. */
    private final @Nullable AlertMonitor alertMonitor;

    /** Sends every raised alert on its way; null only in a test without a database. */
    private final @Nullable AlertRouter alertRouter;

    private final AlertRoutes alerts;

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

    private final AgentApi deployments;

    private final Updates updates;

    private final SeasonRoutes season;

    private final Profile profile;

    private final Gatekeeper gatekeeper;

    private final Metrics metrics;

    /** What the servers know of the game, which the pickers draw from. */
    private final GameDataRoutes gameData;

    /** The servers' command trees, which the console suggests from. */
    private final ConsoleCommands consoleCommands;

    /** The browser's one live stream, which every page's data follows. */
    private final LiveFeed live;

    /** The page's own texts, which the browser renders and an admin's override changes at once. */
    private final WebTexts texts = WebTexts.load();

    /** The database bundle in the network's languages: a refused write and a server's refusal. */
    private final Messages database;

    private final @Nullable MessageOverrideStore overrides;

    /** The channels whose signals can change what a page shows; see {@link #listen}. */
    private static final List<Channel> LIVE_CHANNELS = List.of(
            Channel.UPDATE,
            Channel.SERVER,
            Channel.BOT,
            Channel.PAYMENT,
            Channel.ADMIN,
            Channel.PHASE,
            Channel.SETTINGS,
            Channel.ALERT,
            Channel.GAME_DATA);

    /** How often {@link AlertMonitor#poll} reads the stack. */
    private static final Duration ALERT_POLL = Duration.ofSeconds(30);

    private final Scheduler scheduler;

    /** The alert routing, the failed runs and the alert poll, one at a time: they share what they last saw. */
    private final Executor alertLane;

    /** The session sweep and the alert poll, cancelled at {@link #stop()}. */
    private final List<Scheduler.Task> timed = new ArrayList<>();

    private Javalin app;

    /**
     * @param agentOffered whether an agent token is configured; without one no recreate button is drawn
     * @param languages the network's, which the database bundle is loaded in
     */
    public Web(
            final WebSpec config,
            final Supplier<Thresholds> thresholds,
            final DiscordAuth discord,
            final StackApi stack,
            final AgentClient agent,
            final boolean agentOffered,
            final @Nullable Data data,
            final Languages languages,
            final Clock clock,
            final Scheduler scheduler) {
        this.config = config;
        this.scheduler = scheduler;
        this.alertLane = scheduler.serial();
        this.database = Messages.load(
                Web.class.getClassLoader(),
                "messages/" + MessageSchema.bundle(DatabaseMessages.class),
                languages.locales());
        this.stack = stack;
        this.sessions = data == null ? null : new Sessions(data.dataSource(), Duration.ofDays(config.sessionDays()));
        final @Nullable Credentials localCredentials = data == null ? null : new Credentials(data.dataSource());
        final @Nullable WebAuthn localWebauthn = webAuthnOver(config, localCredentials);
        this.secondFactor =
                new SecondFactor(this::requireSession, data, localCredentials, this.sessions, localWebauthn, clock);
        this.gatekeeper = new Gatekeeper(this::session, this.secondFactor);
        this.metrics = new Metrics(data, clock);
        this.guild = new DiscordApi(new DiscordDirectory(config.discord(), DiscordAuth.DISCORD_API, clock));
        this.commands = new CommandApi(data, ctx -> account(ctx).orElseThrow(), database);
        this.games = new GameActions(data == null ? null : data.dataSource(), commands);
        this.announcements = new Announcements(data, ctx -> account(ctx).orElseThrow());
        this.access = new AccessApi(data, ctx -> account(ctx).orElseThrow());
        this.roster = new RosterRoutes(data);
        this.gameData = new GameDataRoutes(data == null ? null : GameDataStore.using(data.dataSource()));
        this.consoleCommands = new ConsoleCommands(data == null ? null : CommandTreeStore.using(data.dataSource()));
        this.settings = new Settings(config);
        final @Nullable AdminTree localAdmins = data == null ? null : AdminTree.using(data.dataSource());
        this.admins = localAdmins;
        this.adminApi = adminApiOf(data, localAdmins);
        this.packExemptionApi = packExemptionsOf(data);
        this.authFlow = new AuthFlow(config, discord, data, this.sessions, localAdmins);
        this.updates = new Updates(data, ctx -> account(ctx).orElseThrow());
        this.season = new SeasonRoutes(data, ctx -> account(ctx).orElseThrow());
        this.deployments = new AgentApi(agent, agentOffered);
        final @Nullable PushSubscriptions localPushSubscriptions =
                data == null ? null : new PushSubscriptions(data.dataSource());
        final @Nullable AlertPreferences localAlertPreferences =
                data == null ? null : new AlertPreferences(data.dataSource());
        this.exampleValues = data == null ? null : new ExampleValues(data.dataSource());
        this.profile = new Profile(
                this::requireSession,
                this::session,
                localCredentials,
                data,
                authFlow::whatIsMissing,
                localWebauthn,
                this.exampleValues);
        final com.interaso.webpush.@Nullable VapidKeys localVapidKeys = vapidKeysOf(config.webPush());
        final AlertWiring wiring =
                wireAlerts(config, thresholds, stack, data, localPushSubscriptions, localVapidKeys, clock);
        this.alertMonitor = wiring.monitor();
        this.alertRouter = wiring.router();
        this.alerts = new AlertRoutes(this::requireSession, wiring.monitor(), data, localAlertPreferences);
        this.push =
                new PushEndpoints(this::requireSession, data, localPushSubscriptions, wiring.router(), localVapidKeys);
        this.live = watchLive(data, clock);
        this.overrides = data == null ? null : MessageOverrideStore.using(data.dataSource());
    }

    private @Nullable AdminApi adminApiOf(final @Nullable Data data, final @Nullable AdminTree localAdmins) {
        return data == null
                ? null
                : new AdminApi(
                        Objects.requireNonNull(localAdmins),
                        data.audit(),
                        ctx -> account(ctx).orElseThrow());
    }

    private @Nullable PackExemptionApi packExemptionsOf(final @Nullable Data data) {
        return data == null
                ? null
                : new PackExemptionApi(
                        PackExemptions.using(data.dataSource()),
                        data.audit(),
                        ctx -> account(ctx).orElseThrow());
    }

    private static @Nullable WebAuthn webAuthnOver(final WebSpec config, final @Nullable Credentials credentials) {
        return credentials == null
                ? null
                : new WebAuthn(config.webauthn().relyingPartyId(), config.publicUrl(), credentials);
    }

    /** What each topic of the live stream reads: the same reads the routes answer with. */
    private LiveFeed watchLive(final @Nullable Data data, final Clock clock) {
        final LiveFeed live = new LiveFeed(Waiting.on(clock), scheduler);
        stack.watch(live);
        if (data == null) {
            return live;
        }
        live.watch(
                Topic.RUNS,
                () -> Arrays.asList(
                        data.updates().recent(20), data.updates().open().orElse(null)));
        live.watch(
                Topic.REQUESTS,
                () -> List.of(
                        data.smp().version(),
                        data.hungerGames().version(),
                        data.bot().version()));
        live.watch(Topic.JOURNAL, () -> data.audit().recent(1));
        live.watch(
                Topic.PEOPLE,
                () -> List.of(
                        data.access().people(500),
                        data.payments().recent(200),
                        data.payments().allOpen()));
        live.watch(Topic.SEASON, season::read);
        live.watch(Topic.GAMES, () -> List.of(games.readTrack(), games.readRound()));
        live.watch(
                Topic.METRICS,
                () -> data.metrics()
                        .range(
                                "host",
                                Metric.LOAD1.key(),
                                clock.instant().minus(Duration.ofMinutes(5)),
                                clock.instant()));
        live.watch(Topic.ALERTS, alerts::read);
        live.watch(Topic.GAME_DATA, gameData::changes);
        return live;
    }

    private record AlertWiring(
            @Nullable AlertMonitor monitor, @Nullable AlertRouter router) {}

    /** The one alert path: measured here, raised as rows, routed to push and the admin channel. */
    private AlertWiring wireAlerts(
            final WebSpec config,
            final Supplier<Thresholds> thresholds,
            final StackApi stack,
            final @Nullable Data data,
            final @Nullable PushSubscriptions localPushSubscriptions,
            final com.interaso.webpush.@Nullable VapidKeys localVapidKeys,
            final Clock clock) {
        if (data == null) {
            return new AlertWiring(null, null);
        }
        final AlertBook book = AlertBook.using(data.dataSource());
        final AdminTree tree = Objects.requireNonNull(admins);
        final AlertRouter router = new AlertRouter(
                book,
                new AlertPreferences(data.dataSource()),
                () -> tree.admins().stream().map(AdminTree.Admin::discordId).toList(),
                data.bot(),
                localPushSubscriptions,
                localVapidKeys == null
                        ? null
                        : new WebPushSender(config.webPush().subject(), localVapidKeys),
                config.publicUrl(),
                texts.messages());
        final AlertMonitor monitor = new AlertMonitor(stack::stackReading, thresholds, book, data.updates(), clock);
        return new AlertWiring(monitor, router);
    }

    /**
     * Routes alerts and raises failed runs in the alert lane whenever the hub rings; before the hub's start.
     *
     * Each pass reads everything again, so a missed or doubled signal costs nothing.
     */
    public void listen(final SignalHub hub) {
        final MessageOverrideStore store = overrides;
        if (store != null) {
            store.follow(texts.messages(), hub);
            store.follow(database, hub);
        }
        final AlertRouter router = alertRouter;
        final AlertMonitor monitor = alertMonitor;
        if (router != null) {
            hub.on(Channel.ALERT, "alert routing", () -> alertLane.execute(router::route));
        }
        if (monitor != null) {
            hub.on(Channel.UPDATE, "failed runs", () -> alertLane.execute(monitor::runs));
        }
        // Not SMP: play moves the track many times a minute, and the hub's minute covers it.
        for (final Channel channel : LIVE_CHANNELS) {
            hub.on(channel, "the live stream", live::ring);
        }
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
    private static com.interaso.webpush.@Nullable VapidKeys vapidKeysOf(final WebSpec.WebPushSpec webPush) {
        final String publicKey = webPush.publicKey();
        final String privateKey = webPush.privateKey();
        if (publicKey.isBlank() && privateKey.isBlank()) {
            return null;
        }
        if (publicKey.isBlank() || privateKey.isBlank()) {
            throw new IllegalArgumentException("web-push has only one of public-key/private-key set"
                    + " - both or neither. Run `steward " + GENERATE_VAPID_KEYS + "` and paste"
                    + " both lines it prints into the web group.");
        }
        return com.interaso.webpush.VapidKeys.create(publicKey, privateKey);
    }

    public Javalin start(final int port) {
        app = Javalin.create(cfg -> {
                    configureFrontend(cfg);
                    registerAuthCore(cfg);
                    registerLiveRoute(cfg);
                    registerSecondFactorAndKeys(cfg);
                    registerWebPushRoutes(cfg);
                    // Before the update routes: its /api/updates/available must come before /api/updates/{id}.
                    stack.register(cfg, caller());
                    registerAgentRoutes(cfg);
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
                    ErrorHandlers.install(cfg, texts.messages(), database);
                })
                .start(port);

        if (sessions != null) {
            // An expired session is refused by the lookup regardless; the sweep only tidies.
            timed.add(scheduler.every(Duration.ZERO, SWEEP, authFlow::sweepSessions));
        }
        final AlertMonitor monitor = alertMonitor;
        if (monitor != null) {
            timed.add(scheduler.every(Duration.ZERO, ALERT_POLL, () -> alertLane.execute(monitor::poll)));
        }
        live.start();
        authFlow.whatIsMissing()
                .ifPresent(missing ->
                        log.warn("Nobody can sign in yet: {} is empty. Everything else is running.", missing));
        log.info("Nordtal Steward is on {} - public address {}", port, config.publicUrl());
        return app;
    }

    private void configureFrontend(final JavalinConfig cfg) {
        cfg.jsonMapper(new JavalinGson(WireJson.gson(), true));
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
        cfg.routes.get("/api/health", this::health, Gate.ANYONE);
        // `guard` reads `ctx.routeRoles()` per HTTP method, so HEAD needs its own registration too.
        cfg.routes.head("/api/health", this::health, Gate.ANYONE);

        cfg.routes.get("/api/me", profile::whoAmI, Gate.ANYONE);
        // Before signing in too: the sign-in page reads its words from it.
        cfg.routes.get("/api/texts", texts::serve, Gate.ANYONE);

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

    /** One stream for every page: a topic and its new version whenever what a route answers has changed. */
    private void registerLiveRoute(final JavalinConfig cfg) {
        cfg.routes.sse(
                "/api/live",
                client -> {
                    final Caller caller = caller();
                    if (!caller.stillSignedIn(client.ctx())) {
                        client.close();
                        return;
                    }
                    live.serve(client, () -> caller.stillSignedIn(client.ctx()));
                },
                Gate.KEY_HELD);
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
        cfg.routes.post("/api/web-push/test", push::test, Gate.KEY_FRESH);

        // Choosing a channel for oneself is a write like any other.
        cfg.routes.get("/api/alerts", alerts::current, Gate.KEY_HELD);
        cfg.routes.get("/api/alerts/preferences", alerts::preferences, Gate.KEY_HELD);
        cfg.routes.put("/api/alerts/preferences", alerts::setPreference, Gate.KEY_FRESH);
    }

    private void registerAgentRoutes(final JavalinConfig cfg) {
        // Not the update door either: this recreates one container, and only the agent may.
        cfg.routes.get("/api/agent", deployments::state, Gate.KEY_HELD);
    }

    private void registerMetricsRoute(final JavalinConfig cfg) {
        cfg.routes.get("/api/metrics", metrics::range, Gate.KEY_HELD);
    }

    private void registerUpdateListRoutes(final JavalinConfig cfg) {
        cfg.routes.get("/api/updates", updates::list, Gate.KEY_HELD);
        cfg.routes.get("/api/updates/active", updates::active, Gate.KEY_HELD);
    }

    private void registerUpdateLookupRoutes(final JavalinConfig cfg) {
        cfg.routes.get("/api/updates/{id}", updates::lookup, Gate.KEY_HELD);
    }

    private void registerUpdateAskRoute(final JavalinConfig cfg) {
        cfg.routes.post("/api/updates", updates::ask, Gate.KEY_FRESH);
    }

    private void registerUpdateCancelRoute(final JavalinConfig cfg) {
        cfg.routes.post("/api/updates/cancel", updates::cancel, Gate.KEY_FRESH);
    }

    private void registerCommandAndGameRoutes(final JavalinConfig cfg) {
        // A request in a server's inbox, not a connection to a server: the interface holds none.
        cfg.routes.get("/api/commands/{id}", commands::outcome, Gate.KEY_HELD);

        // The same rows as above, asked for by what they act on rather than by command name.
        cfg.routes.get("/api/smp/track", games::track, Gate.KEY_HELD);
        cfg.routes.post("/api/smp/objective", games::completeObjective, Gate.KEY_FRESH);
        cfg.routes.post("/api/smp/milestone", games::unlockMilestone, Gate.KEY_FRESH);
        cfg.routes.get("/api/hunger-games/round", games::round, Gate.KEY_HELD);
        cfg.routes.post("/api/hunger-games/start", games::startRound, Gate.KEY_FRESH);
        cfg.routes.post("/api/announcements", announcements::send, Gate.KEY_FRESH);
        // A text an admin is trying, shown to them alone by whichever process shows its key.
        cfg.routes.post(
                "/api/message-preview",
                ctx -> GameActions.answer(ctx, commands.preview(ctx, stack.messagePreview(ctx))),
                Gate.KEY_FRESH);
    }

    private void registerConfigRoutes(final JavalinConfig cfg) {
        // The example values depend on which admin is asking.
        cfg.routes.get("/api/message-examples", profile::messageExamples, Gate.KEY_HELD);
    }

    private void registerDiscordAndSettingsRoutes(final JavalinConfig cfg) {
        // An unreachable Discord answers available: false and a typed id, never a failure.
        cfg.routes.get("/api/discord/channels", guild::channels, Gate.KEY_HELD);
        cfg.routes.get("/api/settings", settings::get, Gate.KEY_HELD);
        cfg.routes.get("/api/game-data", gameData::read, Gate.KEY_HELD);
        cfg.routes.get("/api/game-data/{version}/icons.png", gameData::icons, Gate.KEY_HELD);
        cfg.routes.get("/api/services/{name}/commands", consoleCommands::tree, Gate.KEY_HELD);
    }

    private void registerRosterRoutes(final JavalinConfig cfg) {
        cfg.routes.get("/api/people", roster::people, Gate.KEY_HELD);
        cfg.routes.get("/api/people/{id}/grants", roster::grants, Gate.KEY_HELD);
        cfg.routes.get("/api/payments", roster::payments, Gate.KEY_HELD);
        cfg.routes.get("/api/payments/open", roster::openPayments, Gate.KEY_HELD);
        cfg.routes.get("/api/journal", roster::journal, Gate.KEY_HELD);
    }

    private void registerAccessRoutes(final JavalinConfig cfg) {
        // Each of these is one request in the bot's inbox, which the bot carries out; see AccessApi.
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

    /** Open to anyone, so a healthcheck needs no session: this process answers, and whether steward-agent does. */
    private void health(final Context ctx) {
        ctx.json(java.util.Map.of("status", "ok", "agent", stack.agentReachable()));
    }

    /** Who is asking, as the stack routes need it: the actor a row records, and whether the session still holds. */
    private Caller caller() {
        return new Caller() {
            @Override
            public Actor actor(final Context ctx) {
                return account(ctx)
                        .orElseThrow(() -> new UnauthorizedResponse("sign in first"))
                        .actor();
            }

            @Override
            public boolean stillSignedIn(final Context ctx) {
                return sessions != null
                        && sessions.find(ctx.cookie(Sessions.COOKIE)).isPresent();
            }
        };
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

    /** Ends every log follow before Jetty, since a follow closed after Jetty stopped loops in its error handling. */
    public void stop() {
        timed.forEach(Scheduler.Task::cancel);
        live.close();
        stack.close();
        if (app != null) {
            app.stop();
        }
    }
}

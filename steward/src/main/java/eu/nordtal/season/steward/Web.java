package eu.nordtal.season.steward;

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
import eu.nordtal.season.database.guild.GuildChannels;
import eu.nordtal.season.database.message.MessageOverrideStore;
import eu.nordtal.season.database.metric.Metric;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.internalapi.agent.AgentClient;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.steward.access.AccessApi;
import eu.nordtal.season.steward.access.AccessRoutes;
import eu.nordtal.season.steward.access.PackExemptionApi;
import eu.nordtal.season.steward.access.RosterRoutes;
import eu.nordtal.season.steward.alert.AlertMonitor;
import eu.nordtal.season.steward.alert.AlertPreferences;
import eu.nordtal.season.steward.alert.AlertRouter;
import eu.nordtal.season.steward.alert.AlertRoutes;
import eu.nordtal.season.steward.alert.PushEndpoints;
import eu.nordtal.season.steward.alert.Thresholds;
import eu.nordtal.season.steward.auth.AdminApi;
import eu.nordtal.season.steward.auth.AuthFlow;
import eu.nordtal.season.steward.auth.AuthRoutes;
import eu.nordtal.season.steward.auth.AuthenticatorApp;
import eu.nordtal.season.steward.auth.Authenticators;
import eu.nordtal.season.steward.auth.Credentials;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.auth.Gate;
import eu.nordtal.season.steward.auth.Gatekeeper;
import eu.nordtal.season.steward.auth.Profile;
import eu.nordtal.season.steward.auth.SecondFactor;
import eu.nordtal.season.steward.auth.SecretBox;
import eu.nordtal.season.steward.auth.Sessions;
import eu.nordtal.season.steward.auth.WebAuthn;
import eu.nordtal.season.steward.config.WebSpec;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.data.ExampleValues;
import eu.nordtal.season.steward.discord.DiscordApi;
import eu.nordtal.season.steward.game.Announcements;
import eu.nordtal.season.steward.game.CommandApi;
import eu.nordtal.season.steward.game.ConsoleCommands;
import eu.nordtal.season.steward.game.GameActions;
import eu.nordtal.season.steward.game.GameDataRoutes;
import eu.nordtal.season.steward.game.GameRoutes;
import eu.nordtal.season.steward.game.Inventories;
import eu.nordtal.season.steward.journal.Journaling;
import eu.nordtal.season.steward.journal.Unjournaled;
import eu.nordtal.season.steward.live.LiveFeed;
import eu.nordtal.season.steward.live.Topic;
import eu.nordtal.season.steward.messages.DocumentsApi;
import eu.nordtal.season.steward.metric.Metrics;
import eu.nordtal.season.steward.push.PushSubscriptions;
import eu.nordtal.season.steward.push.WebPushSender;
import eu.nordtal.season.steward.season.SeasonRoutes;
import eu.nordtal.season.steward.settings.Settings;
import eu.nordtal.season.steward.stack.AgentApi;
import eu.nordtal.season.steward.stack.Caller;
import eu.nordtal.season.steward.stack.StackApi;
import eu.nordtal.season.steward.texts.WebTexts;
import eu.nordtal.season.steward.update.Updates;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The web interface: every route the browser calls, behind one gate.
 *
 * This class creates no container. Only steward-agent does.
 */
public final class Web {

    private static final Logger log = LoggerFactory.getLogger(Web.class);

    /** The request attribute that holds the session once it is read. */
    private static final String PARKED = "steward.session";

    /** The command that prints a new VAPID keypair for the {@code web} group, named in a refusal. */
    public static final String GENERATE_VAPID_KEYS = "generate-vapid-keys";

    private final WebSpec config;

    private final Data data;

    private final StackApi stack;

    private final Sessions sessions;

    private final SecondFactor secondFactor;
    private final AuthenticatorApp authenticatorApp;

    private final PushEndpoints push;

    /** Measures the stack and raises what changed. */
    private final AlertMonitor alertMonitor;

    /** Sends every raised alert. */
    private final AlertRouter alertRouter;

    private final AlertRoutes alerts;

    /** The names of the guild's channels as discord-bot published them. */
    private final DiscordApi guild;

    private final CommandApi commands;

    private final GameActions games;
    private final Inventories inventories;
    private final Announcements announcements;

    private final AccessApi access;

    private final RosterRoutes roster;

    private final Settings settings;
    /** Decides who may sign in. */
    private final AdminTree admins;

    private final AdminApi adminApi;

    private final PackExemptionApi packExemptionApi;
    private final AuthFlow authFlow;

    private final AgentApi deployments;

    private final Updates updates;

    private final SeasonRoutes season;

    private final Profile profile;

    private final Gatekeeper gatekeeper;

    private final Journaling journal;

    private final Metrics metrics;

    /** The game data of the servers, which the pickers draw from. */
    private final GameDataRoutes gameData;

    /** The servers' command trees, which the console suggests from. */
    private final ConsoleCommands consoleCommands;

    /** The one live stream that every page follows. */
    private final LiveFeed live;

    /** The page's own texts. An admin's override changes them at once. */
    private final WebTexts texts = WebTexts.load();

    /** The database bundle in the network's languages. It words a refused write and a server's refusal. */
    private final Messages database;

    private final MessageOverrideStore overrides;

    /** The channels whose signals can change what a page shows. See {@link #listen}. */
    private static final List<Channel> LIVE_CHANNELS = List.of(
            Channel.UPDATE,
            Channel.SERVER,
            Channel.BOT,
            Channel.PAYMENT,
            Channel.ADMIN,
            Channel.PHASE,
            Channel.SETTINGS,
            Channel.ALERT,
            Channel.GAME_DATA,
            Channel.COMMAND_TREE);

    /** How often {@link AlertMonitor#poll} reads the stack. */
    private static final Duration ALERT_POLL = Duration.ofSeconds(30);

    private final Scheduler scheduler;

    /**
     * The lane that runs the alert routing, the failed runs and the alert poll one at a time, since they share state.
     */
    private final Executor alertLane;

    /** The session sweep and the alert poll, which {@link #stop()} cancels. */
    private final List<Scheduler.Task> timed = new ArrayList<>();

    private Javalin app;

    /**
     * @param agentOffered whether an agent token is configured. Without one the page draws no recreate button
     * @param languages the network's languages, which the database bundle is loaded in
     */
    public Web(
            final WebSpec config,
            final Supplier<Thresholds> thresholds,
            final DiscordAuth discord,
            final StackApi stack,
            final AgentClient agent,
            final boolean agentOffered,
            final Data data,
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
        this.data = data;
        this.sessions = new Sessions(data.dataSource(), Duration.ofDays(config.sessionDays()));
        final Credentials localCredentials = new Credentials(data.dataSource());
        final WebAuthn localWebauthn =
                new WebAuthn(config.webauthn().relyingPartyId(), config.publicUrl(), localCredentials);
        final Authenticators localApps = new Authenticators(
                data.dataSource(), SecretBox.of(config.totpKey()).orElse(null), clock);
        this.secondFactor = new SecondFactor(
                this::requireSession, localCredentials, this.sessions, localWebauthn, localApps, clock);
        this.authenticatorApp = new AuthenticatorApp(this::requireSession, secondFactor, localApps, this.sessions);
        this.gatekeeper = new Gatekeeper(this::session, this.secondFactor);
        this.journal = new Journaling(data.audit(), ctx -> account(ctx).map(DiscordAuth.Account::actor));
        this.metrics = new Metrics(data, clock);
        this.guild = new DiscordApi(config.discord(), GuildChannels.using(data.dataSource()));
        this.commands = new CommandApi(data, ctx -> account(ctx).orElseThrow(), database);
        this.games = new GameActions(data.dataSource(), commands);
        this.inventories = new Inventories(data, games, ctx -> account(ctx).orElseThrow(), Waiting.on(clock));
        this.announcements = new Announcements(data, ctx -> account(ctx).orElseThrow());
        this.access = new AccessApi(data, ctx -> account(ctx).orElseThrow());
        this.roster = new RosterRoutes(data, texts.messages());
        this.gameData = new GameDataRoutes(GameDataStore.using(data.dataSource()));
        this.consoleCommands = new ConsoleCommands(CommandTreeStore.using(data.dataSource()));
        this.settings = new Settings(config);
        this.admins = AdminTree.using(data.dataSource());
        this.adminApi = new AdminApi(admins, ctx -> account(ctx).orElseThrow());
        this.packExemptionApi = new PackExemptionApi(
                PackExemptions.using(data.dataSource()), ctx -> account(ctx).orElseThrow());
        this.authFlow = new AuthFlow(config, discord, data, this.sessions, admins);
        this.updates = new Updates(data, ctx -> account(ctx).orElseThrow(), secondFactor::requireStepUp);
        this.season = new SeasonRoutes(data, ctx -> account(ctx).orElseThrow());
        this.deployments = new AgentApi(agent, agentOffered);
        final PushSubscriptions localPushSubscriptions = new PushSubscriptions(data.dataSource());
        this.profile = new Profile(
                this::requireSession,
                this::session,
                localCredentials,
                localApps,
                data,
                authFlow::whatIsMissing,
                localWebauthn,
                new ExampleValues(data.dataSource()));
        final com.interaso.webpush.@Nullable VapidKeys localVapidKeys = WebPushSender.keysOf(config.webPush());
        final AlertWiring wiring =
                wireAlerts(config, thresholds, stack, data, localPushSubscriptions, localVapidKeys, clock);
        this.alertMonitor = wiring.monitor();
        this.alertRouter = wiring.router();
        this.alerts = new AlertRoutes(this::requireSession, wiring.monitor(), wiring.preferences());
        this.push = new PushEndpoints(this::requireSession, localPushSubscriptions, wiring.router(), localVapidKeys);
        this.live = watchLive(data, clock);
        this.overrides = MessageOverrideStore.using(data.dataSource());
    }

    /** Maps each topic of the live stream to the reads that its routes answer with. */
    private LiveFeed watchLive(final Data data, final Clock clock) {
        final LiveFeed live = new LiveFeed(Waiting.on(clock), scheduler);
        stack.watch(live);
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
        live.watch(Topic.JOURNAL, () -> data.journal().page(List.of(), null, 1));
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
        live.watch(Topic.COMMANDS, consoleCommands::published);
        return live;
    }

    private record AlertWiring(AlertMonitor monitor, AlertRouter router, AlertPreferences preferences) {}

    /** Measures the stack, raises alerts as rows and routes them to push and the admin channel. */
    private AlertWiring wireAlerts(
            final WebSpec config,
            final Supplier<Thresholds> thresholds,
            final StackApi stack,
            final Data data,
            final PushSubscriptions localPushSubscriptions,
            final com.interaso.webpush.@Nullable VapidKeys localVapidKeys,
            final Clock clock) {
        final AlertBook book = AlertBook.using(data.dataSource());
        final AdminTree tree = admins;
        final AlertPreferences preferences = new AlertPreferences(data.dataSource());
        final AlertRouter router = new AlertRouter(
                book,
                preferences,
                () -> tree.admins().stream().map(AdminTree.Admin::discordId).toList(),
                data.bot(),
                localPushSubscriptions,
                localVapidKeys == null
                        ? null
                        : new WebPushSender(config.webPush().subject(), localVapidKeys),
                texts.messages());
        final AlertMonitor monitor = new AlertMonitor(stack::stackReading, thresholds, book, data.updates(), clock);
        return new AlertWiring(monitor, router, preferences);
    }

    /**
     * Routes alerts and raises failed runs in the alert lane whenever the hub rings, before the hub's start.
     *
     * Each pass reads everything again, so a missed or doubled signal changes nothing.
     */
    public void listen(final SignalHub hub) {
        overrides.follow(texts.messages(), hub);
        overrides.follow(database, hub);
        hub.on(Channel.ALERT, "alert routing", () -> alertLane.execute(alertRouter::route));
        hub.on(Channel.UPDATE, "failed runs", () -> alertLane.execute(alertMonitor::runs));
        for (final Channel channel : LIVE_CHANNELS) {
            hub.on(channel, "the live stream", live::ring);
        }
    }

    public Javalin start(final int port) {
        app = Javalin.create(cfg -> {
                    // Javalin's default refuses a body above a megabyte, and a document's image may be larger.
                    cfg.http.maxRequestSize = DocumentsApi.IMAGE_BYTES;
                    configureFrontend(cfg);
                    registerOpenRoutes(cfg);
                    AuthRoutes.register(cfg, authFlow, secondFactor, authenticatorApp, profile, adminApi);
                    registerLiveRoute(cfg);
                    alerts.register(cfg, push);
                    // Registers before the update routes: /api/updates/available must precede /api/updates/{id}.
                    stack.register(cfg, caller());
                    deployments.register(cfg);
                    metrics.register(cfg);
                    updates.register(cfg);
                    GameRoutes.register(cfg, commands, games, inventories, announcements, gameData, consoleCommands);
                    registerPreviewRoutes(cfg);
                    guild.register(cfg);
                    settings.register(cfg);
                    AccessRoutes.register(cfg, roster, access, packExemptionApi);
                    season.register(cfg);
                    registerFallbackRoutes(cfg);
                    ErrorHandlers.install(cfg, texts.messages(), database);
                })
                .start(port);

        timed.add(scheduler.every(Duration.ZERO, AuthFlow.SWEEP, authFlow::sweepSessions));
        timed.add(scheduler.every(Duration.ZERO, ALERT_POLL, () -> alertLane.execute(alertMonitor::poll)));
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

        // Gradle packs the built frontend into the jar under /web.
        cfg.staticFiles.add(staticFiles -> {
            staticFiles.hostedPath = "/";
            staticFiles.directory = "/web";
            staticFiles.location = Location.CLASSPATH;
            // `guard` reads no Gate off static files, so this sets the roles.
            staticFiles.roles = Set.of(Gate.ANYONE);
        });
        // Unmatched paths serve index.html, which the client-side router handles.
        cfg.spaRoot.addFile("/", "/web/index.html", Location.CLASSPATH);

        // This hook sees both static files and the single-page fallback.
        cfg.routes.after(Gatekeeper::cacheHeaders);
    }

    /** Registers the routes open before sign-in and the filters that guard and journal every matched route. */
    private void registerOpenRoutes(final JavalinConfig cfg) {
        cfg.routes.get("/api/health", this::health, Gate.ANYONE);
        // `guard` reads `ctx.routeRoles()` per HTTP method, so HEAD needs its own registration.
        cfg.routes.head("/api/health", this::health, Gate.ANYONE);
        // Open before sign-in, because the sign-in page reads its words from it.
        cfg.routes.get("/api/texts", texts::serve, Gate.ANYONE);

        // Checks the Gate of every matched endpoint before it runs; see Gate.
        cfg.routes.beforeMatched(gatekeeper::guard);
        // Writes the journal line of a route marked Journaled after every matched endpoint; see Journaling.
        cfg.routes.afterMatched(journal::write);
    }

    /** Registers the live stream: a topic and its new version whenever the answer of a route changes. */
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

    /** Sends a drafted text or document to the admin alone: stack drafts it, a game server or the bot shows it. */
    private void registerPreviewRoutes(final JavalinConfig cfg) {
        cfg.routes.post(
                "/api/message-preview",
                ctx -> GameActions.answer(ctx, commands.preview(ctx, stack.messagePreview(ctx))),
                Gate.KEY_HELD,
                Unjournaled.SHOWN_TO_ONESELF);
        cfg.routes.post(
                "/api/document-preview",
                ctx -> GameActions.answer(ctx, commands.preview(ctx, stack.documentPreview(ctx))),
                Gate.KEY_HELD,
                Unjournaled.SHOWN_TO_ONESELF);
    }

    private void registerFallbackRoutes(final JavalinConfig cfg) {
        // These routes register last. Without them an unmatched call returns 500 instead of 404.
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

    /** Answers 200 when the database and steward-agent answer and 503 otherwise. Anyone may call it. */
    private void health(final Context ctx) {
        final Health health = new Health(data.answers(), stack.agentReachable());
        ctx.status(health.database() && health.agent() ? 200 : 503).json(health);
    }

    /** The body of {@code GET /api/health}, which anyone may call. It holds these two booleans only. */
    public record Health(boolean database, boolean agent) {}

    /** Returns who is asking: the actor a row records, and whether the session still holds. */
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
                return sessions.find(ctx.cookie(Sessions.COOKIE)).isPresent();
            }
        };
    }

    /** Returns the session of the request, or throws the 401 that the filter would give. */
    private Sessions.Session requireSession(final Context ctx) {
        return session(ctx).orElseThrow(() -> new UnauthorizedResponse("sign in first"));
    }

    private Optional<DiscordAuth.Account> account(final Context ctx) {
        return session(ctx).map(Sessions.Session::account);
    }

    /** Returns the session of this request, read once and kept in the request attribute {@link #PARKED}. */
    private Optional<Sessions.Session> session(final Context ctx) {
        final Optional<Sessions.Session> parked = Optional.ofNullable(ctx.attribute(PARKED));
        if (parked.isPresent()) {
            return parked;
        }
        Optional<Sessions.Session> found = sessions.find(ctx.cookie(Sessions.COOKIE));
        // The admin check runs on every request, so a revocation ends the session at its next request.
        if (found.isPresent()
                && found.get().signedIn()
                && !admins.isAdmin(found.get().signedInDiscordId())) {
            final int ended = sessions.endAllOf(found.get().signedInDiscordId());
            log.info(
                    "ended {} session(s) of {}: no longer an admin",
                    ended,
                    found.get().signedInDiscordId());
            found = Optional.empty();
        }
        found.ifPresent(session -> ctx.attribute(PARKED, session));
        return found;
    }

    /** Ends every log follow before Jetty stops, because a follow closed afterwards loops in its error handling. */
    public void stop() {
        timed.forEach(Scheduler.Task::cancel);
        live.close();
        stack.close();
        if (app != null) {
            app.stop();
        }
    }
}

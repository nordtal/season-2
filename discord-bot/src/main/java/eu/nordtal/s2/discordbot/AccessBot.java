package eu.nordtal.s2.discordbot;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.access.AccessDirectory;
import eu.nordtal.s2.database.access.AdminTree;
import eu.nordtal.s2.database.network.SnapshotDirectory;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.database.notify.SignalHub;
import eu.nordtal.s2.database.payment.PaymentGateway;
import eu.nordtal.s2.database.payment.PaymentRequests;
import eu.nordtal.s2.database.phase.PhaseDirectory;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import eu.nordtal.s2.discordbot.access.discord.LinkFlow;
import eu.nordtal.s2.discordbot.access.discord.ManagedMessages;
import eu.nordtal.s2.discordbot.access.discord.PurchaseFlow;
import eu.nordtal.s2.discordbot.access.discord.RedemptionLimit;
import eu.nordtal.s2.discordbot.access.payment.PaymentProcessor;
import eu.nordtal.s2.discordbot.access.payment.Purchases;
import eu.nordtal.s2.discordbot.access.payment.Tiers;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.BotSpec;
import eu.nordtal.s2.discordbot.config.Configs;
import eu.nordtal.s2.discordbot.config.Configured;
import eu.nordtal.s2.discordbot.config.DatabaseSpec;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.discordbot.discord.AccessInbox;
import eu.nordtal.s2.discordbot.discord.AdminRole;
import eu.nordtal.s2.discordbot.discord.BotAccessEffects;
import eu.nordtal.s2.discordbot.discord.GuildState;
import eu.nordtal.s2.discordbot.discord.UpdateFeed;
import eu.nordtal.s2.discordbot.hungergames.RegisterFlow;
import eu.nordtal.s2.discordbot.hungergames.RegisterMessages;
import eu.nordtal.s2.discordbot.hungergames.Teams;
import eu.nordtal.s2.discordbot.status.StatusChannels;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.MessageEnvironment;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

/**
 * Entry point and owner of the pool, the JDA session, the listeners and the timers.
 *
 * Starts configuration first, then the database and its {@link SchemaCheck}, then Discord, then everything else.
 */
@Slf4j
public class AccessBot implements AutoCloseable {

    /** The one clock of this process. */
    private final Clock clock = NetworkTime.clock();

    /** Classpath root of the message bundles, one {@code <tag>.properties} per language. */
    private static final String MESSAGE_ROOT = "messages/access";

    /** The name {@code {server.name}} reads in this process. */
    private static final String SERVICE = "discord-bot";

    private final Database database;
    private final AccessDirectory access;
    private final JDA jda;

    /** The bot's one {@code LISTEN} connection, since {@code LISTEN} is session state a pool would lose. */
    private final SignalHub signals;

    /** Bounds the liveness check and reconnect only; pgjdbc overrides it while waiting for notifications. */
    private static final int LISTENER_SOCKET_TIMEOUT_SECONDS = 30;

    /** Runs everything that blocks, since a gateway thread must acknowledge an interaction within three seconds. */
    private final ExecutorService worker = Executors.newFixedThreadPool(4, runnable -> {
        final Thread thread = new Thread(runnable, "access-bot-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "access-bot-timer");
        thread.setDaemon(true);
        return thread;
    });

    private record CoreServices(
            Languages languages,
            Messages messages,
            Messages sharedMessages,
            Tiers tiers,
            PaymentRequests requests,
            Purchases purchases) {}

    private record DiscordWiring(
            AdminLog admin,
            AccessRoles roles,
            PaymentProcessor processor,
            PurchaseFlow purchaseFlow,
            AdminRole adminRole,
            GuildState guildState,
            BotAccessEffects inboxEffects,
            eu.nordtal.s2.discordbot.announce.Announcements announcements) {}

    public AccessBot() throws InterruptedException, ConfigException {
        final DatabaseSpec databaseConfig = Configs.database().get();
        final BotSpec botConfig = Configs.bot().get();
        final AccessSpec accessConfig = Configs.access().get();

        this.database = Database.create(toDatabaseConfig(databaseConfig));
        this.database.jdbi().installPlugin(Jdbis.ids());

        boolean started = false;
        try {
            // The bot does not migrate; this refuses an unmigrated database by name.
            SchemaCheck.validate(database.dataSource());

            // Borrows the bot's pool; closing a borrowed pool is a no-op.
            this.access = AccessDirectory.using(database.dataSource(), clock);
            final PhaseDirectory phases = PhaseDirectory.using(database.dataSource(), clock);
            // steward-worker's inbox: the bot writes requests and reads answers, never updating them.
            final UpdateDirectory updates = UpdateDirectory.using(database.dataSource());

            final CoreServices core = loadCoreServices(accessConfig);
            this.jda = connectJda(botConfig);

            final DiscordWiring wiring = wireDiscord(jda, accessConfig, core, phases);
            publishAndReconcile(jda, core.languages(), core.tiers(), core.messages(), wiring);

            this.signals = finishStartup(databaseConfig, accessConfig, core, wiring, phases, updates);

            started = true;
            log.info("access-bot is up");
        } finally {
            if (!started) {
                database.close();
            }
        }
    }

    private SignalHub finishStartup(
            final DatabaseSpec databaseConfig,
            final AccessSpec accessConfig,
            final CoreServices core,
            final DiscordWiring wiring,
            final PhaseDirectory phases,
            final UpdateDirectory updates) {
        // After the guild state reconcile, so the first tick renames against a settled picture.
        final StatusChannels status = new StatusChannels(
                jda,
                core.languages(),
                core.messages(),
                phases,
                SnapshotDirectory.using(database.dataSource()),
                clock,
                wiring.announcements());

        // start() reads the table once so the feed begins at the last run.
        final UpdateFeed updateFeed =
                new UpdateFeed(updates, UpdateFeed.Board.of(wiring.admin()), core.messages(), clock);
        updateFeed.start();

        schedule(accessConfig, wiring.roles(), status, updateFeed);

        // Started last: it refreshes immediately on connect and touches JDA.
        final SignalHub hub = listen(
                databaseConfig,
                wiring.processor(),
                wiring.purchaseFlow(),
                new AccessInbox(
                        eu.nordtal.s2.database.access.AccessRequests.on(database.dataSource()),
                        wiring.inboxEffects(),
                        log),
                wiring.adminRole());

        // Last on purpose: a marker on disk means the constructor finished.
        final Readiness readiness = Readiness.onDefaultPath(clock, log::warn);
        repeat(guarded("readiness marker", readiness::refresh), 0, Readiness.BEAT.toSeconds(), TimeUnit.SECONDS);

        return hub;
    }

    private CoreServices loadCoreServices(final AccessSpec accessConfig) {
        final Languages languages = Languages.of(accessConfig);
        // :commands' shared bundle underneath this module's own; this module's keys win a collision.
        final Messages messages = Messages.load(
                        AccessBot.class.getClassLoader(),
                        java.util.List.of("messages/commands", MESSAGE_ROOT),
                        Configs.messagesDirectory(),
                        languages.locales())
                .within(MessageEnvironment.of(SERVICE));
        messages.unknownOverrideKeys()
                .forEach(key -> log.warn(
                        "the message override names {}, which no bundle declares - it is stored"
                                + " and never used; check the spelling",
                        key));
        // The same files as one root, for remote answers: this module's keys are allowed Discord markdown.
        final Messages sharedMessages = Messages.load(
                        AccessBot.class.getClassLoader(),
                        "messages/commands",
                        Configs.messagesDirectory(),
                        languages.locales())
                .within(MessageEnvironment.of(SERVICE));
        final Tiers tiers = Tiers.of(accessConfig);

        // The bunq key lives in steward-worker and is read here as a row.
        Configured.report(accessConfig, PaymentGateway.state(database.jdbi()));
        final PaymentRequests requests = new PaymentRequests(database.jdbi());
        final Purchases purchases = new Purchases(requests, tiers, accessConfig);

        return new CoreServices(languages, messages, sharedMessages, tiers, requests, purchases);
    }

    private JDA connectJda(final BotSpec botConfig) throws InterruptedException {
        // GUILD_MEMBERS is privileged; without it both reconciles read nothing.
        final JDA connected = JDABuilder.createLight(botConfig.token())
                .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MODERATION)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .build()
                .awaitReady();
        connected
                .getPresence()
                .setPresence(
                        Activity.of(Activity.ActivityType.CUSTOM_STATUS, "It's that time of the year again..."), false);
        return connected;
    }

    private DiscordWiring wireDiscord(
            final JDA jda, final AccessSpec accessConfig, final CoreServices core, final PhaseDirectory phases) {
        final AdminLog admin = new AdminLog(jda, accessConfig, database.jdbi());
        // A period sold while season_phase.smp_start is NULL starts now rather than at the SMP opening.
        final SeasonStart seasonStart = new SeasonStart(phases, admin);
        final AccessRoles roles =
                new AccessRoles(jda, accessConfig, access, core.messages(), admin, database.jdbi(), clock);
        final PaymentProcessor processor = new PaymentProcessor(
                core.languages(),
                core.requests(),
                core.tiers(),
                access,
                roles,
                admin,
                core.messages(),
                jda,
                seasonStart);
        // A grant tree decided in Steward; the bot drops a branch when its admin leaves the guild, never grants.
        final AdminTree adminTree = AdminTree.using(database.dataSource());
        final GuildState guildState =
                new GuildState(jda, accessConfig, core.languages(), access, adminTree, database.jdbi());
        final AdminRole adminRole = new AdminRole(jda, accessConfig, adminTree, admin);
        final Teams teams = new Teams(database.jdbi());

        // Held because the payment seam finishes messages waiting for a link.
        final PurchaseFlow purchaseFlow = new PurchaseFlow(
                core.tiers(), core.purchases(), core.requests(), core.messages(), roles, admin, worker, clock);

        return finishWiring(
                jda,
                accessConfig,
                core,
                admin,
                seasonStart,
                roles,
                processor,
                guildState,
                adminRole,
                teams,
                purchaseFlow);
    }

    private DiscordWiring finishWiring(
            final JDA jda,
            final AccessSpec accessConfig,
            final CoreServices core,
            final AdminLog admin,
            final SeasonStart seasonStart,
            final AccessRoles roles,
            final PaymentProcessor processor,
            final GuildState guildState,
            final AdminRole adminRole,
            final Teams teams,
            final PurchaseFlow purchaseFlow) {
        jda.addEventListener(
                guildState,
                purchaseFlow,
                new LinkFlow(
                        access,
                        roles,
                        core.messages(),
                        admin,
                        new RedemptionLimit(accessConfig.linkCodeAttemptsPerHour(), clock),
                        worker),
                new RegisterFlow(jda, teams, core.messages(), worker));

        final BotAccessEffects inboxEffects = new BotAccessEffects(
                access, roles, core.requests(), admin, seasonStart, core.messages(), core.sharedMessages(), log);
        final eu.nordtal.s2.discordbot.announce.Announcements announcements =
                wireCommandInbox(jda, core.sharedMessages(), core.languages());

        final List<CommandData> commands = new ArrayList<>();
        // Only a player's own self-service is registered natively.
        commands.addAll(LinkFlow.commands());
        jda.updateCommands().addCommands(commands).queue();

        return new DiscordWiring(
                admin, roles, processor, purchaseFlow, adminRole, guildState, inboxEffects, announcements);
    }

    private eu.nordtal.s2.discordbot.announce.Announcements wireCommandInbox(
            final JDA jda, final Messages sharedMessages, final Languages languages) {
        // `announce <language> <text>` rows from the servers, posted verbatim.
        final eu.nordtal.s2.database.command.CommandRequests commandRequests =
                eu.nordtal.s2.database.command.CommandRequests.borrowing(database.dataSource());

        // Inline effects, because the inbox settles the row when the command returns.
        final eu.nordtal.s2.commands.remote.CommandInbox inbox = new eu.nordtal.s2.commands.remote.CommandInbox(
                eu.nordtal.s2.commands.Target.BOT,
                commandRequests,
                sharedMessages,
                eu.nordtal.s2.commands.remote.CommandInbox.AdminCheck.of(
                        access::admins, access::adminMinecraftAccounts),
                (message, failure) -> log.warn(message, failure));
        final eu.nordtal.s2.discordbot.announce.Announcements announcements =
                new eu.nordtal.s2.discordbot.announce.Announcements(jda, languages, Runnable::run, log);
        eu.nordtal.s2.commands.announce.AnnounceCommands.all()
                .forEach(command -> inbox.register(command, announcements));
        // A drain blocks on JDA REST and the database, so it runs on worker, not on the timer thread.
        repeat(() -> worker.execute(inbox::drain), 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        return announcements;
    }

    private void publishAndReconcile(
            final JDA jda,
            final Languages languages,
            final Tiers tiers,
            final Messages messages,
            final DiscordWiring wiring) {
        new ManagedMessages(jda, languages, tiers, messages, database.jdbi()).publishAll();
        new RegisterMessages(jda, languages, messages, database.jdbi()).publishAll();
        wiring.guildState().reconcile();
        wiring.roles().reconcile();
        wiring.adminRole().reconcile();
    }

    /** Starts the recurring timers, each guarded because the scheduler silently cancels a task that throws. */
    private void schedule(
            final AccessSpec config,
            final AccessRoles roles,
            final StatusChannels status,
            final UpdateFeed updateFeed) {
        final int reconcile = config.roleReconcileIntervalMinutes();
        repeat(guarded("role reconcile", roles::reconcile), reconcile, reconcile, TimeUnit.MINUTES);

        // An hour late at most, against a three-day lead.
        repeat(
                guarded("expiry sweep", () -> {
                    roles.sweepExpiryNotices();
                    roles.sweepLinkCodes();
                }),
                1,
                1,
                TimeUnit.HOURS);

        // The tick only calls Discord when the rendered name changed.
        if (status.configured()) {
            repeat(guarded("status channels", status::tick), 0, 1, TimeUnit.MINUTES);
        } else {
            log.info("No language has a status-channel; the sidebar status is off");
        }

        // The one tick that reads the database every pass, so it runs on worker.
        repeat(
                guarded("update feed", () -> updateFeed.submit(worker)),
                UpdateFeed.INTERVAL.toSeconds(),
                UpdateFeed.INTERVAL.toSeconds(),
                TimeUnit.SECONDS);
    }

    /**
     * Opens the bot's one signal hub; every refresh hands its work to {@code worker}.
     *
     * Each runs on connect, on every signal and on the hub's reconciliation, which is what replaced the polls.
     */
    private SignalHub listen(
            final DatabaseSpec databaseConfig,
            final PaymentProcessor processor,
            final PurchaseFlow purchaseFlow,
            final AccessInbox accessInbox,
            final AdminRole adminRole) {
        final SignalHub hub = SignalHub.open(
                databaseConfig.jdbcUrl(),
                databaseConfig.username(),
                databaseConfig.password(),
                LISTENER_SOCKET_TIMEOUT_SECONDS,
                "access-bot-signals",
                log);
        // Unconditional: without bunq both queues are simply empty.
        hub.on(Channel.PAYMENT, "matched payments", () -> worker.execute(guarded("payment booking", processor::poll)));
        hub.on(
                Channel.PAYMENT,
                "waiting payment links",
                () -> worker.execute(guarded("payment links", purchaseFlow::fillIn)));
        hub.on(Channel.ACCESS, "access requests", () -> worker.execute(guarded("access inbox", accessInbox::drain)));
        hub.on(Channel.ADMIN, "admin role", () -> worker.execute(guarded("admin role", adminRole::reconcile)));
        hub.start();
        return hub;
    }

    private void repeat(final Runnable task, final long initialDelay, final long delay, final TimeUnit unit) {
        var _ = timers.scheduleWithFixedDelay(task, initialDelay, delay, unit);
    }

    private Runnable guarded(final String name, final Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (final RuntimeException exception) {
                log.error("The {} task failed; it will run again on schedule", name, exception);
            }
        };
    }

    /**
     * Stops the timers, ends the Discord session and closes the pool.
     *
     * The readiness marker is left to go stale, which is the signal.
     */
    @Override
    public void close() {
        log.info("Shutting down");
        signals.close();
        timers.shutdownNow();
        worker.shutdownNow();
        try {
            jda.shutdown();
        } finally {
            database.close();
        }
    }

    private static DatabaseConfig toDatabaseConfig(final DatabaseSpec config) {
        return DatabaseConfig.builder(config.jdbcUrl())
                .username(config.username())
                .password(config.password())
                .poolName("access-bot")
                .maximumPoolSize(config.maximumPoolSize())
                .logSql(config.logSql())
                .build();
    }

    /** How long a bot that cannot start waits before exiting, so Discord does not rate-limit repeated bad logins. */
    private static final java.time.Duration FATAL_BACKOFF = java.time.Duration.ofSeconds(60);

    public static void main(final String[] args) throws InterruptedException {
        final AccessBot bot;
        try {
            bot = new AccessBot();
        } catch (final ConfigException e) {
            // Not a stack trace: the message names the file, the setting and what is wrong.
            log.error("access-bot is not starting because its configuration could not be read.");
            log.error("{}", e.getMessage());
            System.exit(1);
            return;
        } catch (final net.dv8tion.jda.api.exceptions.InvalidTokenException badToken) {
            // Treated like a refused config: the token is a setting Discord has rejected.
            log.error("access-bot is not starting: Discord rejected the bot token.");
            log.error("Check NORDTAL_BOT_TOKEN in .env against the token in the Discord developer"
                    + " portal - a regenerated token invalidates the old one immediately.");
            backOffThenExit();
            return;
        }
        // The container runtime stops it with SIGTERM, so the pool closes in the shutdown hook.
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close, "access-bot-shutdown"));
    }

    /** Waits, then exits 1; interruptible, so SIGTERM is not ignored for a minute. */
    private static void backOffThenExit() {
        log.error(
                "Waiting {}s before exiting, so this container does not retry a login Discord has"
                        + " already refused every few seconds.",
                FATAL_BACKOFF.toSeconds());
        final var _ = Waiting.on(NetworkTime.clock()).sleep(FATAL_BACKOFF);
        System.exit(1);
    }
}

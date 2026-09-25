package eu.nordtal.s2.discordbot;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.Configured;
import eu.nordtal.s2.discordbot.config.BotSpec;
import eu.nordtal.s2.discordbot.config.Configs;
import eu.nordtal.s2.discordbot.config.DatabaseSpec;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import eu.nordtal.s2.discordbot.discord.AccessInbox;
import eu.nordtal.s2.discordbot.discord.AdminLog;
import eu.nordtal.s2.discordbot.discord.AdminRole;
import eu.nordtal.s2.discordbot.discord.GuildState;
import eu.nordtal.s2.discordbot.access.discord.LinkFlow;
import eu.nordtal.s2.discordbot.access.discord.RedemptionLimit;
import eu.nordtal.s2.discordbot.access.discord.ManagedMessages;
import eu.nordtal.s2.commands.access.AccessCommands;
import eu.nordtal.s2.discordbot.discord.BotAccessEffects;
import eu.nordtal.s2.discordbot.discord.UpdateCommand;
import eu.nordtal.s2.discordbot.discord.UpdateFeed;
import eu.nordtal.s2.discordbot.access.discord.PurchaseFlow;
import eu.nordtal.s2.discordbot.access.payment.PaymentProcessor;
import eu.nordtal.s2.common.payment.PaymentGateway;
import eu.nordtal.s2.common.payment.PaymentRequests;
import eu.nordtal.s2.discordbot.access.payment.Purchases;
import eu.nordtal.s2.discordbot.access.payment.Tiers;
import eu.nordtal.s2.discordbot.status.StatusChannels;
import eu.nordtal.s2.discordbot.hungergames.RegisterFlow;
import eu.nordtal.s2.discordbot.hungergames.RegisterMessages;
import eu.nordtal.s2.discordbot.hungergames.Teams;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AdminTree;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.network.SnapshotDirectory;
import eu.nordtal.s2.common.notify.Channels;
import eu.nordtal.s2.common.notify.NotificationListener;
import eu.nordtal.s2.common.notify.PostgresNotifications;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.update.UpdateDirectory;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point and owner of everything with a lifecycle: the connection pool, the JDA session, the
 * payment listener and the sweeps.
 *
 * <p>The startup order is deliberate: configuration first, so a bad value stops the process here
 * naming the file and the setting; then the database, so bad credentials and a schema this jar was
 * not built against are found before a Discord session exists ({@link SchemaCheck}); then Discord,
 * and only once it is ready the managed messages, the reconciles and the timers.</p>
 */
@Slf4j
public class AccessBot implements AutoCloseable {

    /** Where the message bundles live on the classpath - one {@code <tag>.properties} per language. */
    private static final String MESSAGE_ROOT = "messages/access";

    private final Database database;
    private final AccessDirectory access;
    private final JDA jda;

    /**
     * {@code LISTEN nordtal_payment} - what makes the payment seam feel instant.
     *
     * <p>A dedicated connection, never the pool's: {@code LISTEN} is session state and a pool hands
     * sessions back out. It carries no guarantee of its own; the timer in {@link #schedule} does
     * that, and this only decides when.</p>
     */
    /** How long the access inbox waits before reading the queue again without being told to. */
    private static final Duration ACCESS_POLL = Duration.ofSeconds(30);

    private final NotificationListener paymentListener;

    /**
     * The `nordtal_access` half (season-2-community/08). A second listener rather than a second
     * channel on the payment one: they are configured from different places - the payment poll is
     * the access config's, this one is the inbox's own - and one listener whose wait interval is
     * whichever of the two happened to be passed is the kind of thing nobody notices is wrong.
     */
    private final NotificationListener accessListener;

    /**
     * Bounds a database that has gone away without closing the socket. It is <b>not</b> the wait:
     * pgjdbc overrides the socket timeout for the duration of a {@code getNotifications} call, so
     * this only applies to the liveness check and the reconnect.
     */
    private static final int LISTENER_SOCKET_TIMEOUT_SECONDS = 30;

    /**
     * Everything that blocks: the database work behind an interaction and the REST calls that
     * follow it. JDA's gateway threads must not do either - an interaction that is not acknowledged
     * within three seconds is dead, and a gateway thread waiting on anything stalls every other
     * interaction in the guild.
     */
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

    public AccessBot() throws InterruptedException, ConfigException {
        final DatabaseSpec databaseConfig = Configs.database().get();
        final BotSpec botConfig = Configs.bot().get();
        final AccessSpec accessConfig = Configs.access().get();

        this.database = Database.create(toDatabaseConfig(databaseConfig));

        boolean started = false;
        try {
            // The bot does not migrate - steward-worker does. This check makes a bot started against
            // an unmigrated database refuse here, naming the command, rather than failing on its
            // first query inside a Discord interaction minutes later.
            SchemaCheck.validate(database.dataSource());

            // The bot borrows the pool it already owns rather than opening a second one. Closing a
            // borrowed pool is a no-op, so ownership stays here.
            this.access = AccessDirectory.using(database.dataSource());
            // Over the pool the bot already owns, like the access directory. Nothing here holds a
            // resource, so there is nothing to close.
            final PhaseDirectory phases = PhaseDirectory.using(database.dataSource());
            // steward-worker's inbox. The bot writes requests into it and reads the answers back; it
            // never updates anything itself and could not - the jars and volumes are in another
            // container.
            final UpdateDirectory updates = UpdateDirectory.using(database.dataSource());

            // Which bundles are loaded is a config question, not a code one: the list drives it,
            // so adding a language is an edit to access.yml plus a <tag>.properties file. A
            // language whose file is missing is logged once and reads English rather than failing.
            final Languages languages = Languages.of(accessConfig);
            // Two roots: :commands' shared bundle underneath this module's own, so a string said
            // on more than one surface is declared once. This module's keys win on a collision.
            final Messages messages = Messages.load(AccessBot.class.getClassLoader(),
                    java.util.List.of("messages/commands", MESSAGE_ROOT),
                    Configs.messagesDirectory(), languages.locales());
            messages.unknownOverrideKeys().forEach(key -> log.warn(
                    "the message override names {}, which no bundle declares - it is stored"
                            + " and never used; check the spelling", key));
            // The same files as ONE root, for rendering remote answers: this module's keys are
            // allowed Discord markdown, which a player would read literally in chat. Overrides
            // still apply. Its unknown keys are not reported - a key only this module declares is
            // not unknown to it.
            final Messages sharedMessages = Messages.load(
                    AccessBot.class.getClassLoader(), "messages/commands",
                    Configs.messagesDirectory(), languages.locales());
            final Tiers tiers = Tiers.of(accessConfig);

            // What is NOT configured, once, by name. Every consumer below degrades quietly when an
            // id is empty - which is right at the call site and wrong as the only record of it, so
            // this is the line that answers "why is nothing appearing in that channel".
            //
            // bunq is in that list and is no longer this process's own answer: since steward/109
            // the key lives in steward-worker, so what is read here is the row that worker wrote at
            // its own start. Compose makes the bot wait for the worker's health marker, so the
            // value is this deployment's and not the previous boot's.
            Configured.report(accessConfig, PaymentGateway.state(database.jdbi()));
            final PaymentRequests requests = new PaymentRequests(database.jdbi());
            final Purchases purchases = new Purchases(requests, tiers, accessConfig);

            // GUILD_MEMBERS is privileged and must be enabled in Discord's developer portal:
            // without it there is no member cache, so both reconciles have nothing to read.
            // GUILD_MODERATION carries ban and unban. Nothing else is requested.
            this.jda = JDABuilder.createLight(botConfig.token())
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MODERATION)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .build()
                    .awaitReady();

            jda.getPresence().setPresence(
                    Activity.of(Activity.ActivityType.CUSTOM_STATUS, "It's that time of the year again..."), false);

            final AdminLog admin = new AdminLog(jda, accessConfig, database.jdbi());
            // Every grant is checked against it: a period sold while season_phase.smp_start is
            // NULL starts now instead of at the SMP opening, which is allowed and has to be loud.
            final SeasonStart seasonStart = new SeasonStart(phases, admin);
            final AccessRoles roles = new AccessRoles(jda, accessConfig, access, messages, admin, database.jdbi());
            // The bot's half of the payment seam: it books what steward-worker has already found
            // and attributed, and posts what the worker could not act on. No watermark here any
            // more - it decided which bunq payments were old enough to ignore, which is a question
            // only the process that reads bunq can be asked.
            final PaymentProcessor processor = new PaymentProcessor(languages, requests,
                    tiers, access, roles, admin, messages, jda, seasonStart);
            // Admins are a grant tree decided in Steward. The bot drops a branch when its admin
            // leaves the guild, and keeps the Discord admin role in step - it never grants.
            final AdminTree adminTree = AdminTree.using(database.dataSource());
            final GuildState guildState =
                    new GuildState(jda, accessConfig, languages, access, adminTree, database.jdbi());
            final AdminRole adminRole = new AdminRole(jda, accessConfig, adminTree, admin);
            final Teams teams = new Teams(database.jdbi());

            // Built before the listener list because the command effects below hand it the watch.
            final UpdateCommand updateCommand =
                    new UpdateCommand(updates, admin, database.jdbi(), messages, worker,
                            timers);

            // Held rather than only registered: the payment seam has to be able to reach back into
            // it and finish the ephemeral messages that are waiting for a link.
            final PurchaseFlow purchaseFlow = new PurchaseFlow(accessConfig, tiers, purchases,
                    requests, messages, roles, admin, worker);

            jda.addEventListener(
                    guildState,
                    purchaseFlow,
                    new LinkFlow(access, roles, messages, admin,
                            new RedemptionLimit(accessConfig.linkCodeAttemptsPerHour(), Clock.systemUTC()),
                            worker),
                    updateCommand,
                    new RegisterFlow(jda, teams, messages, worker));

            // The bot registers no declared command as a slash command any more
            // (season-2-community/10). `/smp status` was the last declaration carrying
            // Surface.DISCORD and it lost it with this ticket, which made the whole adapter - JDA
            // registration, the admin check, arguments translated into slash options, the
            // confirmation button - 611 lines that produced nothing. Steward answers that command
            // twice over, as a service page and through POST /api/services/{name}/console.
            //
            // What the bot still does with declared commands is the OTHER direction, right below:
            // a /access grant typed on a console arrives here as a command_request row. The
            // catalogue is not gone from this process, only its Discord face is.
            final eu.nordtal.s2.common.command.CommandRequests commandRequests =
                    eu.nordtal.s2.common.command.CommandRequests.borrowing(database.dataSource());

            // ...and the other direction: a /access grant typed in game arrives here as a row.
            // Inline effects, because the inbox settles the row when the command returns.
            final eu.nordtal.s2.commands.remote.CommandInbox inbox =
                    new eu.nordtal.s2.commands.remote.CommandInbox(
                            eu.nordtal.s2.commands.Target.BOT, commandRequests,
                            sharedMessages,
                            eu.nordtal.s2.commands.remote.CommandInbox.AdminCheck.of(
                                    access::admins, access::adminMinecraftAccounts),
                            (message, failure) -> log.warn(message, failure));
            final BotAccessEffects inboxEffects = new BotAccessEffects(Runnable::run, jda, access,
                    roles, requests, admin, seasonStart, messages, sharedMessages, log);
            AccessCommands.all().forEach(command -> inbox.register(command, inboxEffects));
            // The servers' line into the announcement channels: `announce <language> <text>` rows
            // from the SMP (a milestone, a phase change), posted here verbatim. Inline for
            // the same reason as above.
            final eu.nordtal.s2.discordbot.announce.Announcements announcements =
                    new eu.nordtal.s2.discordbot.announce.Announcements(jda, languages, Runnable::run, log);
            eu.nordtal.s2.commands.announce.AnnounceCommands.all()
                    .forEach(command -> inbox.register(command, announcements));
            // Scheduled on `timers`, run on `worker`: `timers` is one thread carrying five other
            // ticks, and a drain blocks on JDA REST and the database.
            timers.scheduleWithFixedDelay(() -> worker.execute(inbox::drain), 5, 5,
                    java.util.concurrent.TimeUnit.SECONDS);

            final List<CommandData> commands = new ArrayList<>();
            // Only what the bot registers natively - /unlink and the rest of LinkFlow. Those were
            // never in the catalogue: they are a player's own self-service, not an admin command.
            commands.addAll(LinkFlow.commands());
            jda.updateCommands().addCommands(commands).queue();

            new ManagedMessages(jda, languages, tiers, messages, database.jdbi()).publishAll();
            new RegisterMessages(jda, languages, messages, database.jdbi()).publishAll();
            guildState.reconcile();
            roles.reconcile();
            adminRole.reconcile();

            // The sidebar status channels, if any language configured one. After the guild state
            // reconcile, so the first tick renames against a settled picture.
            final StatusChannels status = new StatusChannels(jda, languages, messages, phases,
                    SnapshotDirectory.using(database.dataSource()), Clock.systemUTC(), announcements);

            // Every update run in the admin channel, including ones nobody started in Discord.
            // start() reads the table once to decide where the feed begins; beginning at zero
            // would post a season of history into the channel.
            final UpdateFeed updateFeed =
                    new UpdateFeed(updates, UpdateFeed.Board.of(admin), messages);
            updateFeed.start();

            schedule(accessConfig, processor, purchaseFlow, roles, status, updateFeed);

            // The other half of what drives the payment seam. Started last of the payment wiring,
            // because it refreshes immediately on connect and both refreshes touch JDA.
            this.paymentListener = listenForPayments(databaseConfig, accessConfig, processor,
                    purchaseFlow);

            // Every access change, whoever asked for it (season-2-community/08). The effects are
            // the ones the command inbox already uses - one executor, so a grant from steward and a
            // grant typed in Discord are the same four things.
            this.accessListener = listenForAccess(databaseConfig,
                    new AccessInbox(eu.nordtal.s2.common.access.AccessRequests.on(
                            database.dataSource()), inboxEffects, log), adminRole);

            // The readiness marker sits last on purpose: nothing above writes one, so a marker on
            // disk means this bot got all the way through its constructor. It shares the timer
            // thread with the payment poll deliberately - a wedged timer thread is a bot that has
            // silently stopped booking payments, and it must not look healthy then.
            final Readiness readiness = Readiness.onDefaultPath(log::warn);
            timers.scheduleWithFixedDelay(guarded("readiness marker", readiness::refresh),
                    0, Readiness.BEAT.toSeconds(), TimeUnit.SECONDS);

            started = true;
            log.info("access-bot is up");
        } finally {
            if (!started) {
                database.close();
            }
        }
    }

    /**
     * The recurring timers. Each task is wrapped because the scheduler cancels a task that throws,
     * and the failure mode of that is a bot that looks healthy and stops booking payments.
     */
    private void schedule(final AccessSpec config, final PaymentProcessor processor,
                          final PurchaseFlow purchaseFlow, final AccessRoles roles,
                          final StatusChannels status, final UpdateFeed updateFeed) {
        // Unconditional since steward/109, and that is a change worth naming. It used to be gated
        // on "is bunq configured", because the pass itself called a bank and calling one without a
        // key is an exception every few seconds. It now reads two queues in this database - rows
        // steward-worker wrote - so there is nothing to be unconfigured about, and the gate would
        // have to ask another container's configuration to decide. A deployment with no bunq simply
        // has two empty queues.
        final int poll = config.payment().pollIntervalSeconds();
        timers.scheduleWithFixedDelay(guarded("payment seam", () -> {
            processor.poll();
            purchaseFlow.fillIn();
        }), poll, poll, TimeUnit.SECONDS);

        final int reconcile = config.roleReconcileIntervalMinutes();
        timers.scheduleWithFixedDelay(guarded("role reconcile", roles::reconcile),
                reconcile, reconcile, TimeUnit.MINUTES);

        // Expiry DMs and the link-code sweep are cheap and do not need to be frequent; an hour
        // means a reminder is at most an hour late, against a three-day lead time.
        timers.scheduleWithFixedDelay(guarded("expiry sweep", () -> {
            roles.sweepExpiryNotices();
            roles.sweepLinkCodes();
        }), 1, 1, TimeUnit.HOURS);

        // Almost always free: the tick only calls Discord when the rendered name differs from the
        // one this process last set. Not scheduled at all when no channel is configured.
        if (status.configured()) {
            timers.scheduleWithFixedDelay(guarded("status channels", status::tick), 0, 1, TimeUnit.MINUTES);
        } else {
            log.info("No language has a status-channel; the sidebar status is off");
        }

        // One indexed lookup per tick, answering nothing except while somebody is updating. The
        // timer thread only hands the work over, because this is the one tick that reads the
        // database every pass and a stalled database would take the whole scheduler with it.
        // UpdateFeed#submit takes its single-flight guard BEFORE the hand-over, so a slow pass is
        // skipped rather than queued behind itself.
        timers.scheduleWithFixedDelay(
                guarded("update feed", () -> updateFeed.submit(worker)),
                UpdateFeed.INTERVAL.toSeconds(), UpdateFeed.INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Starts the {@code nordtal_payment} listener.
     *
     * <p>Two refreshes, because two different things are waiting on the same signal: money that has
     * been attributed and not yet booked, and an ephemeral message that has been promised a payment
     * link. Both are handed to {@code worker} rather than run on the listener thread - they call
     * Discord, and a listener thread inside a REST call is a listener that is not listening.</p>
     *
     * <p>Every refresh also runs on connect and on every reconnect, before anything is waited for,
     * which is what covers a notification published while this process was not connected.</p>
     */
    private NotificationListener listenForPayments(final DatabaseSpec databaseConfig,
                                                   final AccessSpec accessConfig,
                                                   final PaymentProcessor processor,
                                                   final PurchaseFlow purchaseFlow) {
        final Duration wait = Duration.ofSeconds(accessConfig.payment().pollIntervalSeconds());
        final NotificationListener listener = new NotificationListener(
                PostgresNotifications.connector(
                        databaseConfig.jdbcUrl(),
                        databaseConfig.username(),
                        databaseConfig.password(),
                        LISTENER_SOCKET_TIMEOUT_SECONDS,
                        "access-bot-payment-listener",
                        List.of(Channels.PAYMENT)),
                "access-bot-payment-listener",
                List.of(
                        new NotificationListener.Refresh("matched payments",
                                () -> worker.execute(guarded("payment booking", processor::poll))),
                        new NotificationListener.Refresh("waiting payment links",
                                () -> worker.execute(guarded("payment links", purchaseFlow::fillIn)))),
                log,
                wait);
        listener.start();
        return listener;
    }

    /**
     * Starts the {@code nordtal_access} listener (season-2-community/08).
     *
     * <p>It carries {@code nordtal_admin} as well, for the admin role: every wake re-reads both,
     * which is what one connection for several channels means.</p>
     *
     * <p>Each refresh is handed to {@code worker} rather than run on the listener thread: carrying a
     * grant out calls Discord four times, and a listener thread inside a REST call is a listener
     * that is not listening.</p>
     *
     * <p>The wait is the poll, and the poll is the guarantee - the notification only makes a change
     * feel instant. Thirty seconds rather than the payment seam's configured interval: an access
     * change is nearly always announced, and the poll exists for the case where the announcement
     * was lost, not for the ordinary one.</p>
     */
    private NotificationListener listenForAccess(final DatabaseSpec databaseConfig,
                                                 final AccessInbox accessInbox,
                                                 final AdminRole adminRole) {
        final NotificationListener listener = new NotificationListener(
                PostgresNotifications.connector(
                        databaseConfig.jdbcUrl(),
                        databaseConfig.username(),
                        databaseConfig.password(),
                        LISTENER_SOCKET_TIMEOUT_SECONDS,
                        "access-bot-access-listener",
                        List.of(Channels.ACCESS, Channels.ADMIN)),
                "access-bot-access-listener",
                List.of(new NotificationListener.Refresh("access requests",
                                () -> worker.execute(guarded("access inbox", accessInbox::drain))),
                        new NotificationListener.Refresh("admin role",
                                () -> worker.execute(guarded("admin role", adminRole::reconcile)))),
                log,
                ACCESS_POLL);
        listener.start();
        return listener;
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
     * Stops the timers, ends the Discord session and closes the connection pool.
     *
     * <p>The readiness beat is one of those timers, so this is also where the container stops being
     * told this process is up. The marker is deliberately not deleted: going stale is the signal.</p>
     */
    @Override
    public void close() {
        log.info("Shutting down");
        paymentListener.close();
        accessListener.close();
        timers.shutdownNow();
        worker.shutdownNow();
        try {
            jda.shutdown();
        } finally {
            access.close();
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

    /**
     * How long a bot that cannot possibly start waits before letting the container exit. The
     * restart policy brings it straight back, and repeated bad logins are what Discord rate-limits,
     * while the fix for a wrong token is a person editing {@code .env}. A minute, so the container
     * still comes back promptly once it is fixed.
     */
    private static final java.time.Duration FATAL_BACKOFF = java.time.Duration.ofSeconds(60);

    public static void main(final String[] args) throws InterruptedException {
        eu.nordtal.s2.common.message.context.Contexts.server("discord-bot");
        final AccessBot bot;
        try {
            bot = new AccessBot();
        } catch (final ConfigException e) {
            // Deliberately not a stack trace: the message is written for whoever has to fix the
            // file, and it already names the file, the setting and what is wrong with it.
            log.error("access-bot is not starting because its configuration could not be read.");
            log.error("{}", e.getMessage());
            System.exit(1);
            return;
        } catch (final net.dv8tion.jda.api.exceptions.InvalidTokenException badToken) {
            // Treated like a refused config, because that is what it is: the token is a setting
            // and Discord has said it is wrong. A stack trace says nothing actionable.
            log.error("access-bot is not starting: Discord rejected the bot token.");
            log.error("Check NORDTAL_BOT_TOKEN in .env against the token in the Discord developer"
                    + " portal - a regenerated token invalidates the old one immediately.");
            backOffThenExit();
            return;
        }
        // The process is normally stopped by SIGTERM from the container runtime, so the shutdown
        // hook is the only place the pool would ever get closed.
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close, "access-bot-shutdown"));
    }

    /**
     * Waits, then exits 1, so the restart policy retries in minutes rather than seconds.
     * Interruptible on purpose: a container that ignores SIGTERM for a minute is worse.
     */
    private static void backOffThenExit() {
        log.error("Waiting {}s before exiting, so this container does not retry a login Discord has"
                + " already refused every few seconds.", FATAL_BACKOFF.toSeconds());
        try {
            Thread.sleep(FATAL_BACKOFF.toMillis());
        } catch (final InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
        System.exit(1);
    }
}

package eu.nordtal.s2.discordbot;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.discordbot.access.SeasonStart;
import eu.nordtal.s2.discordbot.access.bunq.BunqGateway;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.BotSpec;
import eu.nordtal.s2.discordbot.config.Configs;
import eu.nordtal.s2.discordbot.config.DatabaseSpec;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.discordbot.access.discord.AccessRoles;
import eu.nordtal.s2.discordbot.discord.AdminLog;
import eu.nordtal.s2.discordbot.discord.GuildState;
import eu.nordtal.s2.discordbot.access.discord.LinkFlow;
import eu.nordtal.s2.discordbot.access.discord.RedemptionLimit;
import eu.nordtal.s2.discordbot.access.discord.ManagedMessages;
import eu.nordtal.s2.commands.Catalogue;
import eu.nordtal.s2.commands.access.AccessCommands;
import eu.nordtal.s2.commands.phase.PhaseCommands;
import eu.nordtal.s2.discordbot.discord.BotAccessEffects;
import eu.nordtal.s2.discordbot.discord.BotPhaseEffects;
import eu.nordtal.s2.discordbot.discord.DiscordCommands;
import eu.nordtal.s2.discordbot.discord.UpdateCommand;
import eu.nordtal.s2.discordbot.discord.UpdateFeed;
import eu.nordtal.s2.discordbot.access.discord.PurchaseFlow;
import eu.nordtal.s2.discordbot.access.payment.PaymentProcessor;
import eu.nordtal.s2.discordbot.access.payment.PaymentRequests;
import eu.nordtal.s2.discordbot.access.payment.Purchases;
import eu.nordtal.s2.discordbot.access.payment.Watermark;
import eu.nordtal.s2.discordbot.access.payment.Tiers;
import eu.nordtal.s2.discordbot.status.StatusChannels;
import eu.nordtal.s2.discordbot.hungergames.RegisterFlow;
import eu.nordtal.s2.discordbot.hungergames.RegisterMessages;
import eu.nordtal.s2.discordbot.hungergames.Teams;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.health.Readiness;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.network.SnapshotDirectory;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point and owner of everything with a lifecycle: the connection pool, the JDA session, the
 * bunq poll loop and the two sweeps.
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
     * Everything that blocks: bunq HTTP calls and the database work behind an interaction. JDA's
     * gateway threads must not do either - an interaction that is not acknowledged within three
     * seconds is dead, and a gateway thread waiting on a bank stalls every other interaction.
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
            // The bot does not migrate - the updater does. This check makes a bot started against
            // an unmigrated database refuse here, naming the command, rather than failing on its
            // first query inside a Discord interaction minutes later.
            SchemaCheck.validate(database.dataSource());

            // The bot borrows the pool it already owns rather than opening a second one. Closing a
            // borrowed pool is a no-op, so ownership stays here.
            this.access = AccessDirectory.using(database.dataSource());
            // Over the pool the bot already owns, like the access directory. Nothing here holds a
            // resource, so there is nothing to close.
            final PhaseDirectory phases = PhaseDirectory.using(database.dataSource());
            // The updater's inbox. The bot writes requests into it and reads the answers back; it
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
            final BunqGateway bunq = new BunqGateway(botConfig);
            final PaymentRequests requests = new PaymentRequests(database.jdbi());
            final Purchases purchases = new Purchases(requests, bunq, tiers, accessConfig);

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
            // Resolved once: the first start stamps its instant into bot_setting and every later
            // start reads it back. Payments older than it are ignored forever, which is what stops
            // an empty database from booking historical payments on the first poll.
            final PaymentProcessor processor = new PaymentProcessor(accessConfig, languages, bunq, requests,
                    purchases, tiers, access, roles, admin, messages, jda,
                    seasonStart, Watermark.resolve(database.jdbi(), accessConfig.payment().watermark()));
            final GuildState guildState = new GuildState(jda, accessConfig, languages, access, database.jdbi());
            final Teams teams = new Teams(database.jdbi());

            // Built before the listener list because the command effects below hand it the watch.
            final UpdateCommand updateCommand =
                    new UpdateCommand(updates, admin, database.jdbi(), messages, worker,
                            timers);

            jda.addEventListener(
                    guildState,
                    new PurchaseFlow(accessConfig, tiers, purchases, requests, messages, roles, admin, worker),
                    new LinkFlow(access, roles, messages, admin,
                            new RedemptionLimit(accessConfig.linkCodeAttemptsPerHour(), Clock.systemUTC()),
                            worker),
                    updateCommand,
                    new RegisterFlow(jda, teams, messages, worker));

            // Every declared command, as slash commands. /phase runs here - the bot writes the row
            // itself, because no process owns it - and everything else becomes a command_request
            // addressed to the process that does.
            final eu.nordtal.s2.common.command.CommandRequests commandRequests =
                    eu.nordtal.s2.common.command.CommandRequests.borrowing(database.dataSource());
            final DiscordCommands declared = new DiscordCommands(messages, database.jdbi(), access,
                    new eu.nordtal.s2.commands.remote.Outbox(commandRequests, timers,
                            (message, failure) -> log.warn(message, failure)),
                    worker);
            final BotPhaseEffects phaseEffects = new BotPhaseEffects(phases, admin, worker);
            PhaseCommands.all().forEach(command -> declared.local(command, phaseEffects));

            // Access, payments and the bot's own wording. These are gated on discord_user.admin,
            // not only on Discord's DefaultMemberPermissions, so the network has one admin list.
            final BotAccessEffects accessEffects = new BotAccessEffects(worker, jda, access, roles,
                    requests, admin, seasonStart, messages, sharedMessages, log);
            AccessCommands.all().forEach(command -> declared.local(command, accessEffects));
            // The one argument nobody can be expected to type from memory, on the one command that
            // books money.
            declared.suggest(AccessCommands.SETTLE, "reference", accessEffects::openReferences);

            // /update is Target.LOCAL, so the bot writes the update_request row itself rather than
            // sending a command_request to somebody who would write it for us. The watch is this
            // process's own: one embed, a field per service, edited while the run works.
            final eu.nordtal.s2.commands.update.UpdateEffects updateEffects =
                    new eu.nordtal.s2.commands.update.DirectoryUpdateEffects(
                            updates,
                            worker::execute,
                            (what, failure) -> log.warn("An update command failed while {}", what,
                                    failure),
                            (id, user) -> updateCommand.follow(user, id));
            eu.nordtal.s2.commands.update.UpdateCommands.all()
                    .forEach(command -> declared.local(command, updateEffects));

            declared.remoteAll(Catalogue.all());
            jda.addEventListener(declared);

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
            // from the SMP (a milestone, a farm-reset warning), posted here verbatim. Inline for
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
            commands.addAll(LinkFlow.commands());
            commands.addAll(declared.commands());
            jda.updateCommands().addCommands(commands).queue();

            new ManagedMessages(jda, languages, tiers, messages, database.jdbi()).publishAll();
            new RegisterMessages(jda, languages, messages, database.jdbi()).publishAll();
            guildState.reconcile();
            roles.reconcile();

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

            schedule(accessConfig, processor, roles, status, updateFeed);

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
                          final AccessRoles roles, final StatusChannels status,
                          final UpdateFeed updateFeed) {
        final int poll = config.payment().pollIntervalSeconds();
        timers.scheduleWithFixedDelay(guarded("payment poll", processor::poll), poll, poll, TimeUnit.SECONDS);

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

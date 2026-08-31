package eu.nordtal.s2.discordbot;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.discordbot.bunq.BunqGateway;
import eu.nordtal.s2.discordbot.config.AccessSpec;
import eu.nordtal.s2.discordbot.config.BotSpec;
import eu.nordtal.s2.discordbot.config.Configs;
import eu.nordtal.s2.discordbot.config.DatabaseSpec;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.discordbot.discord.AccessRoles;
import eu.nordtal.s2.discordbot.discord.AdminCommands;
import eu.nordtal.s2.discordbot.discord.AdminLog;
import eu.nordtal.s2.discordbot.discord.GuildState;
import eu.nordtal.s2.discordbot.discord.LinkFlow;
import eu.nordtal.s2.discordbot.discord.ManagedMessages;
import eu.nordtal.s2.discordbot.discord.PhaseCommand;
import eu.nordtal.s2.discordbot.discord.PurchaseFlow;
import eu.nordtal.s2.discordbot.payment.PaymentProcessor;
import eu.nordtal.s2.discordbot.payment.PaymentRequests;
import eu.nordtal.s2.discordbot.payment.Purchases;
import eu.nordtal.s2.discordbot.payment.Watermark;
import eu.nordtal.s2.discordbot.payment.Tiers;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.phase.PhaseDirectory;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point and owner of everything with a lifecycle: the connection pool, the JDA session, the
 * bunq poll loop and the two sweeps.
 * <p>
 * The startup order is deliberate. Configuration is read and validated first, so a bad value stops
 * the process here - with a message naming the file and the setting - rather than surfacing hours
 * later against the wrong channel. Then the database, so bad credentials and a broken schema are
 * found before a Discord session exists. Then Discord, and only once it is ready are the managed
 * messages published, the guild state reconciled and the timers started.
 * </p>
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
            log.info("Applied {} database migration(s)", database.migrate());

            // The bot borrows the pool it already owns rather than opening a second one. Closing a
            // borrowed pool is a no-op, so ownership stays here.
            this.access = AccessDirectory.using(database.dataSource());
            // Over the pool the bot already owns, like the access directory. Nothing here holds a
            // resource, so there is nothing to close.
            final PhaseDirectory phases = PhaseDirectory.using(database.dataSource());

            // Which bundles are loaded is a config question, not a code one: the list drives it,
            // so adding a language is an edit to access.yml plus a <tag>.properties file. A
            // language whose file is missing is logged once and reads English rather than failing.
            final Languages languages = Languages.of(accessConfig);
            final Messages messages = Messages.load(MESSAGE_ROOT, languages.locales());
            final Tiers tiers = Tiers.of(accessConfig);
            final BunqGateway bunq = new BunqGateway(botConfig);
            final PaymentRequests requests = new PaymentRequests(database.jdbi());
            final Purchases purchases = new Purchases(requests, bunq, tiers, accessConfig);

            // GUILD_MEMBERS is privileged and has to be enabled for the application in Discord's
            // developer portal. It is not optional here: without it there is no member cache, so
            // the role reconcile and the guild-state reconcile have nothing to read.
            // GUILD_MODERATION carries the ban and unban events. Nothing else is requested -
            // season 1 asked for ALL_INTENTS, including message content.
            this.jda = JDABuilder.createLight(botConfig.token())
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MODERATION)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .build()
                    .awaitReady();

            jda.getPresence().setPresence(
                    Activity.of(Activity.ActivityType.CUSTOM_STATUS, "nordtal.eu"), false);

            final AdminLog admin = new AdminLog(jda, accessConfig, database.jdbi());
            final AccessRoles roles = new AccessRoles(jda, accessConfig, access, messages, admin, database.jdbi());
            // Resolved once, at startup: the first start ever stamps its own instant into
            // bot_setting and every later start reads it back. Payments older than it are ignored
            // forever, which is what stops the first poll booking fifty historical payments.
            final PaymentProcessor processor = new PaymentProcessor(accessConfig, languages, bunq, requests,
                    purchases, tiers, access, roles, admin, messages, jda,
                    Watermark.resolve(database.jdbi(), accessConfig.payment().watermark()));
            final GuildState guildState = new GuildState(jda, accessConfig, languages, access, database.jdbi());

            jda.addEventListener(
                    guildState,
                    new PurchaseFlow(accessConfig, tiers, purchases, requests, messages, roles, admin, worker),
                    new LinkFlow(access, roles, messages, admin, worker),
                    new AdminCommands(access, roles, requests, admin, messages, worker),
                    new PhaseCommand(phases, admin, database.jdbi(), worker));

            final List<CommandData> commands = new ArrayList<>();
            commands.addAll(AdminCommands.commands());
            commands.addAll(LinkFlow.commands());
            commands.addAll(PhaseCommand.commands());
            jda.updateCommands().addCommands(commands).queue();

            new ManagedMessages(jda, languages, tiers, messages, database.jdbi()).publishAll();
            guildState.reconcile();
            roles.reconcile();

            schedule(accessConfig, processor, roles);
            started = true;
            log.info("access-bot is up");
        } finally {
            if (!started) {
                database.close();
            }
        }
    }

    /**
     * The three timers.
     * <p>
     * Each task is wrapped so a thrown exception cannot silently kill its schedule -
     * {@code scheduleAtFixedRate} cancels a task that throws, and the failure mode of that is a
     * bot that looks healthy and stops booking payments.
     * </p>
     */
    private void schedule(final AccessSpec config, final PaymentProcessor processor, final AccessRoles roles) {
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

    /** Stops the timers, ends the Discord session and closes the connection pool. */
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
        }
        // The process is normally stopped by SIGTERM from the container runtime, so the shutdown
        // hook is the only place the pool would ever get closed.
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close, "access-bot-shutdown"));
    }
}

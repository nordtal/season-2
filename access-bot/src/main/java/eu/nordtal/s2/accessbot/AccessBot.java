package eu.nordtal.s2.accessbot;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.accessbot.config.BotSpec;
import eu.nordtal.s2.accessbot.config.Configs;
import eu.nordtal.s2.accessbot.config.DatabaseSpec;
import eu.nordtal.s2.accessbot.config.PaymentProcessingSpec;
import eu.nordtal.s2.accessbot.service.BunqService;
import eu.nordtal.s2.accessbot.events.ContributionEventListeners;
import eu.nordtal.s2.accessbot.events.SlashCommandInteractionListener;
import eu.nordtal.s2.accessbot.service.ContributionService;
import eu.nordtal.s2.accessbot.service.PaymentProcessingService;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

/**
 * Entry point and owner of everything with a lifecycle: the connection pool, the JDA session and
 * the payment poll loop.
 */
@Slf4j
public class AccessBot implements AutoCloseable {

    /**
     * The one connection pool of the process. Everything that touches the database goes through
     * this instance; it is closed in {@link #close()}.
     */
    private final Database database;

    @Getter
    private final JDA jda;

    @Getter
    private final PaymentProcessingService paymentProcessingService;

    public AccessBot() throws InterruptedException, ConfigException {
        // Configuration comes first, then persistence, then Discord. Every config is read and
        // validated before anything with a lifecycle starts, so a bad value stops the process
        // here - with a message naming the file and the setting - instead of surfacing hours
        // later against the wrong channel or account. Database.create then fails fast on bad
        // credentials or an unreachable host, and Flyway on a broken schema, both of which are
        // better discovered before a Discord session is live and slash commands are registered.
        final DatabaseSpec databaseConfig = Configs.database().get();
        final BotSpec botConfig = Configs.bot().get();
        final PaymentProcessingSpec paymentConfig = Configs.paymentProcessing().get();

        BunqService.configure(botConfig);

        this.database = Database.create(toDatabaseConfig(databaseConfig));

        boolean started = false;
        try {
            log.info("Applied {} database migration(s)", database.migrate());

            jda = JDABuilder.createDefault(botConfig.token())
                    .enableIntents(GatewayIntent.getIntents(GatewayIntent.ALL_INTENTS))
                    .build().awaitReady();

            jda.getPresence().setPresence(Activity.of(Activity.ActivityType.CUSTOM_STATUS, "Counting stacks 💶"), false);

            jda.updateCommands().addCommands(
                    Commands.slash("send-contribution-embed", "Sends the contribution embed to the channel you are in.")
                            .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                    Commands.slash("test-con", "Adds test contribution.")
                            .addOption(OptionType.INTEGER, "amount", "Amount", true)
                            .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                    Commands.slash("manual-con", "Adds a manual contribution.")
                            .addOption(OptionType.USER, "user", "User", true)
                            .addOption(OptionType.INTEGER, "amount", "Amount", true)
                            .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
            ).queue();

            paymentProcessingService =
                    new PaymentProcessingService(jda, new ContributionService(database), paymentConfig);

            jda.addEventListener(new SlashCommandInteractionListener(this), new ContributionEventListeners());
            started = true;
        } finally {
            if (!started) {
                database.close();
            }
        }
    }

    /**
     * Stops the poll loop, ends the Discord session and closes the connection pool. Idempotent as
     * far as its parts are.
     */
    @Override
    public void close() {
        log.info("Shutting down");
        try {
            paymentProcessingService.close();
            jda.shutdown();
        } finally {
            database.close();
        }
    }

    /**
     * Turns the config settings into the record jcore's {@code Database.create} takes. The pool
     * knobs jcore exposes but nobody has needed to tune here (idle timeout, max lifetime,
     * connection timeout) stay at jcore's defaults rather than being mirrored into the file.
     */
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
        } catch (ConfigException e) {
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

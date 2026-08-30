package eu.nordtal.s2.paymentsbot;

import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.paymentsbot.config.BotDatabaseConfig;
import eu.nordtal.s2.paymentsbot.config.Configs;
import eu.nordtal.s2.paymentsbot.events.ContributionEventListeners;
import eu.nordtal.s2.paymentsbot.events.SlashCommandInteractionListener;
import eu.nordtal.s2.paymentsbot.service.ContributionService;
import eu.nordtal.s2.paymentsbot.service.PaymentProcessingService;

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
public class NordTalPayments implements AutoCloseable {

    /**
     * The one connection pool of the process. Everything that touches the database goes through
     * this instance; it is closed in {@link #close()}.
     */
    private final Database database;

    @Getter
    private final JDA jda;

    @Getter
    private final PaymentProcessingService paymentProcessingService;

    public NordTalPayments() throws InterruptedException, ConfigException {
        // Persistence comes first on purpose. Database.create fails fast on bad credentials or an
        // unreachable host, and Flyway fails fast on a broken schema - both are better discovered
        // before a Discord session is live and slash commands are registered.
        final BotDatabaseConfig databaseConfig = Configs.load("database", BotDatabaseConfig.class);
        this.database = Database.create(databaseConfig.toDatabaseConfig());

        boolean started = false;
        try {
            log.info("Applied {} database migration(s)", database.migrate());

            final String token = System.getenv("BOT_TOKEN");
            jda = JDABuilder.createDefault(token)
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

            paymentProcessingService = new PaymentProcessingService(jda, new ContributionService(database));

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

    public static void main(final String[] args) throws InterruptedException, ConfigException {
        final NordTalPayments bot = new NordTalPayments();
        // The process is normally stopped by SIGTERM from the container runtime, so the shutdown
        // hook is the only place the pool would ever get closed.
        Runtime.getRuntime().addShutdownHook(new Thread(bot::close, "payments-bot-shutdown"));
    }


}

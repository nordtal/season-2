package eu.nordtal.s2.steward.ui;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.time.NetworkTime;
import eu.nordtal.s2.settings.SettingsException;
import eu.nordtal.s2.steward.ui.auth.Credentials;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.auth.Sessions;
import eu.nordtal.s2.steward.ui.config.UiSettings;
import eu.nordtal.s2.steward.ui.config.UiSpec;
import eu.nordtal.s2.steward.ui.data.Data;
import eu.nordtal.s2.steward.ui.internal.InternalClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The container's entry point and its one maintenance subcommand. */
final class Cli {

    private static final Logger log = LoggerFactory.getLogger(Cli.class);

    /** The one clock of this process. */
    private static final Clock CLOCK = NetworkTime.clock();

    private Cli() {}

    /** Reads the configuration, builds the clients and serves. */
    static void run(final String[] args) {
        final Path directory = Path.of(System.getenv().getOrDefault("NORDTAL_STEWARD_UI_CONFIG_DIR", "config"));
        if (args.length > 0 && StewardUi.FORGET.equals(args[0])) {
            System.exit(forgetFactors(directory, args));
            return;
        }
        if (args.length > 0 && StewardUi.GENERATE_VAPID_KEYS.equals(args[0])) {
            final com.interaso.webpush.VapidKeys keys = com.interaso.webpush.VapidKeys.generate();
            System.out.println(keys.getX509PublicKey());
            System.out.println(keys.getPkcs8PrivateKey());
            return;
        }
        if (args.length > 0 && !StewardUi.SERVE.equals(args[0])) {
            System.err.println("`" + args[0] + "` is not a command. This program serves the web"
                    + " interface when given none, and knows `" + StewardUi.FORGET + " <discord-id>` and `"
                    + StewardUi.GENERATE_VAPID_KEYS + "`.");
            System.exit(2);
            return;
        }
        final UiSpec config;
        final Data data;
        try {
            config = UiSettings.ui(directory, log).get();
            // Opened here, so a bad config fails at startup.
            data = new Data(UiSettings.database(directory, log).get(), CLOCK);
        } catch (SettingsException failure) {
            log.error(
                    "The configuration in {} could not be read, so nothing is being served.",
                    directory.toAbsolutePath(),
                    failure);
            System.exit(1);
            return;
        }

        final InternalClient worker = clientOf(
                "steward-worker", config.worker().baseUrl(), config.worker().token());
        if (config.worker().token().isBlank()) {
            log.warn("worker.token is empty, so nothing about a container can be read. Every page"
                    + " that would show one says so instead of drawing an empty table.");
        }
        // A second client and a second secret: the deployer may create containers, the worker may not.
        final InternalClient deployer = clientOf(
                "steward-deployer",
                config.deployer().baseUrl(),
                config.deployer().token());
        if (config.deployer().token().isBlank()) {
            log.warn("deployer.token is empty, so no container can be recreated from here. The"
                    + " button is not drawn and the page says why.");
        }
        if (config.webPush().publicKey().isBlank()) {
            log.warn("web-push has no VAPID keypair yet, so the traffic light cannot reach a phone's"
                    + " lock screen. Run `steward-ui " + StewardUi.GENERATE_VAPID_KEYS + "` and paste both"
                    + " lines it prints into steward-ui.yml's web-push section.");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(data::close, "steward-ui-shutdown"));
        new StewardUi(config, new DiscordAuth(config.discord(), config.publicUrl()), worker, deployer, data, CLOCK)
                .start(config.port());
    }

    private static InternalClient clientOf(final String name, final String baseUrl, final String token) {
        return new InternalClient(name, baseUrl, token, Duration.ofSeconds(10));
    }

    /**
     * {@code forget-factors <discord-id>}: the way back in after a lost authenticator, run from a host shell.
     *
     * Removes keys and sessions together, so no signed-in browser stays inside; returns the exit status.
     */
    private static int forgetFactors(final Path directory, final String[] args) {
        if (args.length != 2 || args[1].isBlank()) {
            System.err.println("Usage: " + StewardUi.FORGET + " <discord-id>");
            System.err.println("Clears the security keys and the sessions of one account, so that"
                    + " its next sign-in starts at the setup page.");
            return 2;
        }
        final DiscordId discordId = DiscordId.of(args[1].trim());
        // Anything but digits would run a DELETE matching nothing.
        if (!discordId.value().chars().allMatch(Character::isDigit)) {
            System.err.println("`" + discordId + "` is not a Discord id - those are digits only."
                    + " Take it from the journal or from the account list.");
            return 2;
        }
        try (Data data = new Data(UiSettings.database(directory, log).get(), CLOCK)) {
            final Credentials credentials = new Credentials(data.dataSource());
            final Sessions sessions = new Sessions(data.dataSource(), Duration.ofDays(1));
            final int keys = credentials.forget(discordId);
            final int signedOut = sessions.endAllOf(discordId);
            if (keys == 0 && signedOut == 0) {
                System.out.println("Nothing to forget: " + discordId + " has no security key and"
                        + " no session. Its next sign-in already starts at the setup page.");
                return 0;
            }
            // Written after the deletes, so a row never claims something that did not happen.
            data.audit()
                    .record(
                            "FORGET_FACTORS",
                            "host",
                            discordId.value(),
                            null,
                            keys + " security key(s) and " + signedOut + " session(s) of " + discordId
                                    + " were cleared from the host");
            System.out.println(
                    "Cleared " + keys + " security key(s) and " + signedOut + " session(s) of " + discordId + ".");
            System.out.println("Its next sign-in will ask for Discord and then register a new key.");
            return 0;
        } catch (SettingsException failure) {
            log.error(
                    "The database configuration in {} could not be read, so nothing was cleared.",
                    directory.toAbsolutePath(),
                    failure);
            return 1;
        }
    }
}

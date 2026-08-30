package eu.nordtal.s2.accessbot.config;

import eu.nordtal.jcore.config.exception.ConfigValidationException;
import eu.nordtal.jcore.config.exception.UnknownConfigKeyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot's side of the config change: the one-time JSON conversion for the existing Docker
 * deploy, and the fail-fast that replaced "log it and carry on with defaults".
 */
class ConfigsTest {

    @TempDir
    Path directory;

    @BeforeEach
    void pointConfigsAtTempDirectory() {
        System.setProperty(Configs.DIRECTORY_PROPERTY, directory.toString());
    }

    @AfterEach
    void restore() {
        System.clearProperty(Configs.DIRECTORY_PROPERTY);
    }

    // ------------------------------------------------------------- JSON -> YAML conversion

    @Test
    @DisplayName("an existing payment-processing.json is converted once, keeping every value")
    void convertsLegacyPaymentProcessingJson() throws Exception {
        // Exactly the shape the deployed config volume holds: jcore 1.x wrote SNAKE_CASE.
        Files.writeString(directory.resolve("payment-processing.json"), """
                {
                  "check_interval_seconds" : 25,
                  "confirmation_channel_id" : "111222333",
                  "balance_channel_id" : "444555666",
                  "balance_channel_format" : "BAL %s EUR"
                }
                """);

        final PaymentProcessingSpec config = Configs.paymentProcessing().get();

        assertAll(
                () -> assertEquals(25L, config.checkIntervalSeconds()),
                () -> assertEquals("111222333", config.confirmationChannelId()),
                // These two were flat in the JSON and are nested now, so the plain
                // underscore-to-hyphen rule is not enough on its own.
                () -> assertEquals("444555666", config.balance().channelId()),
                () -> assertEquals("BAL %s EUR", config.balance().nameFormat())
        );

        assertAll(
                () -> assertTrue(Files.isRegularFile(directory.resolve("payment-processing.yml"))),
                () -> assertFalse(Files.exists(directory.resolve("payment-processing.json")),
                        "the JSON file is moved aside so the conversion cannot run twice"),
                () -> assertTrue(Files.isRegularFile(directory.resolve("payment-processing.json.migrated")),
                        "and it is kept, not deleted")
        );
    }

    @Test
    @DisplayName("the converted file comes out with its comments and header in place")
    void convertedFileIsCommented() throws Exception {
        Files.writeString(directory.resolve("payment-processing.json"),
                "{\"check_interval_seconds\": 25}");

        Configs.paymentProcessing();

        final String yaml = Files.readString(directory.resolve("payment-processing.yml"));
        assertAll(
                () -> assertTrue(yaml.contains("#   access-bot - payment processing"), yaml),
                () -> assertTrue(yaml.contains("# How often the bunq account is polled"), yaml),
                () -> assertTrue(yaml.contains("check-interval-seconds: 25"), yaml),
                () -> assertTrue(yaml.contains("balance:"), "settings absent from the JSON get their defaults")
        );
    }

    @Test
    @DisplayName("conversion does not run when a YAML file already exists")
    void doesNotConvertOverAnExistingYaml() throws Exception {
        Files.writeString(directory.resolve("payment-processing.yml"), "check-interval-seconds: 7\n");
        Files.writeString(directory.resolve("payment-processing.json"),
                "{\"check_interval_seconds\": 25}");

        assertAll(
                () -> assertEquals(7L, Configs.paymentProcessing().get().checkIntervalSeconds()),
                () -> assertTrue(Files.isRegularFile(directory.resolve("payment-processing.json")),
                        "the JSON file is left alone")
        );
    }

    @Test
    @DisplayName("a legacy database.json converts with the plain snake-case rule")
    void convertsLegacyDatabaseJson() throws Exception {
        Files.writeString(directory.resolve("database.json"), """
                {
                  "jdbc_url" : "jdbc:postgresql://db:5432/payments",
                  "username" : "deploy-user",
                  "password" : "from-the-volume",
                  "maximum_pool_size" : 4,
                  "log_sql" : true
                }
                """);

        final DatabaseSpec config = Configs.database().get();

        assertAll(
                () -> assertEquals("jdbc:postgresql://db:5432/payments", config.jdbcUrl()),
                () -> assertEquals("deploy-user", config.username()),
                () -> assertEquals("from-the-volume", config.password()),
                () -> assertEquals(4, config.maximumPoolSize()),
                () -> assertTrue(config.logSql())
        );
    }

    // ------------------------------------------------------------- fail fast

    @Test
    @DisplayName("a mistyped setting stops the bot and says what was meant")
    void mistypedSettingStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("payment-processing.yml"), """
                check-interval-secondz: 10
                """);

        // The old code caught this, logged it and returned new PaymentProcessingConfig(), so the
        // bot ran against default channel ids for as long as nobody read the log.
        final UnknownConfigKeyException error =
                assertThrows(UnknownConfigKeyException.class, Configs::paymentProcessing);

        assertAll(
                () -> assertEquals("check-interval-secondz", error.unknownKeys().get(0).path()),
                () -> assertEquals("check-interval-seconds", error.unknownKeys().get(0).suggestion())
        );
    }

    @Test
    @DisplayName("a non-positive poll interval stops the bot")
    void nonPositiveIntervalStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("payment-processing.yml"), "check-interval-seconds: 0\n");

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, Configs::paymentProcessing);
        assertTrue(error.getMessage().contains("greater than zero"), error.getMessage());
    }

    @Test
    @DisplayName("a balance format without %s stops the bot")
    void badBalanceFormatStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("payment-processing.yml"), """
                check-interval-seconds: 10
                confirmation-channel-id: '1'
                balance:
                  channel-id: '2'
                  name-format: 'no placeholder here'
                """);

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, Configs::paymentProcessing);
        assertTrue(error.getMessage().contains("%s"), error.getMessage());
    }

    @Test
    @DisplayName("a non-PostgreSQL jdbc-url stops the bot")
    void wrongDatabaseUrlStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("database.yml"),
                "jdbc-url: jdbc:mysql://db:3306/payments\nusername: u\n");

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, Configs::database);
        assertTrue(error.getMessage().contains("PostgreSQL"), error.getMessage());
    }

    @Test
    @DisplayName("the bot refuses to start while the credentials are empty")
    void emptyCredentialsStopTheBot() {
        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::bot);

        assertAll(
                () -> assertTrue(error.getMessage().contains("token"), error.getMessage()),
                () -> assertTrue(error.getMessage().contains("NORDTAL_BOT_TOKEN"),
                        "the message has to name the variable to set: " + error.getMessage())
        );
    }

    @Test
    @DisplayName("a non-numeric bunq account id is caught at startup, not in the poll loop")
    void nonNumericAccountIdStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("bot.yml"), """
                token: a-token
                bunq:
                  api-key: a-key
                  account-id: not-a-number
                  context-path: ''
                """);

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, Configs::bot);
        assertTrue(error.getMessage().contains("must be a number"), error.getMessage());
    }

    @Test
    @DisplayName("a complete bot.yml loads")
    void completeBotConfigLoads() throws Exception {
        Files.writeString(directory.resolve("bot.yml"), """
                token: a-token
                bunq:
                  api-key: a-key
                  account-id: '1234'
                  context-path: ''
                """);

        final BotSpec config = Configs.bot().get();
        assertAll(
                () -> assertEquals("a-token", config.token()),
                () -> assertEquals("1234", config.bunq().accountId())
        );
    }

    @Test
    @DisplayName("a defaults file is written when nothing exists, and the secrets slot stays empty")
    void writesDefaultsWithoutSecrets() {
        assertThrows(ConfigValidationException.class, Configs::bot);

        final Path file = directory.resolve("bot.yml");
        assertAll(
                () -> assertTrue(Files.isRegularFile(file), "the defaults file is still written"),
                () -> assertTrue(Files.readString(file).contains("token: ''"),
                        "the token slot is written empty, never guessed"),
                () -> assertTrue(Files.readString(file).contains("LEAVE THESE EMPTY"),
                        "and the header says so")
        );
    }
}

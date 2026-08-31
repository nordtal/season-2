package eu.nordtal.s2.discordbot.config;

import eu.nordtal.jcore.config.exception.ConfigValidationException;
import eu.nordtal.jcore.config.exception.UnknownConfigKeyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fail-fast that replaced "log it and carry on with defaults".
 * <p>
 * Every test here is a value that used to be able to reach production: a mistyped key that the old
 * loader deleted silently, a bunq account id that was only parsed inside the poll loop, a channel
 * id nobody filled in. The point of the config layer is that none of them get past startup.
 * </p>
 * <p>
 * What these tests <b>cannot</b> prove: that the ids in a real {@code access.yml} point at the
 * channels and roles somebody meant. A snowflake is checked for being a snowflake, not for
 * existing - that only shows up against a real guild.
 * </p>
 */
class ConfigsTest {

    /** The agreed price list, as YAML. Substituted into {@link #access(String)}. */
    private static final String VALID_TIERS = """
            tiers:
            - days: 30
              price-cents: 300
            - days: 60
              price-cents: 500
            - days: 90
              price-cents: 700""";

    /** The agreed language list, as YAML. Substituted into {@link #access(String, String)}. */
    private static final String VALID_LANGUAGES = """
            languages:
            - tag: en
              role: '30'
              contribution-channel: '31'
              link-channel: '32'
            - tag: de
              role: '33'
              contribution-channel: '34'
              link-channel: '35'""";

    /**
     * A third language, to be appended to {@link #VALID_LANGUAGES}. Nothing in the bot knows the
     * tag {@code fr} exists; that is the point.
     */
    private static final String FRENCH = """

            - tag: fr
              role: '36'
              contribution-channel: '37'
              link-channel: '38'""";

    /**
     * Everything but the tiers and the languages, so a test about one setting does not trip over
     * the other twenty.
     * <p>
     * There are deliberately no {@code roles.german} / {@code roles.english} and no
     * {@code channels.contribution-*} / {@code channels.link-*} keys here. They were deleted on
     * 2026-08-31 when the {@code languages} list became the only source for them, and an
     * undeclared key stops the load - which {@link #retiredLanguageRolesStopTheBot()} asserts.
     * </p>
     */
    private static final String REST = """
            guild-id: '1'
            donation-cents: 500
            roles:
              access: '10'
              donor: '11'
              admin: '14'
              admin-ping: '15'
            channels:
              admin: '24'
            payment:
              poll-interval-seconds: 30
              request-ttl-hours: 24
              watermark: ''
              recent-payment-count: 50
            expiry-reminder-lead-days: 3
            role-reconcile-interval-minutes: 10
            """;

    /**
     * A complete access.yml with the given tiers and languages blocks.
     *
     * @param tiers     the {@code tiers:} section to use
     * @param languages the {@code languages:} section to use
     * @return the whole file
     */
    private static String access(final String tiers, final String languages) {
        return tiers + "\n" + languages + "\n" + REST;
    }

    /** A complete access.yml with the agreed language list and the given tiers. */
    private static String access(final String tiers) {
        return access(tiers, VALID_LANGUAGES);
    }

    /** A complete access.yml with the agreed price list and the given languages. */
    private static String languages(final String languages) {
        return access(VALID_TIERS, languages);
    }

    /** A complete, valid access.yml. */
    private static String access() {
        return access(VALID_TIERS, VALID_LANGUAGES);
    }

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

    // ------------------------------------------------------------- access.yml

    @Test
    @DisplayName("a complete access.yml loads, with the prices as integer cents")
    void completeAccessConfigLoads() throws Exception {
        Files.writeString(directory.resolve("access.yml"), access());

        final AccessSpec config = Configs.access().get();

        assertAll(
                () -> assertEquals("1", config.guildId()),
                () -> assertEquals(3, config.tiers().size()),
                () -> assertEquals(30, config.tiers().getFirst().days()),
                () -> assertEquals(700, config.tiers().getLast().priceCents()),
                () -> assertEquals(500, config.donationCents()),
                () -> assertEquals("10", config.roles().access()),
                () -> assertEquals("14", config.roles().admin()),
                () -> assertEquals("24", config.channels().admin()),
                () -> assertEquals(24, config.payment().requestTtlHours())
        );
    }

    @Test
    @DisplayName("the bot refuses to start while a channel id is empty")
    void emptyChannelIdStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("access.yml"), access().replace("admin: '24'", "admin: ''"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("channels.admin"), error.getMessage());
    }

    @Test
    @DisplayName("the bot refuses to start while the admin role id is empty")
    void emptyAdminRoleStopsTheBot() throws Exception {
        // The flag this role is mirrored into authorises /phase set and admission during
        // MAINTENANCE. An unset id would mirror "nobody is an admin" onto everybody, silently.
        Files.writeString(directory.resolve("access.yml"), access().replace("admin: '14'", "admin: ''"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("roles.admin"), error.getMessage());
    }

    @Test
    @DisplayName("a role id that is not a snowflake stops the bot")
    void nonNumericRoleIdStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("access.yml"),
                access().replace("access: '10'", "access: '<@&10>'"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("roles.access"), error.getMessage());
    }

    @Test
    @DisplayName("a longer tier that costs less stops the bot")
    void unorderedTiersStopTheBot() throws Exception {
        // A shortfall is settled by walking down to the highest tier the amount covers, which is
        // only the answer a human would give if more days cost more money.
        Files.writeString(directory.resolve("access.yml"),
                access(VALID_TIERS.replace("- days: 60\n  price-cents: 500", "- days: 60\n  price-cents: 900")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("more expensive as they get longer"), error.getMessage());
    }

    @Test
    @DisplayName("two tiers offering the same number of days stop the bot")
    void duplicateDayCountsStopTheBot() throws Exception {
        // A purchase button carries a day count, so two tiers with the same one is an ambiguous
        // lookup rather than a cosmetic mistake.
        Files.writeString(directory.resolve("access.yml"),
                access(VALID_TIERS.replace("- days: 90", "- days: 30")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("Day counts identify a tier"), error.getMessage());
    }

    @Test
    @DisplayName("an empty tier list stops the bot and prints the shape to write")
    void emptyTiersStopTheBotWithTheShape() throws Exception {
        Files.writeString(directory.resolve("access.yml"), access("tiers: []"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertAll(
                () -> assertTrue(error.getMessage().contains("nothing to buy"), error.getMessage()),
                () -> assertTrue(error.getMessage().contains("price-cents: 300"),
                        "the message has to show what to write: " + error.getMessage()),
                () -> assertTrue(error.getMessage().contains("access.yml"),
                        "and name the file: " + error.getMessage())
        );
    }

    // ------------------------------------------------------------- the language list

    @Test
    @DisplayName("the language list loads with its tags, role and channels")
    void languageListLoads() throws Exception {
        Files.writeString(directory.resolve("access.yml"), access());

        final AccessSpec config = Configs.access().get();
        assertAll(
                () -> assertEquals(2, config.languages().size()),
                () -> assertEquals("en", config.languages().getFirst().tag()),
                () -> assertEquals("30", config.languages().getFirst().role()),
                () -> assertEquals("32", config.languages().getFirst().linkChannel()),
                () -> assertEquals("de", config.languages().getLast().tag()),
                () -> assertEquals("34", config.languages().getLast().contributionChannel())
        );
    }

    @Test
    @DisplayName("a language list without 'en' stops the bot and prints the shape to write")
    void missingEnglishStopsTheBot() throws Exception {
        // English is the floor every missing translation degrades to. Without it a missing key has
        // nowhere to fall back to, and that would surface on a disconnect screen rather than here.
        Files.writeString(directory.resolve("access.yml"), languages("""
                languages:
                - tag: de
                  role: '33'
                  contribution-channel: '34'
                  link-channel: '35'"""));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertAll(
                () -> assertTrue(error.getMessage().contains("no 'en' entry"), error.getMessage()),
                () -> assertTrue(error.getMessage().contains("tag: en"),
                        "the message has to show what to write: " + error.getMessage())
        );
    }

    @Test
    @DisplayName("an empty language list stops the bot")
    void emptyLanguageListStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("access.yml"), languages("languages: []"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertAll(
                () -> assertTrue(error.getMessage().contains("languages is empty"), error.getMessage()),
                () -> assertTrue(error.getMessage().contains("link-channel"),
                        "the message has to show the whole entry: " + error.getMessage())
        );
    }

    @Test
    @DisplayName("two entries with the same tag stop the bot")
    void duplicateLanguageTagsStopTheBot() throws Exception {
        // A tag is the bundle file name and the value in discord_user.locale, so two entries
        // claiming one is an ambiguous lookup rather than a cosmetic mistake.
        Files.writeString(directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace("- tag: de", "- tag: en")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("Tags identify a language"), error.getMessage());
    }

    @Test
    @DisplayName("an upper-case tag stops the bot")
    void upperCaseLanguageTagStopsTheBot() throws Exception {
        // Nothing downstream case-folds a .properties file name.
        Files.writeString(directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace("- tag: de", "- tag: DE")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("must be lower case"), error.getMessage());
    }

    @Test
    @DisplayName("an empty id on a language entry stops the bot, naming the entry")
    void emptyLanguageChannelStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace("link-channel: '35'", "link-channel: ''")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("languages[1].link-channel"), error.getMessage());
    }

    @Test
    @DisplayName("a tag too long for managed_message.kind stops the bot")
    void anOverlongLanguageTagStopsTheBot() throws Exception {
        // Not a rule about languages: managed_message.kind is varchar(32) and the bot writes
        // "CONTRIBUTION_<TAG>" into it. Caught here rather than as an INSERT failure at startup.
        Files.writeString(directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace("- tag: de", "- tag: " + "a".repeat(20))));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("as long as a managed message's key"), error.getMessage());
    }

    // ------------------------------------------------------------- the list is the only source

    @Test
    @DisplayName("the retired roles.german / roles.english are not settings of this file any more")
    void retiredLanguageRolesStopTheBot() throws Exception {
        // The list carries each language's role. Leaving the old pair declared alongside it is what
        // made access.yml have two sources of truth for the same ids; deleting a key is only free
        // while nothing is deployed, because an undeclared key stops the load.
        Files.writeString(directory.resolve("access.yml"),
                access().replace("  donor: '11'", "  donor: '11'\n  german: '12'"));

        final UnknownConfigKeyException error =
                assertThrows(UnknownConfigKeyException.class, Configs::access);
        assertEquals("roles.german", error.unknownKeys().getFirst().path());
    }

    @Test
    @DisplayName("the retired fixed contribution and link channels are not settings any more")
    void retiredLanguageChannelsStopTheBot() throws Exception {
        Files.writeString(directory.resolve("access.yml"),
                access().replace("  admin: '24'", "  contribution-en: '20'\n  admin: '24'"));

        final UnknownConfigKeyException error =
                assertThrows(UnknownConfigKeyException.class, Configs::access);
        assertEquals("channels.contribution-en", error.unknownKeys().getFirst().path());
    }

    @Test
    @DisplayName("a third language is a config edit and nothing else")
    void aThirdLanguageIsPurelyAConfigEdit() throws Exception {
        // The whole point of the list (docs/i18n.md). Nothing in the bot's source mentions 'fr':
        // the file below is the entire change, and everything the bot does per language comes out
        // of it - the role it mirrors, the two channels it posts in, the bundle it loads, and the
        // managed_message keys it remembers its own messages under.
        Files.writeString(directory.resolve("access.yml"), languages(VALID_LANGUAGES + FRENCH));

        final Languages languages = Languages.of(Configs.access().get());
        final Languages.Language french = languages.byTag("fr").orElseThrow();

        assertAll(
                () -> assertEquals(3, languages.all().size()),
                () -> assertEquals(Locale.FRENCH, french.locale()),
                () -> assertEquals("fr", languages.resolve(Set.of("36")).orElseThrow().tag()),
                () -> assertEquals("37", french.contributionChannelId()),
                () -> assertEquals("38", french.linkChannelId()),
                () -> assertEquals("CONTRIBUTION_FR", french.contributionKind()),
                () -> assertArrayEquals(new Locale[]{Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH},
                        languages.locales()),
                // ...and the two that already existed still behave exactly as they did.
                () -> assertEquals("de", languages.resolve(Set.of("33")).orElseThrow().tag()),
                () -> assertEquals("31", languages.forLocale(Locale.ENGLISH).contributionChannelId())
        );
    }

    @Test
    @DisplayName("an empty watermark is the normal case - the bot stamps its own")
    void emptyWatermarkIsAccepted() throws Exception {
        Files.writeString(directory.resolve("access.yml"), access());

        assertEquals("", Configs.access().get().payment().watermark());
    }

    @Test
    @DisplayName("a watermark override that is not an instant stops the bot")
    void badWatermarkStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("access.yml"),
                access().replace("watermark: ''", "watermark: '1 September 2026'"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("ISO-8601"), error.getMessage());
    }

    @Test
    @DisplayName("a mistyped setting stops the bot and says what was meant")
    void mistypedSettingStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("access.yml"),
                access().replace("donation-cents:", "donation-cent:"));

        // jcore's predecessor deleted a key it did not know, so a typo cost both the setting and
        // any trace of it.
        final UnknownConfigKeyException error =
                assertThrows(UnknownConfigKeyException.class, Configs::access);

        assertAll(
                () -> assertEquals("donation-cent", error.unknownKeys().getFirst().path()),
                () -> assertEquals("donation-cents", error.unknownKeys().getFirst().suggestion())
        );
    }

    @Test
    @DisplayName("the retired link-code-ttl-minutes is not a setting of this file any more")
    void retiredLinkCodeTtlStopsTheBot() throws Exception {
        // gate.yml carries the only link-code TTL (decided 2026-08-31). The bot never read its own
        // copy, and an undeclared key stops the load - which is why the deletion was only free
        // while nothing is deployed.
        Files.writeString(directory.resolve("access.yml"), access() + "link-code-ttl-minutes: 10\n");

        final UnknownConfigKeyException error =
                assertThrows(UnknownConfigKeyException.class, Configs::access);
        assertEquals("link-code-ttl-minutes", error.unknownKeys().getFirst().path());
    }

    @Test
    @DisplayName("a defaults access.yml is written, and it cannot start the bot")
    void defaultsAreWrittenButRefused() {
        // Season 1 shipped real channel and role ids as defaults, so a config that failed to load
        // wrote into somebody's production channel. Empty ids cannot do that.
        assertThrows(ConfigValidationException.class, Configs::access);

        final Path file = directory.resolve("access.yml");
        assertAll(
                () -> assertTrue(Files.isRegularFile(file)),
                () -> assertTrue(Files.readString(file).contains("access: ''"),
                        "the role ids are written empty, never guessed"),
                () -> assertTrue(Files.readString(file).contains("price-cents: 300"),
                        "but the price list is written in full - a fresh install is ready to sell"),
                () -> assertTrue(Files.readString(file).contains("price-cents: 700"),
                        "all three tiers, not just the first"),
                // jcore initialises a List<NestedSpec> to empty, so without DefaultLanguages this
                // comes out as "languages: []" and a fresh install has no language at all.
                () -> assertTrue(Files.readString(file).contains("tag: en"),
                        "the fallback language is written: " + read(file)),
                () -> assertTrue(Files.readString(file).contains("tag: de"),
                        "and so is German - both entries, not an empty list: " + read(file)),
                () -> assertTrue(Files.readString(file).contains("link-channel: ''"),
                        "with their ids empty, exactly like every other id")
        );
    }

    private static String read(final Path file) throws Exception {
        return Files.readString(file);
    }

    // ------------------------------------------------------------- bot.yml

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
                  environment: PRODUCTION
                  context-path: ''
                """);

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, Configs::bot);
        assertTrue(error.getMessage().contains("must be a number"), error.getMessage());
    }

    @Test
    @DisplayName("an unknown bunq environment stops the bot")
    void badBunqEnvironmentStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("bot.yml"), """
                token: a-token
                bunq:
                  api-key: a-key
                  account-id: '1234'
                  environment: staging
                  context-path: ''
                """);

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, Configs::bot);
        assertTrue(error.getMessage().contains("PRODUCTION or SANDBOX"), error.getMessage());
    }

    @Test
    @DisplayName("a complete bot.yml loads, including the sandbox switch")
    void completeBotConfigLoads() throws Exception {
        Files.writeString(directory.resolve("bot.yml"), """
                token: a-token
                bunq:
                  api-key: a-key
                  account-id: '1234'
                  environment: SANDBOX
                  context-path: ''
                """);

        final BotSpec config = Configs.bot().get();
        assertAll(
                () -> assertEquals("a-token", config.token()),
                () -> assertEquals("1234", config.bunq().accountId()),
                () -> assertEquals("SANDBOX", config.bunq().environment())
        );
    }

    @Test
    @DisplayName("a defaults bot.yml is written and the secrets slot stays empty")
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

    // ------------------------------------------------------------- database.yml

    @Test
    @DisplayName("a non-PostgreSQL jdbc-url stops the bot")
    void wrongDatabaseUrlStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("database.yml"),
                "jdbc-url: jdbc:mysql://db:3306/access\nusername: u\n");

        final ConfigValidationException error =
                assertThrows(ConfigValidationException.class, Configs::database);
        assertTrue(error.getMessage().contains("PostgreSQL"), error.getMessage());
    }
}

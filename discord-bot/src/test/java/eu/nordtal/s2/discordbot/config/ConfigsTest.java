package eu.nordtal.s2.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.exception.ConfigValidationException;
import eu.nordtal.jcore.config.exception.UnknownConfigKeyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fail-fast that replaced "log it and carry on with defaults".
 *
 * Every test here is a value that used to be able to reach production: a mistyped key that the old loader deleted
 * silently, a bunq account id that was only parsed inside the poll loop, a channel id nobody filled in. The point of
 * the config layer is that none of them get past startup.
 *
 * What these tests cannot prove: that the ids in a real {@code access.yml} point at the channels and roles somebody
 * meant. A snowflake is checked for being a snowflake, not for existing - that only shows up against a real guild.
 *
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
              hunger-games-channel: '39'
            - tag: de
              role: '33'
              contribution-channel: '34'
              link-channel: '35'
              hunger-games-channel: '40'""";

    /**
     * A third language, to be appended to {@link #VALID_LANGUAGES}.
     *
     * Nothing in the bot knows the tag {@code fr} exists; that is the point.
     */
    private static final String FRENCH = """

            - tag: fr
              role: '36'
              contribution-channel: '37'
              link-channel: '38'
              hunger-games-channel: '41'""";

    /**
     * Everything but the tiers and the languages, so a test about one setting does not trip over the other twenty.
     *
     * There are deliberately no {@code roles.german} / {@code roles.english} and no {@code channels.contribution-*} /
     * {@code channels.link-*} keys here: the {@code languages} list is the only source for them, and an undeclared
     * key stops the load - which
     * {@link #theRetiredRolesGermanRolesEnglishAreDeletedFromTheFileNotArguedWith()} asserts.
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

    // Access.yml.

    @Test
    void aCompleteAccessYmlLoadsWithThePricesAsIntegerCents() throws Exception {
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
                () -> assertEquals(24, config.payment().requestTtlHours()));
    }

    @Test
    void noAdminChannelIsABotThatStartsAndLogsItsAlertsInstead() throws Exception {
        // A deployment with a guild but no admin channel yet still has to come up to say what else is missing.
        Files.writeString(directory.resolve("access.yml"), access().replace("admin: '24'", "admin: ''"));

        assertEquals("", Configs.access().get().channels().admin());
    }

    @Test
    void anAdminChannelThatIsPresentStillHasToBeASnowflake() throws Exception {
        // Optional and lenient are not the same thing. Empty is a decision; `<#24>` is a paste.
        Files.writeString(directory.resolve("access.yml"), access().replace("admin: '24'", "admin: '<#24>'"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("channels.admin"), error.getMessage());
    }

    @Test
    void theGuildIdIsOneOfTheTwoTheBotCannotStartWithout() throws Exception {
        // The other is roles.admin: together they say where the bot lives and who may administer it.
        Files.writeString(directory.resolve("access.yml"), access().replace("guild-id: '1'", "guild-id: ''"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("guild-id"), error.getMessage());
    }

    @Test
    void everyRoleButTheAdminOneMayBeLeftEmptyAndTheBotStillStarts() throws Exception {
        Files.writeString(
                directory.resolve("access.yml"),
                access().replace("access: '10'", "access: ''")
                        .replace("donor: '11'", "donor: ''")
                        .replace("admin-ping: '15'", "admin-ping: ''"));

        final AccessSpec config = Configs.access().get();

        assertAll(
                () -> assertEquals("", config.roles().access()),
                () -> assertEquals("", config.roles().donor()),
                () -> assertEquals("", config.roles().adminPing()),
                () -> assertEquals("14", config.roles().admin()));
    }

    @Test
    void theBotRefusesToStartWhileTheAdminRoleIdIsEmpty() throws Exception {
        // The flag this role mirrors into authorises /phase set and admission during MAINTENANCE.
        Files.writeString(directory.resolve("access.yml"), access().replace("admin: '14'", "admin: ''"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("roles.admin"), error.getMessage());
    }

    @Test
    void aRoleIdThatIsNotASnowflakeStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("access.yml"), access().replace("access: '10'", "access: '<@&10>'"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("roles.access"), error.getMessage());
    }

    @Test
    void aLongerTierThatCostsLessStopsTheBot() throws Exception {
        // A shortfall walks down to the highest tier the amount covers - the answer if longer costs more.
        Files.writeString(
                directory.resolve("access.yml"),
                access(VALID_TIERS.replace("- days: 60\n  price-cents: 500", "- days: 60\n  price-cents: 900")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("more expensive as they get longer"), error.getMessage());
    }

    @Test
    void twoTiersOfferingTheSameNumberOfDaysStopTheBot() throws Exception {
        // A purchase button carries a day count, so two tiers sharing one is an ambiguous lookup.
        Files.writeString(directory.resolve("access.yml"), access(VALID_TIERS.replace("- days: 90", "- days: 30")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("Day counts identify a tier"), error.getMessage());
    }

    @Test
    void anEmptyTierListIsADeploymentThatHasNotPricedAnythingYet() throws Exception {
        // Prices are a decision made in the interface after the stack is up, not a precondition of being up.
        Files.writeString(directory.resolve("access.yml"), access("tiers: []"));

        assertTrue(Configs.access().get().tiers().isEmpty());
    }

    // The language list.

    @Test
    void theLanguageListLoadsWithItsTagsRoleAndChannels() throws Exception {
        Files.writeString(directory.resolve("access.yml"), access());

        final AccessSpec config = Configs.access().get();
        assertAll(
                () -> assertEquals(2, config.languages().size()),
                () -> assertEquals("en", config.languages().getFirst().tag()),
                () -> assertEquals("30", config.languages().getFirst().role()),
                () -> assertEquals("32", config.languages().getFirst().linkChannel()),
                () -> assertEquals("de", config.languages().getLast().tag()),
                () -> assertEquals("34", config.languages().getLast().contributionChannel()),
                () -> assertEquals("40", config.languages().getLast().hungerGamesChannel()));
    }

    @Test
    void aLanguageListWithoutEnStopsTheBotAndPrintsTheShapeToWrite() throws Exception {
        // English is the floor every missing translation degrades to; without it the failure surfaces elsewhere.
        Files.writeString(directory.resolve("access.yml"), languages("""
                languages:
                - tag: de
                  role: '33'
                  contribution-channel: '34'
                  link-channel: '35'
                  hunger-games-channel: '40'"""));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertAll(
                () -> assertTrue(error.getMessage().contains("no 'en' entry"), error.getMessage()),
                () -> assertTrue(
                        error.getMessage().contains("tag: en"),
                        "the message has to show what to write: " + error.getMessage()));
    }

    @Test
    void anEmptyLanguageListStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("access.yml"), languages("languages: []"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertAll(
                () -> assertTrue(error.getMessage().contains("languages is empty"), error.getMessage()),
                () -> assertTrue(
                        error.getMessage().contains("link-channel"),
                        "the message has to show the whole entry: " + error.getMessage()));
    }

    @Test
    void twoEntriesWithTheSameTagStopTheBot() throws Exception {
        // A tag is the bundle file name and the value in discord_user.locale, so two entries claiming one is ambiguous.
        Files.writeString(
                directory.resolve("access.yml"), languages(VALID_LANGUAGES.replace("- tag: de", "- tag: en")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("Tags identify a language"), error.getMessage());
    }

    @Test
    void anUpperCaseTagStopsTheBot() throws Exception {
        // Nothing downstream case-folds a .properties file name.
        Files.writeString(
                directory.resolve("access.yml"), languages(VALID_LANGUAGES.replace("- tag: de", "- tag: DE")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("must be lower case"), error.getMessage());
    }

    @Test
    void aLanguageEntryWhoseIdsAreAllEmptyIsALanguageThatServesNothing() throws Exception {
        // Empty switches the thing it names off - no link message in this language - and Configured lists it.
        Files.writeString(
                directory.resolve("access.yml"),
                languages(VALID_LANGUAGES
                        .replace("role: '33'", "role: ''")
                        .replace("contribution-channel: '34'", "contribution-channel: ''")
                        .replace("link-channel: '35'", "link-channel: ''")
                        .replace("hunger-games-channel: '40'", "hunger-games-channel: ''")));

        final AccessSpec.LanguageSpec german =
                Configs.access().get().languages().get(1);

        assertAll(
                () -> assertEquals("", german.role()),
                () -> assertEquals("", german.contributionChannel()),
                () -> assertEquals("", german.linkChannel()),
                () -> assertEquals("", german.hungerGamesChannel()));
    }

    @Test
    void aLanguageIdThatIsPresentStillHasToBeASnowflakeNamingTheEntry() throws Exception {
        // The leniency is about emptiness only: an unresolvable channel and an unconfigured one are not the same.
        Files.writeString(
                directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace("link-channel: '35'", "link-channel: '<#35>'")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("languages[1].link-channel"), error.getMessage());
    }

    @Test
    void aLanguageWithNoStatusChannelIsALanguageWithNoStatusChannel() throws Exception {
        // The one optional id in the file; VALID_LANGUAGES does not carry it at all.
        Files.writeString(directory.resolve("access.yml"), languages(VALID_LANGUAGES));

        final AccessSpec config = Configs.access().get();

        assertEquals("", config.languages().getFirst().statusChannel());
        assertFalse(Languages.of(config).all().getFirst().hasStatusChannel());
    }

    @Test
    void aLanguageWithNoAnnouncementChannelGetsNoAnnouncementsAndTheBotStarts() throws Exception {
        // The second optional id: the servers' announce rows for this language settle as "no channel".
        Files.writeString(directory.resolve("access.yml"), languages(VALID_LANGUAGES));

        final AccessSpec config = Configs.access().get();

        assertEquals("", config.languages().getFirst().announcementChannel());
        assertFalse(Languages.of(config).all().getFirst().hasAnnouncementChannel());
    }

    @Test
    void anAnnouncementChannelThatIsSetHasToBeARealSnowflake() throws Exception {
        Files.writeString(
                directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace(
                        "hunger-games-channel: '40'",
                        "hunger-games-channel: '40'\n  announcement-channel: 'not-an-id'")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("languages[1].announcement-channel"), error.getMessage());
    }

    @Test
    void aStatusChannelThatIsSetHasToBeARealSnowflake() throws Exception {
        // Lenient about absent must not become lenient about wrong: an unresolvable id fails silently otherwise.
        Files.writeString(
                directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace(
                        "hunger-games-channel: '40'", "hunger-games-channel: '40'\n  status-channel: 'not-an-id'")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("languages[1].status-channel"), error.getMessage());
    }

    @Test
    void aLinkCodeAttemptCapOfZeroWouldLockEverybodyOutAndIsRefused() throws Exception {
        Files.writeString(
                directory.resolve("access.yml"),
                access().replace(
                                "role-reconcile-interval-minutes: 10",
                                "role-reconcile-interval-minutes: 10\nlink-code-attempts-per-hour: 0"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("link-code-attempts-per-hour"), error.getMessage());
    }

    @Test
    void aTagTooLongForManagedMessageKindStopsTheBot() throws Exception {
        // Not a rule about languages: managed_message.kind is varchar(32) and holds "CONTRIBUTION_<TAG>".
        Files.writeString(
                directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace("- tag: de", "- tag: " + "a".repeat(20))));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("as long as a managed message's key"), error.getMessage());
    }

    // The list is the only source.

    @Test
    void theRetiredRolesGermanRolesEnglishAreDeletedFromTheFileNotArguedWith() throws Exception {
        // The list is the only source for a language's role; a re-declared `german:` key is deleted, not refused.
        Files.writeString(
                directory.resolve("access.yml"), access().replace("  donor: '11'", "  donor: '11'\n  german: '12'"));

        final AccessSpec config = Configs.access().get();

        assertAll(
                () -> assertFalse(
                        Files.readString(directory.resolve("access.yml")).contains("german:"),
                        "the retired key has to be gone from the file"),
                () -> assertTrue(
                        Files.readString(directory.resolve("access.yml.bak")).contains("german:"),
                        "and recoverable from the backup, because it carried an id"),
                () -> assertEquals(
                        2, config.languages().size(), "the list is still the only source for the language roles"));
    }

    @Test
    void theRetiredFixedContributionAndLinkChannelsAreDeletedFromTheFile() throws Exception {
        Files.writeString(
                directory.resolve("access.yml"),
                access().replace("  admin: '24'", "  contribution-en: '20'\n  admin: '24'"));

        final AccessSpec config = Configs.access().get();

        assertAll(
                () -> assertFalse(
                        Files.readString(directory.resolve("access.yml")).contains("contribution-en:")),
                () -> assertEquals("24", config.channels().admin(), "the sibling ids are not collateral"));
    }

    @Test
    void aThirdLanguageIsAConfigEditAndNothingElse() throws Exception {
        // Nothing in the bot's source mentions 'fr': the file below is the entire change per language.
        Files.writeString(directory.resolve("access.yml"), languages(VALID_LANGUAGES + FRENCH));

        final Languages languages = Languages.of(Configs.access().get());
        final Languages.Language french = languages.byTag("fr").orElseThrow();

        assertAll(
                () -> assertEquals(3, languages.all().size()),
                () -> assertEquals(Locale.FRENCH, french.locale()),
                () -> assertEquals(
                        "fr", languages.resolve(Set.of("36")).orElseThrow().tag()),
                () -> assertEquals("37", french.contributionChannelId()),
                () -> assertEquals("38", french.linkChannelId()),
                () -> assertEquals("41", french.hungerGamesChannelId()),
                () -> assertEquals("CONTRIBUTION_FR", french.contributionKind()),
                () -> assertEquals("HG_REGISTER_FR", french.hungerGamesRegisterKind()),
                () -> assertArrayEquals(
                        new Locale[] {Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH}, languages.locales()),
                // ...and the two that already existed still behave exactly as they did.
                () -> assertEquals(
                        "de", languages.resolve(Set.of("33")).orElseThrow().tag()),
                () -> assertEquals("31", languages.forLocale(Locale.ENGLISH).contributionChannelId()));
    }

    @Test
    void aDeployedAccessYmlCarryingTheMovedPaymentKeysLosesThemAndKeepsTheBot() throws Exception {
        // payment.watermark and payment.recent-payment-count belong to steward-worker's bunq block, not this one.
        Files.writeString(directory.resolve("access.yml"), access().replace("""
                        payment:
                          poll-interval-seconds: 30
                        """, """
                        payment:
                          poll-interval-seconds: 30
                          watermark: '2026-09-01T00:00:00Z'
                          recent-payment-count: 50
                        """));

        Configs.access();

        final String written = Files.readString(directory.resolve("access.yml"));
        assertFalse(
                written.lines().anyMatch(line -> line.strip().startsWith("watermark:")),
                "access.yml still carries payment.watermark after a load - something re-declared"
                        + " it in AccessSpec: " + written);
        assertFalse(
                written.lines().anyMatch(line -> line.strip().startsWith("recent-payment-count:")),
                "access.yml still carries payment.recent-payment-count after a load: " + written);
    }

    @Test
    void aMistypedSettingStopsTheBotAndSaysWhatWasMeant() throws Exception {
        Files.writeString(directory.resolve("access.yml"), access().replace("donation-cents:", "donation-cent:"));

        // jcore keeps the trace of a typo rather than deleting the key it does not know, as the old loader did.
        final UnknownConfigKeyException error = assertThrows(UnknownConfigKeyException.class, Configs::access);

        assertAll(
                () -> assertEquals(
                        "donation-cent", error.unknownKeys().getFirst().path()),
                () -> assertEquals(
                        "donation-cents", error.unknownKeys().getFirst().suggestion()));
    }

    @Test
    void theRetiredLinkCodeTtlMinutesIsDeletedFromTheFileAndGateYmlKeepsTheOnlyOne() throws Exception {
        // gate.yml carries the only link-code TTL; a deployed access.yml still carrying the key means nothing.
        Files.writeString(directory.resolve("access.yml"), access() + "link-code-ttl-minutes: 10\n");

        Configs.access();

        assertFalse(
                Files.readString(directory.resolve("access.yml")).contains("link-code-ttl-minutes"),
                "the key is gone rather than sitting in the file looking like a setting");
    }

    @Test
    void aDefaultsAccessYmlIsWrittenAndItCannotStartTheBot() {
        // Real channel and role ids as defaults would let a config that failed to load post into a real channel.
        assertThrows(ConfigValidationException.class, Configs::access);

        final Path file = directory.resolve("access.yml");
        assertAll(
                () -> assertTrue(Files.isRegularFile(file)),
                () -> assertTrue(
                        Files.readString(file).contains("access: ''"), "the role ids are written empty, never guessed"),
                () -> assertTrue(
                        Files.readString(file).contains("price-cents: 300"),
                        "but the price list is written in full - a fresh install is ready to sell"),
                () -> assertTrue(
                        Files.readString(file).contains("price-cents: 700"), "all three tiers, not just the first"),
                // jcore initialises a List<NestedSpec> to empty; without DefaultLanguages a fresh install has none.
                () -> assertTrue(
                        Files.readString(file).contains("tag: en"), "the fallback language is written: " + read(file)),
                () -> assertTrue(
                        Files.readString(file).contains("tag: de"),
                        "and so is German - both entries, not an empty list: " + read(file)),
                () -> assertTrue(
                        Files.readString(file).contains("link-channel: ''"),
                        "with their ids empty, exactly like every other id"),
                () -> assertTrue(
                        Files.readString(file).contains("hunger-games-channel: ''"),
                        "the hunger games channel id too"));
    }

    private static String read(final Path file) throws Exception {
        return Files.readString(file);
    }

    // Bot.yml.

    @Test
    void theBotRefusesToStartWhileTheCredentialsAreEmpty() {
        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::bot);

        assertAll(
                () -> assertTrue(error.getMessage().contains("token"), error.getMessage()),
                () -> assertTrue(
                        error.getMessage().contains("NORDTAL_BOT_TOKEN"),
                        "the message has to name the variable to set: " + error.getMessage()));
    }

    @Test
    void aDeployedBotYmlStillCarryingTheWholeBunqBlockLosesItAndKeepsTheBot() throws Exception {
        // The bunq block belongs to steward-worker; a deployed bot.yml carrying it costs only a WARN and a .bak.
        Files.writeString(directory.resolve("bot.yml"), """
                token: a-token
                bunq:
                  api-key: a-key
                  account-id: '1234'
                  context-path: ''
                  environment: SANDBOX
                """);

        assertEquals("a-token", Configs.bot().get().token());

        final String written = Files.readString(directory.resolve("bot.yml"));
        assertFalse(
                written.lines().anyMatch(line -> line.strip().startsWith("bunq:")),
                "bot.yml still carries the bunq block after a load. jcore drops a key the interface"
                        + " does not declare - if it survived, something declared it again: " + written);
        assertFalse(written.contains("a-key"), "the bunq API key survived into the rewritten bot.yml: " + written);
    }

    @Test
    void aDefaultsBotYmlIsWrittenAndTheSecretsSlotStaysEmpty() {
        assertThrows(ConfigValidationException.class, Configs::bot);

        final Path file = directory.resolve("bot.yml");
        // The header is not in the YAML; jcore puts the file-level @ConfigSpec(header) into the schema instead.
        final Path schema = directory.resolve("bot.schema.json");
        assertAll(
                () -> assertTrue(Files.isRegularFile(file), "the defaults file is still written"),
                () -> assertTrue(
                        Files.readString(file).contains("token: ''"), "the token slot is written empty, never guessed"),
                () -> assertTrue(
                        Files.isRegularFile(schema),
                        "the schema is written beside it, under the config's own base name"),
                // "THIS" and not "THESE": bot.yml has one setting, the token; the bunq credentials are elsewhere.
                () -> assertTrue(
                        Files.readString(schema).contains("LEAVE THIS EMPTY"),
                        "and the schema's root explanation carries the header that says so"),
                () -> assertTrue(
                        Files.readString(schema).contains("NORDTAL_STEWARD_BUNQ_API_KEY"),
                        "the header also has to say where the bunq key went, because the one thing"
                                + " an operator will look for in bot.yml is the setting that is no"
                                + " longer in it"),
                () -> assertFalse(
                        Files.readString(file).contains("LEAVE THIS EMPTY"),
                        "the YAML itself stays comment-free - that is what jcore 4.0.0 decided"));
    }

    // Database.yml.

    @Test
    void aNonPostgresqlJdbcUrlStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("database.yml"), "jdbc-url: jdbc:mysql://db:3306/access\nusername: u\n");

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::database);
        assertTrue(error.getMessage().contains("PostgreSQL"), error.getMessage());
    }

    /**
     * Every loaded config file has a {@code *.schema.json} written beside it.
     *
     * A file with no schema falls back to steward-worker's plain leaf-key reading, which is a weaker guard than the
     * schema gives.
     *
     * {@code ConfigHandle} 's load writes {@code <name>.schema.json} unconditionally on every load, whether or not the
     * load ends up throwing in the validator - the schema write happens before the validator runs. This pins that this
     * module's own loading code, which calls {@link ConfigLoader#builder} exactly the way {@code smp} and every other
     * module does, keeps doing so.
     */
    @Test
    void everyConfigFileThisModuleWritesGetsASchemaJsonBesideIt() {
        swallowValidationFailure(Configs::access);
        swallowValidationFailure(Configs::bot);
        swallowValidationFailure(Configs::database);

        assertAll(
                () -> assertTrue(
                        Files.isRegularFile(directory.resolve("access.schema.json")),
                        "access.yml has no access.schema.json beside it"),
                () -> assertTrue(
                        Files.isRegularFile(directory.resolve("bot.schema.json")),
                        "bot.yml has no bot.schema.json beside it"),
                () -> assertTrue(
                        Files.isRegularFile(directory.resolve("database.schema.json")),
                        "database.yml has no database.schema.json beside it"));
    }

    /**
     * Runs a config loader and discards a validation failure.
     *
     * This test is only about the file and its schema having been written, which jcore does before the validator
     * ever runs, not about whether the freshly written defaults are themselves acceptable (they usually are not:
     * an empty token or guild id is refused by design).
     */
    private static void swallowValidationFailure(final ThrowingCall call) {
        try {
            call.run();
        } catch (final ConfigValidationException expectedForFreshDefaults) {
            // Ignored on purpose - see the Javadoc above.
        } catch (final ConfigException unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws ConfigException;
    }
}

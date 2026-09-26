package eu.nordtal.s2.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.jcore.config.exception.ConfigValidationException;
import eu.nordtal.jcore.config.exception.UnknownConfigKeyException;
import eu.nordtal.jcore.config.spec.annotation.Protected;
import eu.nordtal.s2.common.config.EnvOverrideFile;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
              hunger-games-channel: '39'
            - tag: de
              role: '33'
              contribution-channel: '34'
              link-channel: '35'
              hunger-games-channel: '40'""";

    /**
     * A third language, to be appended to {@link #VALID_LANGUAGES}. Nothing in the bot knows the
     * tag {@code fr} exists; that is the point.
     */
    private static final String FRENCH = """

            - tag: fr
              role: '36'
              contribution-channel: '37'
              link-channel: '38'
              hunger-games-channel: '41'""";

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
                () -> assertEquals(24, config.payment().requestTtlHours()));
    }

    @Test
    @DisplayName("no admin channel is a bot that starts and logs its alerts instead")
    void anEmptyAdminChannelIsAllowed() throws Exception {
        // This USED to stop the bot, and that was the wrong end of the stick: a deployment that has
        // a guild but has not made an admin channel yet cannot come up to be told what else is
        // missing. The channel is a feature, and AdminLog writes its alerts to the log without one.
        Files.writeString(directory.resolve("access.yml"), access().replace("admin: '24'", "admin: ''"));

        assertEquals("", Configs.access().get().channels().admin());
    }

    @Test
    @DisplayName("an admin channel that is present still has to be a snowflake")
    void aNonNumericAdminChannelStillStopsTheBot() throws Exception {
        // Optional and lenient are not the same thing. Empty is a decision; `<#24>` is a paste.
        Files.writeString(directory.resolve("access.yml"), access().replace("admin: '24'", "admin: '<#24>'"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("channels.admin"), error.getMessage());
    }

    @Test
    @DisplayName("the guild id is one of the two the bot cannot start without")
    void emptyGuildIdStopsTheBot() throws Exception {
        // The other is roles.admin, below. Everything else in this file is a feature that can be
        // left off; these two say where the bot lives and who may administer it, and without the
        // second nobody can log in to Steward to fill the rest in.
        Files.writeString(directory.resolve("access.yml"), access().replace("guild-id: '1'", "guild-id: ''"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("guild-id"), error.getMessage());
    }

    @Test
    @DisplayName("every role but the admin one may be left empty, and the bot still starts")
    void theOptionalRolesMayAllBeEmpty() throws Exception {
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
        Files.writeString(directory.resolve("access.yml"), access().replace("access: '10'", "access: '<@&10>'"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("roles.access"), error.getMessage());
    }

    @Test
    @DisplayName("a longer tier that costs less stops the bot")
    void unorderedTiersStopTheBot() throws Exception {
        // A shortfall is settled by walking down to the highest tier the amount covers, which is
        // only the answer a human would give if more days cost more money.
        Files.writeString(
                directory.resolve("access.yml"),
                access(VALID_TIERS.replace("- days: 60\n  price-cents: 500", "- days: 60\n  price-cents: 900")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("more expensive as they get longer"), error.getMessage());
    }

    @Test
    @DisplayName("two tiers offering the same number of days stop the bot")
    void duplicateDayCountsStopTheBot() throws Exception {
        // A purchase button carries a day count, so two tiers with the same one is an ambiguous
        // lookup rather than a cosmetic mistake.
        Files.writeString(directory.resolve("access.yml"), access(VALID_TIERS.replace("- days: 90", "- days: 30")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("Day counts identify a tier"), error.getMessage());
    }

    @Test
    @DisplayName("an empty tier list is a deployment that has not priced anything yet")
    void emptyTiersAreAllowed() throws Exception {
        // It used to stop the bot with a worked example of what to write, which is a good error
        // message for a situation that is not an error. Prices are a decision somebody makes in the
        // interface after the stack is up, not a precondition of the stack being up.
        Files.writeString(directory.resolve("access.yml"), access("tiers: []"));

        assertTrue(Configs.access().get().tiers().isEmpty());
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
                () -> assertEquals("34", config.languages().getLast().contributionChannel()),
                () -> assertEquals("40", config.languages().getLast().hungerGamesChannel()));
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
    @DisplayName("an empty language list stops the bot")
    void emptyLanguageListStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("access.yml"), languages("languages: []"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertAll(
                () -> assertTrue(error.getMessage().contains("languages is empty"), error.getMessage()),
                () -> assertTrue(
                        error.getMessage().contains("link-channel"),
                        "the message has to show the whole entry: " + error.getMessage()));
    }

    @Test
    @DisplayName("two entries with the same tag stop the bot")
    void duplicateLanguageTagsStopTheBot() throws Exception {
        // A tag is the bundle file name and the value in discord_user.locale, so two entries
        // claiming one is an ambiguous lookup rather than a cosmetic mistake.
        Files.writeString(
                directory.resolve("access.yml"), languages(VALID_LANGUAGES.replace("- tag: de", "- tag: en")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("Tags identify a language"), error.getMessage());
    }

    @Test
    @DisplayName("an upper-case tag stops the bot")
    void upperCaseLanguageTagStopsTheBot() throws Exception {
        // Nothing downstream case-folds a .properties file name.
        Files.writeString(
                directory.resolve("access.yml"), languages(VALID_LANGUAGES.replace("- tag: de", "- tag: DE")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("must be lower case"), error.getMessage());
    }

    @Test
    @DisplayName("a language entry whose ids are all empty is a language that serves nothing")
    void everyLanguageIdMayBeEmpty() throws Exception {
        // All four of these used to be mandatory, which made a first deployment wait on eight
        // channels nobody had created. Empty switches the thing it names off - no link message in
        // this language - and Configured lists every one of them in a line at startup.
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
    @DisplayName("a language id that is present still has to be a snowflake, naming the entry")
    void aNonNumericLanguageChannelStillStopsTheBot() throws Exception {
        // The reason the leniency is about emptiness and nothing else: an unresolvable channel and
        // an unconfigured one look identical from the outside and are not the same thing at all.
        Files.writeString(
                directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace("link-channel: '35'", "link-channel: '<#35>'")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("languages[1].link-channel"), error.getMessage());
    }

    @Test
    @DisplayName("a language with no status-channel is a language with no status channel")
    void anAbsentStatusChannelIsAllowed() throws Exception {
        // The one optional id in the file. VALID_LANGUAGES does not carry it at all, which is the
        // case an operator who has not created the channels yet is actually in.
        Files.writeString(directory.resolve("access.yml"), languages(VALID_LANGUAGES));

        final AccessSpec config = Configs.access().get();

        assertEquals("", config.languages().getFirst().statusChannel());
        assertFalse(Languages.of(config).all().getFirst().hasStatusChannel());
    }

    @Test
    @DisplayName("a language with no announcement-channel gets no announcements, and the bot starts")
    void anAbsentAnnouncementChannelIsAllowed() throws Exception {
        // The second optional id (2026-09-06): the servers' announce rows for this language then
        // settle as "no channel", which is the default and not a fault.
        Files.writeString(directory.resolve("access.yml"), languages(VALID_LANGUAGES));

        final AccessSpec config = Configs.access().get();

        assertEquals("", config.languages().getFirst().announcementChannel());
        assertFalse(Languages.of(config).all().getFirst().hasAnnouncementChannel());
    }

    @Test
    @DisplayName("an announcement-channel that is set has to be a real snowflake")
    void aMistypedAnnouncementChannelStopsTheBot() throws Exception {
        Files.writeString(
                directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace(
                        "hunger-games-channel: '40'",
                        "hunger-games-channel: '40'\n  announcement-channel: 'not-an-id'")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("languages[1].announcement-channel"), error.getMessage());
    }

    @Test
    @DisplayName("a status-channel that is set has to be a real snowflake")
    void aMistypedStatusChannelStopsTheBot() throws Exception {
        // Being lenient about it being absent must not become being lenient about it being wrong:
        // the failure mode of an unresolvable channel id is silence, which looks exactly like a
        // channel nobody configured.
        Files.writeString(
                directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace(
                        "hunger-games-channel: '40'", "hunger-games-channel: '40'\n  status-channel: 'not-an-id'")));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("languages[1].status-channel"), error.getMessage());
    }

    @Test
    @DisplayName("a link-code attempt cap of zero would lock everybody out and is refused")
    void aZeroAttemptCapStopsTheBot() throws Exception {
        Files.writeString(
                directory.resolve("access.yml"),
                access().replace(
                                "role-reconcile-interval-minutes: 10",
                                "role-reconcile-interval-minutes: 10\nlink-code-attempts-per-hour: 0"));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("link-code-attempts-per-hour"), error.getMessage());
    }

    @Test
    @DisplayName("a tag too long for managed_message.kind stops the bot")
    void anOverlongLanguageTagStopsTheBot() throws Exception {
        // Not a rule about languages: managed_message.kind is varchar(32) and the bot writes
        // "CONTRIBUTION_<TAG>" into it. Caught here rather than as an INSERT failure at startup.
        Files.writeString(
                directory.resolve("access.yml"),
                languages(VALID_LANGUAGES.replace("- tag: de", "- tag: " + "a".repeat(20))));

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(error.getMessage().contains("as long as a managed message's key"), error.getMessage());
    }

    // ------------------------------------------------------------- the list is the only source

    @Test
    @DisplayName("the retired roles.german / roles.english are deleted from the file, not argued with")
    void retiredLanguageRolesAreDropped() throws Exception {
        // The list carries each language's role. Leaving the old pair declared alongside it is what
        // made access.yml have two sources of truth for the same ids.
        //
        // Until jcore 3.1.0 a deployed file still carrying `german:` stopped the bot by name, and
        // this test asserted that. It is the wrong answer for a key nobody can fix by editing it:
        // the only possible move was to delete the line, so the loader does it. What the test still
        // pins is that the key does not come back - re-declaring it as a quiet no-op would leave it
        // in the file, and this fails.
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
    @DisplayName("the retired fixed contribution and link channels are deleted from the file")
    void retiredLanguageChannelsAreDropped() throws Exception {
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
    @DisplayName("a deployed access.yml carrying the moved payment keys loses them and keeps the bot")
    void theMovedPaymentKeysAreDropped() throws Exception {
        // payment.watermark and payment.recent-payment-count were this file's until steward/109 and
        // are steward-worker's bunq: block now. A deployed access.yml in a volume still carries
        // both, and the operator's only possible move would be to delete them by hand - so jcore
        // deletes them instead, with a WARN and a .bak, and the bot starts.
        //
        // The other half is what this is really for: nobody may quietly re-declare either key here
        // to make an upgrade smoother. A watermark read by a process with no bunq connection is a
        // setting that cannot do anything except disagree with the one that can.
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
    @DisplayName("a mistyped setting stops the bot and says what was meant")
    void mistypedSettingStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("access.yml"), access().replace("donation-cents:", "donation-cent:"));

        // jcore's predecessor deleted a key it did not know, so a typo cost both the setting and
        // any trace of it.
        final UnknownConfigKeyException error = assertThrows(UnknownConfigKeyException.class, Configs::access);

        assertAll(
                () -> assertEquals(
                        "donation-cent", error.unknownKeys().getFirst().path()),
                () -> assertEquals(
                        "donation-cents", error.unknownKeys().getFirst().suggestion()));
    }

    @Test
    @DisplayName("the retired link-code-ttl-minutes is deleted from the file, and gate.yml keeps the only one")
    void retiredLinkCodeTtlIsDropped() throws Exception {
        // gate.yml carries the only link-code TTL (decided 2026-08-31). The bot never read its own
        // copy, so a deployed access.yml that still carries the key is carrying a number that means
        // nothing - which is exactly the case jcore 3.1.0 deletes instead of refusing.
        Files.writeString(directory.resolve("access.yml"), access() + "link-code-ttl-minutes: 10\n");

        Configs.access();

        assertFalse(
                Files.readString(directory.resolve("access.yml")).contains("link-code-ttl-minutes"),
                "the key is gone rather than sitting in the file looking like a setting");
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
                () -> assertTrue(
                        Files.readString(file).contains("access: ''"), "the role ids are written empty, never guessed"),
                () -> assertTrue(
                        Files.readString(file).contains("price-cents: 300"),
                        "but the price list is written in full - a fresh install is ready to sell"),
                () -> assertTrue(
                        Files.readString(file).contains("price-cents: 700"), "all three tiers, not just the first"),
                // jcore initialises a List<NestedSpec> to empty, so without DefaultLanguages this
                // comes out as "languages: []" and a fresh install has no language at all.
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

    // ------------------------------------------------------------- bot.yml

    @Test
    @DisplayName("the bot refuses to start while the credentials are empty")
    void emptyCredentialsStopTheBot() {
        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::bot);

        assertAll(
                () -> assertTrue(error.getMessage().contains("token"), error.getMessage()),
                () -> assertTrue(
                        error.getMessage().contains("NORDTAL_BOT_TOKEN"),
                        "the message has to name the variable to set: " + error.getMessage()));
    }

    @Test
    @DisplayName("a deployed bot.yml still carrying the whole bunq block loses it and keeps the bot")
    void theMovedBunqBlockIsDropped() throws Exception {
        // bunq lived in this file until steward/109 - api-key, account-id, context-path, and a
        // retired `environment` switch that jcore was already dropping by name. The whole block is
        // steward-worker's now, and a bot.yml sitting in the config volume of a running deployment
        // still has every line of it.
        //
        // What must NOT happen is a refusal to start: an unknown key costs a WARN and a .bak. What
        // must also not happen is somebody re-declaring the block here as a no-op to make an
        // upgrade quiet - that would put a bunq API key back into the Discord process, which is the
        // entire thing the move was for.
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
    @DisplayName("a defaults bot.yml is written and the secrets slot stays empty")
    void writesDefaultsWithoutSecrets() {
        assertThrows(ConfigValidationException.class, Configs::bot);

        final Path file = directory.resolve("bot.yml");
        // The header is NOT in the YAML any more, and looking for it there is what made this test
        // red on 2026-09-16. jcore 4.0.0 stopped writing comments and headers into the file it
        // writes; 4.1.0 put the file-level @ConfigSpec(header) into the schema beside it, on the
        // root node's `explanation`. So the sentence that tells an operator where the token
        // actually comes from still has to exist and still has to be findable - just in the other
        // file. Asserting it here rather than dropping the assertion is the point: that sentence
        // is the only place NORDTAL_BOT_TOKEN is explained to somebody looking at bot.yml.
        final Path schema = directory.resolve("bot.schema.json");
        assertAll(
                () -> assertTrue(Files.isRegularFile(file), "the defaults file is still written"),
                () -> assertTrue(
                        Files.readString(file).contains("token: ''"), "the token slot is written empty, never guessed"),
                () -> assertTrue(
                        Files.isRegularFile(schema),
                        "the schema is written beside it, under the config's own base name"),
                // "THIS" and not "THESE" since steward/109: bot.yml has one setting left, because
                // the bunq credentials moved to steward-worker. The sentence is still the only
                // place NORDTAL_BOT_TOKEN is explained to somebody looking at this file.
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

    // ------------------------------------------------------------- database.yml

    @Test
    @DisplayName("a non-PostgreSQL jdbc-url stops the bot")
    void wrongDatabaseUrlStopsTheBot() throws Exception {
        Files.writeString(directory.resolve("database.yml"), "jdbc-url: jdbc:mysql://db:3306/access\nusername: u\n");

        final ConfigValidationException error = assertThrows(ConfigValidationException.class, Configs::database);
        assertTrue(error.getMessage().contains("PostgreSQL"), error.getMessage());
    }

    // ------------------------------------------------------------- steward/70: schema beside every file

    /**
     * steward/70: the running deployment's {@code bot-config} volume had three {@code .yml} files
     * and not one {@code *.schema.json} beside any of them - every other service in the stack had
     * one. This is the same guard steward/54 and steward/58 need for the schema to be useful at
     * all: a file with no schema falls back to steward-worker's plain leaf-key reading, which is
     * exactly the "looks right, means less" state that ticket is about.
     * <p>
     * {@code ConfigHandle}'s load (jcore, current dependency) writes {@code <name>.schema.json}
     * unconditionally on every load, whether or not the load ends up throwing in the validator -
     * the schema write happens before the validator runs. That already held on 2026-09-16 with
     * jcore 4.1.0 and this module's own {@code Configs}: this test does not need to be red before
     * green here, because there is nothing in this module's own loading code to fix - it calls
     * {@link ConfigLoader#builder} exactly the way {@code smp} and every other module does. What
     * <b>was</b> missing from the deployed volume is a stale jar: the running container was last
     * built before jcore gained {@code SchemaWriter} and simply does not carry the class, so its
     * copy of {@code ConfigHandle} never had a schema to write in the first place. A rebuild of
     * this module's image against the current dependency is the fix for the volume; this test
     * pins that the module's own code does not regress back into the state that jar is stuck in.
     */
    @Test
    @DisplayName("every config file this module writes gets a schema.json beside it")
    void everyConfigFileHasASchemaBesideIt() {
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
     * Runs a config loader and discards a validation failure - this test is only about the file
     * and its schema having been written, which jcore does before the validator ever runs, not
     * about whether the freshly written defaults are themselves acceptable (they usually are not:
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

    // ------------------------------------------------------------- .env.example

    /**
     * The value of {@code key} as {@code .env.example} at the repository root ships it, with a
     * leading {@code #} stripped from every line of a commented-out block.
     * <p>
     * Read out of the real file rather than copied into a literal here, on purpose: a copy is a
     * second source of truth that nothing compares, and the two blocks this reads are exactly the
     * ones whose shape has to survive jcore's environment overlay. {@code build-logic}'s
     * {@code repositoryRootTestInputs} declares the file as an input of this test task - without
     * that, editing {@code .env.example} would leave {@code :discord-bot:test} UP-TO-DATE and the
     * check would not run at all.
     * </p>
     * <p>
     * Both blocks are written the way an operator reads them - one key per line - rather than as
     * the single line a shell would have needed, because docker compose parses a single-quoted
     * multi-line value in an env file as one string. That is a claim about two systems this
     * repository does not own, so the half that is ours is what is checked: whatever compose
     * hands over, jcore has to turn back into a list of specs.
     * </p>
     */
    private static String envExampleValue(final String key) throws IOException {
        final Path file = repositoryRoot().resolve(".env.example");
        assertTrue(Files.isRegularFile(file), file + " does not exist");
        final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        final String opening = key + "='";

        for (int i = 0; i < lines.size(); i++) {
            final StringBuilder value = new StringBuilder(uncomment(lines.get(i)));
            if (!value.toString().startsWith(opening)) {
                continue;
            }
            value.delete(0, opening.length());
            while (!value.toString().endsWith("'")) {
                if (++i == lines.size()) {
                    throw new IllegalStateException(file + " never closes the quote on " + key);
                }
                value.append('\n').append(uncomment(lines.get(i)));
            }
            return value.substring(0, value.length() - 1);
        }
        throw new IllegalStateException(file + " does not set " + key + " any more. If it was"
                + " renamed, rename it here too - this test is the only thing that reads it.");
    }

    /** A commented-out block is still the value an operator uncomments; the {@code #} is not. */
    private static String uncomment(final String line) {
        return line.startsWith("#") ? line.substring(1) : line;
    }

    /**
     * The repository root, found by walking up from the working directory - which Gradle sets to
     * the module folder and IntelliJ may not - until {@code settings.gradle.kts} is there.
     * <p>
     * It anchors on the build rather than on the first {@code .env.example} above it, because
     * this module used to ship one of its own next to its old compose file: a search for the
     * nearest file by name found that one, which is the wrong file and looks like the right one.
     * </p>
     */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }

    @Test
    @DisplayName("the language list in .env.example is read back as two languages")
    void theEnvExampleLanguageListParses() throws Exception {
        final AccessSpec config =
                fromEnvironment(Map.of("NORDTAL_ACCESS_LANGUAGES", envExampleValue("NORDTAL_ACCESS_LANGUAGES")));

        assertEquals(2, config.languages().size(), "both entries have to survive the round trip");
        assertEquals("en", config.languages().get(0).tag());
        assertEquals("de", config.languages().get(1).tag());
        // The ids are the reason the block exists: a JSON key that does not match the @Key
        // name comes back as null rather than as an error, which would surface as a null channel
        // id hours later. REPLACE_ME arriving intact is what proves the names line up.
        assertEquals("REPLACE_ME", config.languages().get(0).role());
        assertEquals("REPLACE_ME", config.languages().get(0).contributionChannel());
        assertEquals("REPLACE_ME", config.languages().get(0).linkChannel());
        assertEquals("REPLACE_ME", config.languages().get(1).hungerGamesChannel());
        // And status-channel is empty rather than REPLACE_ME, because it is the one id an operator
        // may leave out - a REPLACE_ME there would refuse to start a bot that does not need it.
        assertEquals("", config.languages().get(0).statusChannel());
        assertEquals("", config.languages().get(1).statusChannel());
    }

    @Test
    @DisplayName("the price list in .env.example is read back as three tiers")
    void theEnvExampleTierListParses() throws Exception {
        final AccessSpec config =
                fromEnvironment(Map.of("NORDTAL_ACCESS_TIERS", envExampleValue("NORDTAL_ACCESS_TIERS")));

        assertEquals(3, config.tiers().size());
        assertEquals(30, config.tiers().get(0).days());
        assertEquals(700, config.tiers().get(2).priceCents());
    }

    @Test
    @DisplayName("a REPLACE_ME id is refused by name rather than started with")
    void aPlaceholderIdIsRefusedByName() throws Exception {
        // The whole point of REPLACE_ME over a row of zeros: zeros are a valid snowflake and would
        // start a bot against a guild that does not exist.
        Files.writeString(directory.resolve("access.yml"), access().replace("access: '10'", "access: 'REPLACE_ME'"));

        final ConfigValidationException thrown = assertThrows(ConfigValidationException.class, Configs::access);
        assertTrue(
                thrown.getMessage().contains("roles.access"),
                "the message has to name the setting, was: " + thrown.getMessage());
    }

    /**
     * steward/74: the {@code @Protected} annotation and the bot's own startup rule have to name the
     * same language, and there is no way to notice at runtime if they stop doing so - the removal
     * refusal lives in steward-worker, in another process, and the startup check here would simply
     * go on protecting a different tag without complaining.
     *
     * <p>This is that check, at build time. It replaces reading the annotation reflectively into
     * {@code Configs.FALLBACK_LANGUAGE}: both the field and the annotation already name
     * {@link Languages#FALLBACK_TAG}, so the reflection guarded against nothing the compiler does
     * not, while adding one way for this class to fail to initialise at all.</p>
     */
    @Test
    @DisplayName("the language steward-worker refuses to remove is the one this bot falls back to")
    void theProtectedLanguageIsTheFallback() throws Exception {
        final Method languages = AccessSpec.class.getMethod("languages");
        final Protected annotation = languages.getAnnotation(Protected.class);
        assertNotNull(
                annotation,
                "AccessSpec#languages() must carry @Protected - without it"
                        + " steward-worker lets an operator remove the fallback language through the API,"
                        + " and the bot only notices on its next restart");
        assertEquals("tag", annotation.field(), "@Protected has to match on the element's own tag field");
        assertEquals(
                Languages.FALLBACK_TAG,
                annotation.value(),
                "the protected tag and the fallback tag are the same language or the rule protects"
                        + " the wrong entry");
    }

    /**
     * Loads {@code access.yml} with a fake environment on top, the way the container's is.
     * <p>
     * {@link Configs#access()} reads {@link System#getenv} and a test cannot set that, so this goes
     * through {@link ConfigLoader} directly with the same prefix. It therefore covers the overlay
     * and not the validator - which is the half at risk here: a JSON shape that does not map onto
     * the spec fails inside Gson, long before any rule of ours runs.
     * </p>
     */
    private AccessSpec fromEnvironment(final Map<String, String> environment) throws Exception {
        return handleFromEnvironment(environment).get();
    }

    /**
     * The handle itself, for steward/76: {@link #fromEnvironment} only ever needed the loaded
     * spec, but {@link ConfigHandle#environmentOverrides()} is what feeds
     * {@code EnvOverrideFile.write} in {@code Configs#load} - see
     * {@code environmentOverridesNameExactlyTheOverriddenPaths} below for why this had to be added
     * rather than reused as it stood.
     */
    private ConfigHandle<AccessSpec> handleFromEnvironment(final Map<String, String> environment) throws Exception {
        Files.writeString(directory.resolve("access.yml"), access());
        return ConfigLoader.builder(directory.resolve("access.yml"), AccessSpec.class)
                .envPrefix("NORDTAL_ACCESS")
                .environment(environment::get)
                .load();
    }

    /**
     * steward/76, and the exact measurement its report is built on: this host's own
     * {@code deploy/dev.env.example} sets {@code NORDTAL_ACCESS_LANGUAGES}, and the running bot's
     * startup line names {@code languages} among seven overridden settings. This is that same
     * override, run through {@link ConfigHandle#environmentOverrides()} and then
     * {@link EnvOverrideFile}, which is the whole path {@code Configs#load}'s new
     * {@code recordEnvironmentOverrides} step takes in production - a private method a test cannot
     * call directly, so this exercises the same two calls in the same order instead of trusting
     * that they are wired up.
     */
    @Test
    @DisplayName("languages, overridden exactly the way dev.env.example overrides it, ends up in the marker file")
    void environmentOverridesNameExactlyTheOverriddenPaths() throws Exception {
        final ConfigHandle<AccessSpec> handle =
                handleFromEnvironment(Map.of("NORDTAL_ACCESS_LANGUAGES", envExampleValue("NORDTAL_ACCESS_LANGUAGES")));

        assertTrue(
                handle.environmentOverrides().contains("languages"),
                "jcore itself has to report the override before anything downstream can - reported: "
                        + handle.environmentOverrides());

        EnvOverrideFile.write(handle.file(), handle.environmentOverrides());

        assertEquals(
                Optional.of(handle.environmentOverrides()),
                EnvOverrideFile.read(handle.file()),
                "the marker file steward-worker reads has to carry exactly what jcore reported");
    }

    /**
     * {@link #environmentOverridesNameExactlyTheOverriddenPaths} deliberately builds its own
     * {@link ConfigHandle} and calls {@link EnvOverrideFile} itself, because {@link Configs#load}
     * is private and {@code Configs.access()} does not let a test inject environment variables -
     * so nothing above actually calls {@code Configs}'s own {@code recordEnvironmentOverrides}
     * step. This is the test that does: it goes through the real, public entry point with no
     * override in play at all, and the only thing it can require is that the entry point writes
     * <em>some</em> marker file - the empty-list case {@code EnvOverrideFileTest} already covers
     * for {@link EnvOverrideFile} on its own. A regression that deletes the
     * {@code recordEnvironmentOverrides(handle);} line from {@code Configs#load} shows up here as
     * a missing file, not as a wrong value in one.
     */
    @Test
    @DisplayName("loading access.yml through Configs.access() itself leaves a marker file beside it")
    void loadingThroughTheRealEntryPointWritesTheMarkerFile() throws Exception {
        Files.writeString(directory.resolve("access.yml"), access());

        Configs.access();

        assertEquals(
                Optional.of(List.of()),
                EnvOverrideFile.read(directory.resolve("access.yml")),
                "nothing is overridden here, but Configs#load still has to run the write step - an"
                        + " absent marker file and an empty one are different facts (steward/76)");
    }
}

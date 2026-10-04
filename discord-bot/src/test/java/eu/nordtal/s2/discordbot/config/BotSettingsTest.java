package eu.nordtal.s2.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.spec.annotation.Protected;
import eu.nordtal.s2.settings.MemorySettingStore;
import eu.nordtal.s2.settings.SettingsException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Every access value that must not get past startup, and how the environment reaches the access settings. */
class BotSettingsTest {

    private final MemorySettingStore store = new MemorySettingStore();

    /** A complete, valid access group as Steward stores it, with English and German. */
    private static Map<String, Object> access() {
        return access(List.of(language("en", 30, 39), language("de", 33, 40)));
    }

    /** A complete access group with the given languages, one value per path. */
    private static Map<String, Object> access(final List<Map<String, String>> languages) {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("guild-id", "1");
        values.put("roles.access", "10");
        values.put("roles.donor", "11");
        values.put("roles.admin", "14");
        values.put("channels.admin", "24");
        values.put("payment.request-ttl-hours", 24);
        values.put("expiry-reminder-lead-days", 3);
        values.put("role-reconcile-interval-minutes", 10);
        values.put("languages", languages);
        return values;
    }

    /** One language entry: its role, the two channels numbered after it, and its Hunger Games channel. */
    private static Map<String, String> language(final String tag, final int role, final int hungerGamesChannel) {
        final Map<String, String> entry = new LinkedHashMap<>();
        entry.put("tag", tag);
        entry.put("role", String.valueOf(role));
        entry.put("contribution-channel", String.valueOf(role + 1));
        entry.put("link-channel", String.valueOf(role + 2));
        entry.put("hunger-games-channel", String.valueOf(hungerGamesChannel));
        return entry;
    }

    /** Takes the access group over {@code values} the way the bot does at its start. */
    private AccessSpec taken(final Map<String, Object> values) throws SettingsException {
        return store.checked(BotSettings.SERVICE, BotSettings.ACCESS, values);
    }

    /** The message the check refuses {@code values} with. */
    private String refused(final Map<String, Object> values) {
        return assertThrows(SettingsException.class, () -> taken(values)).getMessage();
    }

    /** English, and German with {@code key} set to {@code value}. */
    private static Map<String, Object> germanWith(final String key, final String value) {
        final Map<String, String> german = language("de", 33, 40);
        german.put(key, value);
        return access(List.of(language("en", 30, 39), german));
    }

    @Test
    void aCompleteAccessGroupLoads() throws Exception {
        final AccessSpec config = taken(access());

        assertAll(
                () -> assertEquals("1", config.guildId()),
                () -> assertEquals("10", config.roles().access()),
                () -> assertEquals("14", config.roles().admin()),
                () -> assertEquals("24", config.channels().admin()),
                () -> assertEquals(24, config.payment().requestTtlHours()),
                () -> assertEquals(
                        List.of("en", "de"),
                        config.languages().stream()
                                .map(AccessSpec.LanguageSpec::tag)
                                .toList()));
    }

    @Test
    void noAdminChannelIsABotThatStartsAndLogsItsAlertsInstead() throws Exception {
        // A guild without an admin channel yet still has to come up to say what else is missing.
        final Map<String, Object> values = access();
        values.put("channels.admin", "");

        assertEquals("", taken(values).channels().admin());
    }

    @Test
    void anAdminChannelThatIsPresentStillHasToBeASnowflake() {
        // Empty is a decision; `<#24>` is a paste.
        final Map<String, Object> values = access();
        values.put("channels.admin", "<#24>");

        final String message = refused(values);
        assertTrue(message.contains("channels.admin"), message);
    }

    @Test
    void theGuildIdIsOneOfTheTwoTheBotCannotStartWithout() {
        final Map<String, Object> values = access();
        values.put("guild-id", "");

        final String message = refused(values);
        assertTrue(message.contains("guild-id"), message);
    }

    @Test
    void everyRoleButTheAdminOneMayBeLeftEmptyAndTheBotStillStarts() throws Exception {
        final Map<String, Object> values = access();
        values.put("roles.access", "");
        values.put("roles.donor", "");

        final AccessSpec config = taken(values);

        assertAll(
                () -> assertEquals("", config.roles().access()),
                () -> assertEquals("", config.roles().donor()),
                () -> assertEquals("14", config.roles().admin()));
    }

    @Test
    void theBotRefusesToStartWhileTheAdminRoleIdIsEmpty() {
        // This role's flag authorises the admin actions and admission during MAINTENANCE.
        final Map<String, Object> values = access();
        values.put("roles.admin", "");

        final String message = refused(values);
        assertTrue(message.contains("roles.admin"), message);
    }

    @Test
    void aRoleIdThatIsNotASnowflakeStopsTheBot() {
        final Map<String, Object> values = access();
        values.put("roles.access", "<@&10>");

        final String message = refused(values);
        assertTrue(message.contains("roles.access"), message);
    }

    @Test
    void aReplaceMeIdIsRefusedByNameRatherThanStartedWith() {
        // REPLACE_ME rather than zeros: zeros are a valid snowflake for a guild that does not exist.
        final Map<String, Object> values = access();
        values.put("roles.access", "REPLACE_ME");

        final String message = refused(values);
        assertTrue(message.contains("roles.access"), "the message has to name the setting, was: " + message);
    }

    @Test
    void theLanguageListLoadsWithItsTagsRoleAndChannels() throws Exception {
        final AccessSpec config = taken(access());

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
    void aLanguageListWithoutEnStopsTheBotAndPrintsTheShapeToWrite() {
        // English is the floor every missing translation degrades to.
        final String message = refused(access(List.of(language("de", 33, 40))));

        assertAll(
                () -> assertTrue(message.contains("no 'en' entry"), message),
                () -> assertTrue(message.contains("tag: en"), "the message has to show what to write: " + message));
    }

    @Test
    void anEmptyLanguageListStopsTheBot() {
        final String message = refused(access(List.of()));

        assertAll(
                () -> assertTrue(message.contains("languages is empty"), message),
                () -> assertTrue(
                        message.contains("link-channel"), "the message has to show the whole entry: " + message));
    }

    @Test
    void twoEntriesWithTheSameTagStopTheBot() {
        // A tag is the bundle name and the discord_user.locale value, so two entries claiming one are ambiguous.
        final String message = refused(access(List.of(language("en", 30, 39), language("en", 33, 40))));

        assertTrue(message.contains("Tags identify a language"), message);
    }

    @Test
    void anUpperCaseTagStopsTheBot() {
        // Nothing downstream case-folds a .properties file name.
        final String message = refused(access(List.of(language("en", 30, 39), language("DE", 33, 40))));

        assertTrue(message.contains("must be lower case"), message);
    }

    @Test
    void aLanguageEntryWhoseIdsAreAllEmptyIsALanguageThatServesNothing() throws Exception {
        // Empty switches off what it names, and Configured lists it.
        final Map<String, String> empty = language("de", 33, 40);
        empty.replaceAll((key, value) -> key.equals("tag") ? value : "");

        final AccessSpec.LanguageSpec german = taken(access(List.of(language("en", 30, 39), empty)))
                .languages()
                .get(1);

        assertAll(
                () -> assertEquals("", german.role()),
                () -> assertEquals("", german.contributionChannel()),
                () -> assertEquals("", german.linkChannel()),
                () -> assertEquals("", german.hungerGamesChannel()));
    }

    @Test
    void aLanguageIdThatIsPresentStillHasToBeASnowflakeNamingTheEntry() {
        // An unresolvable channel and an unconfigured one are not the same.
        final String message = refused(germanWith("link-channel", "<#35>"));

        assertTrue(message.contains("languages[1].link-channel"), message);
    }

    @Test
    void aLanguageWithNoStatusChannelIsALanguageWithNoStatusChannel() throws Exception {
        final AccessSpec config = taken(access());

        assertEquals("", config.languages().getFirst().statusChannel());
        assertFalse(Languages.of(config).all().getFirst().hasStatusChannel());
    }

    @Test
    void aLanguageWithNoAnnouncementChannelGetsNoAnnouncementsAndTheBotStarts() throws Exception {
        // The second optional id: the servers' announce rows for this language settle as "no channel".
        final AccessSpec config = taken(access());

        assertEquals("", config.languages().getFirst().announcementChannel());
        assertFalse(Languages.of(config).all().getFirst().hasAnnouncementChannel());
    }

    @Test
    void anAnnouncementChannelThatIsSetHasToBeARealSnowflake() {
        final String message = refused(germanWith("announcement-channel", "not-an-id"));

        assertTrue(message.contains("languages[1].announcement-channel"), message);
    }

    @Test
    void aStatusChannelThatIsSetHasToBeARealSnowflake() {
        // Lenient about absent must not become lenient about wrong.
        final String message = refused(germanWith("status-channel", "not-an-id"));

        assertTrue(message.contains("languages[1].status-channel"), message);
    }

    @Test
    void aLinkCodeAttemptCapOfZeroWouldLockEverybodyOutAndIsRefused() {
        final Map<String, Object> values = access();
        values.put("link-code-attempts-per-hour", 0);

        final String message = refused(values);
        assertTrue(message.contains("link-code-attempts-per-hour"), message);
    }

    @Test
    void aTagTooLongForManagedMessageKindStopsTheBot() {
        // managed_message.kind is varchar(32) and holds "CONTRIBUTION_<TAG>".
        final String message = refused(access(List.of(language("en", 30, 39), language("a".repeat(20), 33, 40))));

        assertTrue(message.contains("as long as a managed message's key"), message);
    }

    @Test
    void aThirdLanguageIsASettingEditAndNothingElse() throws Exception {
        // Nothing in the bot's source mentions 'fr'; this entry is the entire change per language.
        final Languages languages = Languages.of(
                taken(access(List.of(language("en", 30, 39), language("de", 33, 40), language("fr", 36, 41)))));
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
                () -> assertEquals(
                        "de", languages.resolve(Set.of("33")).orElseThrow().tag()),
                () -> assertEquals("31", languages.forLocale(Locale.ENGLISH).contributionChannelId()));
    }

    @Test
    void theBotRefusesToStartWhileTheCredentialsAreEmpty() {
        final SettingsException error =
                assertThrows(SettingsException.class, () -> BotSettings.bot(store.settings(BotSettings.SERVICE)));

        assertAll(
                () -> assertTrue(error.getMessage().contains("token"), error.getMessage()),
                () -> assertTrue(
                        error.getMessage().contains("NORDTAL_BOT_TOKEN"),
                        "the message has to name the variable to set: " + error.getMessage()));
    }

    /**
     * The {@code @Protected} annotation and the bot's startup rule name the same fallback language.
     *
     * Nothing notices at runtime if they drift, because the removal refusal lives in steward.
     */
    @Test
    void theLanguageStewardRefusesToRemoveIsTheOneThisBotFallsBackTo() throws Exception {
        final Method languages = AccessSpec.class.getMethod("languages");
        final Protected annotation = languages.getAnnotation(Protected.class);
        assertNotNull(
                annotation,
                "AccessSpec#languages() must carry @Protected - without it"
                        + " steward lets an operator remove the fallback language through the API,"
                        + " and the bot only notices on its next restart");
        assertEquals("tag", annotation.field(), "@Protected has to match on the element's own tag field");
        assertEquals(
                Languages.FALLBACK_TAG,
                annotation.value(),
                "the protected tag and the fallback tag are the same language or the rule protects"
                        + " the wrong entry");
    }

    /** A value under {@code NORDTAL_ACCESS_} wins over what is stored, and Steward is told which path it holds. */
    @Test
    void theEnvironmentWinsAndStewardIsToldWhichPathItHolds() throws Exception {
        access().forEach((path, value) -> store.set(BotSettings.SERVICE, "access", path, value));

        final AccessSpec taken = BotSettings.access(store.settings(
                        BotSettings.SERVICE,
                        BotSettings.ENVIRONMENT.reading(Map.of("NORDTAL_ACCESS_GUILD_ID", "2")::get)))
                .get();

        assertEquals("2", taken.guildId());
        assertEquals(
                List.of("guild-id"),
                store.group(BotSettings.SERVICE, "access").orElseThrow().environment());
    }
}

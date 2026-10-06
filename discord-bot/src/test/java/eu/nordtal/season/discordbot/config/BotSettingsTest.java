package eu.nordtal.season.discordbot.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.settings.SettingsException;
import eu.nordtal.season.settings.network.NetworkSettings;
import eu.nordtal.season.spec.annotation.Protected;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every access and onboarding value that must not get past startup, and how the environment reaches them. */
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
        values.put("role-names.access", "Access");
        values.put("role-names.donor", "Donor");
        values.put("role-names.admin", "Admin");
        values.put("channels.admin", "24");
        values.put("payment.request-ttl-hours", 24);
        values.put("expiry-reminder-lead-days", 3);
        values.put("role-reconcile-interval-minutes", 10);
        values.put("languages", languages);
        return values;
    }

    /** One language entry: its two channels numbered after {@code first}, and its Hunger Games channel. */
    private static Map<String, String> language(final String tag, final int first, final int hungerGamesChannel) {
        final Map<String, String> entry = new LinkedHashMap<>();
        entry.put("tag", tag);
        entry.put("contribution-channel", String.valueOf(first + 1));
        entry.put("link-channel", String.valueOf(first + 2));
        entry.put("hunger-games-channel", String.valueOf(hungerGamesChannel));
        return entry;
    }

    /** The languages the network speaks in most of these tests. */
    private static final Languages NETWORK = new Languages(List.of("en", "de"));

    /** Takes the access group over {@code values} as the bot does at its start, on the English and German network. */
    private AccessSpec taken(final Map<String, Object> values) throws SettingsException {
        return taken(values, NETWORK);
    }

    private AccessSpec taken(final Map<String, Object> values, final Languages network) throws SettingsException {
        return store.checked(BotSettings.SERVICE, BotSettings.accessGroup(network), values);
    }

    /** The message the check refuses {@code values} with. */
    private String refused(final Map<String, Object> values) {
        return refused(values, NETWORK);
    }

    private String refused(final Map<String, Object> values, final Languages network) {
        return assertThrows(SettingsException.class, () -> taken(values, network))
                .getMessage();
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
                () -> assertEquals("Access", config.roleNames().access()),
                () -> assertEquals("Admin", config.roleNames().admin()),
                () -> assertEquals("24", config.channels().admin()),
                () -> assertEquals(24, config.payment().requestTtlHours()),
                () -> assertEquals(
                        List.of("en", "de"),
                        config.languages().stream()
                                .map(AccessSpec.LanguageSpec::tag)
                                .toList()));
    }

    @Test
    void anAccessGroupStoredWithRoleIdsStillLoadsWithItsChannels() throws Exception {
        // The rows an installation kept from before the names: role ids at paths that are gone, and in every language.
        final Map<String, String> english = language("en", 30, 39);
        english.put("role", "1407097541761171496");
        final Map<String, String> german = language("de", 33, 40);
        german.put("role", "1407097130866311329");
        final Map<String, Object> values = access(List.of(english, german));
        values.remove("role-names.access");
        values.remove("role-names.donor");
        values.remove("role-names.admin");
        values.put("roles.access", "1544515346940301384");
        values.put("roles.donor", "1544515504889139301");

        final AccessSpec config = taken(values);

        assertAll(
                () -> assertEquals("Access", config.roleNames().access()),
                () -> assertEquals("35", config.languages().get(1).linkChannel()),
                () -> assertEquals(
                        "Deutsch", GuildLanguages.roleNameOf(config.languages().get(1))));
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
    void theGuildIdIsTheOneValueTheBotCannotStartWithout() {
        final Map<String, Object> values = access();
        values.put("guild-id", "");

        final String message = refused(values);
        assertTrue(message.contains("guild-id"), message);
    }

    @Test
    void anEmptyRoleNameIsRefusedByItsPathSinceTheBotWouldHaveNothingToFindTheRoleBy() {
        final Map<String, Object> values = access();
        values.put("role-names.admin", " ");

        final String message = refused(values);
        assertTrue(message.contains("role-names.admin"), message);
    }

    @Test
    void twoRolesOfOneNameAreRefusedSinceTheBotWouldTakeTheSameRoleForBoth() {
        final Map<String, Object> values = access();
        values.put("role-names.donor", "Access");

        final String message = refused(values);
        assertTrue(message.contains("role-names.donor"), message);
    }

    @Test
    void aLanguageRoleIsNamedAfterTheLanguageInItselfUnlessItsEntryNamesIt() throws Exception {
        final GuildLanguages languages = GuildLanguages.of(taken(germanWith("role-name", "German")), NETWORK);

        assertAll(
                () -> assertEquals(
                        "English", languages.byTag("en").orElseThrow().roleName()),
                () -> assertEquals("German", languages.byTag("de").orElseThrow().roleName()),
                () -> assertEquals(
                        "Deutsch",
                        GuildLanguages.of(taken(access()), NETWORK)
                                .byTag("de")
                                .orElseThrow()
                                .roleName()));
    }

    @Test
    void aLanguageRoleNamedLikeAnotherRoleIsRefusedNamingTheEntry() {
        final String message = refused(germanWith("role-name", "Admin"));

        assertTrue(message.contains("languages[de].role-name"), message);
    }

    @Test
    void aReplaceMeIdIsRefusedByNameRatherThanStartedWith() {
        // REPLACE_ME rather than zeros: zeros are a valid snowflake for a guild that does not exist.
        final Map<String, Object> values = access();
        values.put("channels.admin", "REPLACE_ME");

        final String message = refused(values);
        assertTrue(message.contains("channels.admin"), "the message has to name the setting, was: " + message);
    }

    @Test
    void theLanguageListLoadsWithItsTagsRoleAndChannels() throws Exception {
        final AccessSpec config = taken(access());

        assertAll(
                () -> assertEquals(2, config.languages().size()),
                () -> assertEquals("en", config.languages().getFirst().tag()),
                () -> assertEquals("", config.languages().getFirst().roleName()),
                () -> assertEquals("32", config.languages().getFirst().linkChannel()),
                () -> assertEquals("de", config.languages().getLast().tag()),
                () -> assertEquals("34", config.languages().getLast().contributionChannel()),
                () -> assertEquals("40", config.languages().getLast().hungerGamesChannel()));
    }

    @Test
    void aLanguageListWithoutTheDefaultLanguageStopsTheBotNamingIt() {
        // The network's default is the floor every missing translation degrades to.
        final String message = refused(access(List.of(language("de", 33, 40))));

        assertTrue(message.contains("no entry for 'en'"), message);
    }

    @Test
    void aNetworkLanguageWithoutAnEntryStopsTheBotNamingIt() {
        final String message = refused(
                access(List.of(language("en", 30, 39), language("de", 33, 40))),
                new Languages(List.of("en", "de", "fr")));

        assertAll(
                () -> assertTrue(message.contains("no entry for 'fr'"), message),
                () -> assertTrue(message.contains("the network speaks"), message));
    }

    @Test
    void anEntryForALanguageTheNetworkDoesNotSpeakStopsTheBotNamingIt() {
        final String message =
                refused(access(List.of(language("en", 30, 39), language("de", 33, 40), language("fr", 36, 41))));

        assertAll(
                () -> assertTrue(message.contains("entry for 'fr'"), message),
                () -> assertTrue(message.contains("does not speak"), message));
    }

    @Test
    void anEmptyLanguageListStopsTheBot() {
        final String message = refused(access(List.of()));

        assertTrue(message.contains("no entry for 'en'"), message);
    }

    @Test
    void twoEntriesWithTheSameTagStopTheBot() {
        // A tag is the bundle name and the discord_user.locale value, so two entries claiming one are ambiguous.
        final String message = refused(access(List.of(language("en", 30, 39), language("en", 33, 40))));

        assertTrue(message.contains("Tags identify a language"), message);
    }

    @Test
    void anUpperCaseTagIsNotTheNetworksTag() {
        // The network refuses an upper case tag itself, so an entry written so matches no language.
        final String message = refused(access(List.of(language("en", 30, 39), language("DE", 33, 40))));

        assertTrue(message.contains("entry for 'DE'"), message);
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
                () -> assertEquals("", german.roleName()),
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
        assertFalse(GuildLanguages.of(config, NETWORK).all().getFirst().hasStatusChannel());
    }

    @Test
    void aLanguageWithNoAnnouncementChannelGetsNoAnnouncementsAndTheBotStarts() throws Exception {
        // The second optional id: the servers' announce rows for this language settle as "no channel".
        final AccessSpec config = taken(access());

        assertEquals("", config.languages().getFirst().announcementChannel());
        assertFalse(GuildLanguages.of(config, NETWORK).all().getFirst().hasAnnouncementChannel());
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
        final String message = refused(
                access(List.of(language("en", 30, 39), language("a".repeat(20), 33, 40))),
                new Languages(List.of("en", "a".repeat(20))));

        assertTrue(message.contains("as long as a managed message's key"), message);
    }

    @Test
    void aThirdLanguageIsASettingEditAndNothingElse() throws Exception {
        // Nothing in the bot's source mentions 'fr'; this entry is the entire change per language.
        final Languages network = new Languages(List.of("en", "de", "fr"));
        final GuildLanguages languages = GuildLanguages.of(
                taken(access(List.of(language("en", 30, 39), language("de", 33, 40), language("fr", 36, 41))), network),
                network);
        final GuildLanguages.Language french = languages.byTag("fr").orElseThrow();

        assertAll(
                () -> assertEquals(3, languages.all().size()),
                () -> assertEquals(Locale.FRENCH, french.locale()),
                () -> assertEquals("Français", french.roleName()),
                () -> assertEquals("37", french.contributionChannelId()),
                () -> assertEquals("38", french.linkChannelId()),
                () -> assertEquals("41", french.hungerGamesChannelId()),
                () -> assertEquals("CONTRIBUTION_FR", french.contributionKind()),
                () -> assertEquals("HG_REGISTER_FR", french.hungerGamesRegisterKind()),
                () -> assertArrayEquals(
                        new Locale[] {Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH}, languages.locales()),
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
                NetworkSettings.defaultLanguages().tags().getFirst(),
                annotation.value(),
                "the protected tag and the fallback tag are the same language or the rule protects"
                        + " the wrong entry");
    }

    @Test
    void theDefaultEntriesAreTheNetworksDefaultLanguagesSoTheTwoNeverStartApart() {
        assertEquals(
                NetworkSettings.defaultLanguages().tags(),
                DefaultLanguages.LIST.stream().map(AccessSpec.LanguageSpec::tag).toList());
    }

    /** A value under {@code NORDTAL_ACCESS_} wins over what is stored, and Steward is told which path it holds. */
    @Test
    void theEnvironmentWinsAndStewardIsToldWhichPathItHolds() throws Exception {
        access().forEach((path, value) -> store.set(BotSettings.SERVICE, "access", path, value));

        final AccessSpec taken = BotSettings.access(
                        store.settings(
                                BotSettings.SERVICE,
                                BotSettings.ENVIRONMENT.reading(Map.of("NORDTAL_ACCESS_GUILD_ID", "2")::get)),
                        NETWORK)
                .get();

        assertEquals("2", taken.guildId());
        assertEquals(
                List.of("guild-id"),
                store.group(BotSettings.SERVICE, "access").orElseThrow().environment());
    }

    /** Takes the onboarding group over {@code values} the way the bot does at its start. */
    private OnboardingSpec onboarding(final Map<String, Object> values) throws SettingsException {
        return store.checked(BotSettings.SERVICE, BotSettings.ONBOARDING, values);
    }

    /** The message the onboarding check refuses {@code values} with. */
    private String refusedOnboarding(final Map<String, Object> values) {
        return assertThrows(SettingsException.class, () -> onboarding(values)).getMessage();
    }

    /** An onboarding group whose regions are {@code regions}, as name and zone pairs. */
    private static Map<String, Object> regions(final String... namesAndZones) {
        final List<Map<String, String>> regions = new ArrayList<>();
        for (int index = 0; index < namesAndZones.length; index += 2) {
            regions.add(Map.of("name", namesAndZones[index], "zone", namesAndZones[index + 1]));
        }
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("regions", regions);
        return values;
    }

    @Test
    void anUntouchedOnboardingLocksNobodyHasNoChannelAndOffersTheDefaultRegions() throws Exception {
        final OnboardingSpec config = onboarding(Map.of());

        assertAll(
                () -> assertFalse(config.lock()),
                () -> assertEquals("", config.channel()),
                () -> assertEquals("Onboarding", config.lockRole()),
                () -> assertEquals(12, config.regions().size()),
                () -> assertTrue(config.regions().stream()
                        .anyMatch(region -> region.zone().equals("Europe/Berlin"))),
                () -> assertTrue(config.regions().stream()
                        .allMatch(region -> ZoneId.getAvailableZoneIds().contains(region.zone()))));
    }

    @Test
    void theOnboardingIsTakenWhileTheBotRunsSoTheLockSwitchNeedsNoRestart() {
        assertTrue(BotSettings.ONBOARDING.live());
    }

    @Test
    void aRegionWhoseZoneIsNoIanaZoneIsRefusedNamingTheEntry() {
        final String message = refusedOnboarding(regions("Central Europe", "Europe/Berlin", "Mars", "Mars/Olympus"));

        assertTrue(message.contains("regions[1].zone"), message);
    }

    @Test
    void twoRegionsOfOneZoneAreRefusedSinceTheZoneIsWhatARegionStandsFor() {
        final String message =
                refusedOnboarding(regions("Central Europe", "Europe/Berlin", "Germany", "Europe/Berlin"));

        assertTrue(message.contains("regions[1]"), message);
    }

    @Test
    void aRegionNamedLikeTheLockRoleIsRefusedSinceBothWouldBeOneRole() {
        final String message = refusedOnboarding(regions("Onboarding", "Europe/Berlin"));

        assertTrue(message.contains("regions[0].name"), message);
    }

    @Test
    void anOnboardingWithoutRegionsIsRefusedSinceNobodyCouldEverChooseOne() {
        final String message = refusedOnboarding(regions());

        assertTrue(message.contains("regions"), message);
    }

    @Test
    void anOnboardingChannelThatIsSetHasToBeASnowflake() {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("channel", "<#5>");

        final String message = refusedOnboarding(values);
        assertTrue(message.contains("channel"), message);
    }
}

package eu.nordtal.s2.settings.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.RepositoryRoot;
import eu.nordtal.s2.database.payment.Tier;
import eu.nordtal.s2.messages.context.SeasonContext;
import eu.nordtal.s2.settings.Group;
import eu.nordtal.s2.settings.MemorySettingStore;
import eu.nordtal.s2.settings.SettingsException;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The network's own groups: one row read by every process, and every value the network cannot run on refused. */
class NetworkSettingsTest {

    private final MemorySettingStore store = new MemorySettingStore();

    /** What an admin stored, taken by the next load. */
    private final Map<String, Object> values = new LinkedHashMap<>();

    @Test
    void oneRowOfTheNetworkReachesEveryProcess() throws Exception {
        store.set(MemorySettingStore.NETWORK, "players", "max-players", 120);

        assertEquals(
                120, store.settings("proxy").load(NetworkSettings.PLAYERS).get().maxPlayers());
        assertEquals(
                120, store.settings("smp").load(NetworkSettings.PLAYERS).get().maxPlayers());
    }

    @Test
    void theNetworksRowsAreNotAnyProcesssOwn() throws Exception {
        // A row under the proxy's own name is not the network's, so the network's default stands.
        store.set("proxy", "players", "max-players", 120);

        assertEquals(
                500, store.settings("smp").load(NetworkSettings.PLAYERS).get().maxPlayers());
    }

    @Test
    void theLimitAndTheAllowlistArriveWhileAProcessRuns() throws Exception {
        final var players = store.settings("smp").load(NetworkSettings.PLAYERS);
        final PlayersSpec held = players.get();
        store.set(MemorySettingStore.NETWORK, "players", "command-allowlist", List.of("msg"));

        players.reload();

        assertEquals(List.of("msg"), held.commandAllowlist());
    }

    // players

    @Test
    void theParsedAllowlistFollowsAReload() throws Exception {
        final var players = store.settings("proxy").load(NetworkSettings.PLAYERS);
        final var allowlist = NetworkSettings.allowlist(players.get());
        assertTrue(allowlist.get().allows("/poi"));
        store.set(MemorySettingStore.NETWORK, "players", "command-allowlist", List.of("msg"));

        players.reload();

        assertFalse(allowlist.get().allows("/poi"));
        assertTrue(allowlist.get().allows("/msg Till hi"));
    }

    @Test
    void theDefaultAllowlistIsOurOwnPlayerCommands() throws Exception {
        // The default is the assertion: this list is what every player on the network can type.
        assertEquals(
                List.of("navigate", "poi", "hg ready", "msg", "whisper", "r", "discord", "rules"),
                checked(NetworkSettings.PLAYERS).commandAllowlist());
    }

    @Test
    void aBlankAllowlistEntryIsRefusedBecauseItWouldBeDroppedSilently() {
        values.put("command-allowlist", List.of("msg", ""));

        assertRefused(NetworkSettings.PLAYERS, "command-allowlist");
    }

    @Test
    void anAllowlistEntryThatNamesNoCommandIsRefused() {
        values.put("command-allowlist", List.of("/"));

        assertRefused(NetworkSettings.PLAYERS, "command-allowlist");
    }

    @Test
    void anEmptyAllowlistIsAChoiceNotAMistake() throws Exception {
        values.put("command-allowlist", List.of());

        assertEquals(List.of(), checked(NetworkSettings.PLAYERS).commandAllowlist());
    }

    @Test
    void aNetworkWithoutRoomIsRefused() {
        values.put("max-players", 0);

        assertRefused(NetworkSettings.PLAYERS, "max-players");
    }

    // prices

    @Test
    void theDefaultPriceListIsTheAgreedOne() throws Exception {
        final var tiers = NetworkSettings.tiers(checked(NetworkSettings.PRICES));

        assertEquals(List.of(30, 60, 90), tiers.all().stream().map(Tier::days).toList());
        assertEquals(
                List.of(300, 500, 700),
                tiers.all().stream().map(Tier::priceCents).toList());
        assertEquals(500, tiers.donationCents());
    }

    @Test
    void aLongerTierThatCostsLessIsRefused() {
        // A shortfall walks down to the highest tier the amount covers.
        values.put("tiers", List.of(tier(30, 300), tier(60, 900), tier(90, 700)));

        assertRefused(NetworkSettings.PRICES, "more expensive as they get longer");
    }

    @Test
    void twoTiersOfferingTheSameNumberOfDaysAreRefused() {
        // A purchase button carries a day count, so two tiers sharing one is an ambiguous lookup.
        values.put("tiers", List.of(tier(30, 300), tier(30, 500)));

        assertRefused(NetworkSettings.PRICES, "Day counts identify a tier");
    }

    @Test
    void anEmptyTierListIsANetworkThatHasNotPricedAnythingYet() throws Exception {
        // Prices are set in the interface after the stack is up, not a precondition of being up.
        values.put("tiers", List.of());

        assertTrue(NetworkSettings.tiers(checked(NetworkSettings.PRICES)).all().isEmpty());
    }

    @Test
    void aDonationOfNothingIsRefused() {
        values.put("donation-cents", 0);

        assertRefused(NetworkSettings.PRICES, "donation-cents");
    }

    private static Map<String, Object> tier(final int days, final int priceCents) {
        return Map.of("days", days, "price-cents", priceCents);
    }

    // season

    @Test
    void theSeasonDefaultsToThisOne() throws Exception {
        final SeasonSpec season = checked(NetworkSettings.SEASON);

        assertEquals(2, season.number());
        assertEquals("Season 2", season.name());
    }

    @Test
    void everyMessageNamesTheSeasonAnAdminSet() throws Exception {
        values.put("number", 3);
        values.put("name", "Staffel Drei");

        assertEquals(new SeasonContext(3, "Staffel Drei"), NetworkSettings.season(checked(NetworkSettings.SEASON)));
    }

    @Test
    void aSeasonWithoutANameIsRefused() {
        values.put("name", " ");

        assertRefused(NetworkSettings.SEASON, "name");
    }

    @Test
    void aSeasonWithoutANumberIsRefused() {
        values.put("number", 0);

        assertRefused(NetworkSettings.SEASON, "number");
    }

    // language and time

    @Test
    void theLanguagesStartWithTheDefaultWhereverTheListPutsIt() throws Exception {
        values.put("languages", List.of("de", "en", "fr"));

        assertEquals(
                List.of("en", "de", "fr"),
                NetworkSettings.languages(checked(NetworkSettings.LANGUAGE_AND_TIME))
                        .tags());
    }

    @Test
    void theZoneIsTheOneTheNetworkTellsTimeIn() throws Exception {
        values.put("default-time-zone", "Europe/Helsinki");

        assertEquals(ZoneId.of("Europe/Helsinki"), NetworkSettings.zone(checked(NetworkSettings.LANGUAGE_AND_TIME)));
    }

    @Test
    void theDefaultsAreEnglishAndGermanInBerlin() throws Exception {
        final LanguageAndTimeSpec spec = checked(NetworkSettings.LANGUAGE_AND_TIME);

        assertEquals(List.of("en", "de"), NetworkSettings.languages(spec).tags());
        assertEquals(ZoneId.of("Europe/Berlin"), NetworkSettings.zone(spec));
    }

    @Test
    void theContainersLogInTheDefaultZone() throws Exception {
        final String zone = checked(NetworkSettings.LANGUAGE_AND_TIME).defaultTimeZone();
        final String compose = Files.readString(RepositoryRoot.path().resolve("compose.yml"));
        final Matcher zones = Pattern.compile("TZ: \\$\\{TZ:-([^}]+)}").matcher(compose);
        int found = 0;
        while (zones.find()) {
            assertEquals(zone, zones.group(1), "a container's default TZ left the network's default zone");
            found++;
        }
        assertTrue(found > 0, "compose.yml sets no TZ at all");
    }

    @Test
    void aZoneThatDoesNotExistIsRefused() {
        values.put("default-time-zone", "Europe/Atlantis");

        assertRefused(NetworkSettings.LANGUAGE_AND_TIME, "default-time-zone");
    }

    @Test
    void aDefaultLanguageTheListLeavesOutIsRefused() {
        values.put("languages", List.of("de"));

        assertRefused(NetworkSettings.LANGUAGE_AND_TIME, "default-language");
    }

    @Test
    void aDefaultLanguageOtherThanEnglishIsRefusedSinceOnlyEnglishIsComplete() {
        values.put("default-language", "de");

        assertRefused(NetworkSettings.LANGUAGE_AND_TIME, "default-language");
    }

    @Test
    void aLanguageListedTwiceIsRefused() {
        values.put("languages", List.of("en", "de", "de"));

        assertRefused(NetworkSettings.LANGUAGE_AND_TIME, "languages");
    }

    @Test
    void aTagThatIsNotLowerCaseIsRefused() {
        values.put("languages", List.of("en", "DE"));

        assertRefused(NetworkSettings.LANGUAGE_AND_TIME, "languages");
    }

    private <T> T checked(final Group<T> group) throws SettingsException {
        return store.checked("smp", group, values);
    }

    private void assertRefused(final Group<?> group, final String key) {
        final SettingsException refused = assertThrows(SettingsException.class, () -> checked(group));
        assertTrue(refused.getMessage().contains(key), refused.getMessage());
    }
}

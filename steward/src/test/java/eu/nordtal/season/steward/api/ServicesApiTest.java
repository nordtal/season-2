package eu.nordtal.season.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.online.OnlineCount;
import eu.nordtal.season.database.online.OnlineDirectory;
import eu.nordtal.season.database.online.OnlinePlayer;
import eu.nordtal.season.database.online.OnlineRoster;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What {@link ServicesApi} makes of what {@link OnlineDirectory} and {@link OnlineRoster} hand back, in memory. */
class ServicesApiTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID ADA = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID BEN = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID CIA = UUID.fromString("00000000-0000-4000-8000-000000000003");

    @Test
    void aSubjectWithNoRowAtAllIsSimplyNotInTheAnswer() {
        assertTrue(api(Map.of(), List.of()).read().counts().isEmpty());
    }

    @Test
    void aFreshRowsCountIsReturnedExactlyIncludingAGenuineZero() {
        final Map<String, Integer> players = api(
                        Map.of(
                                "smp", new OnlineCount("smp", 3, NOW.minusSeconds(1)),
                                "limbo", new OnlineCount("limbo", 0, NOW)),
                        List.of())
                .read()
                .counts();

        assertEquals(3, players.get("smp"));
        assertTrue(players.containsKey("limbo"), "a genuine 0 must still be present, not dropped");
        assertEquals(0, players.get("limbo"));
    }

    @Test
    void aRowJustInsideTheStalenessCutoffIsStillTrusted() {
        final Instant justInside = NOW.minus(ServicesApi.STALE_AFTER);

        assertEquals(
                7,
                api(Map.of("smp", new OnlineCount("smp", 7, justInside)), List.of())
                        .read()
                        .counts()
                        .get("smp"));
    }

    @Test
    void aRowOlderThanTheCutoffIsTreatedExactlyLikeNoRowAtAll() {
        // A service that stopped writing is dropped, whatever its row last held.
        final Instant tooOld = NOW.minus(ServicesApi.STALE_AFTER).minusSeconds(1);

        assertFalse(
                api(Map.of("smp", new OnlineCount("smp", 99, tooOld)), List.of())
                        .read()
                        .counts()
                        .containsKey("smp"),
                "a stale row must read exactly like no row - not like the last number it saw");
    }

    @Test
    void proxyBeingStaleTakesEverySubjectDownWithItSinceItIsTheOnlyWriter() {
        final Instant tooOld = NOW.minus(ServicesApi.STALE_AFTER).minusSeconds(1);

        assertTrue(
                api(
                                Map.of(
                                        "smp", new OnlineCount("smp", 4, tooOld),
                                        "hunger-games", new OnlineCount("hunger-games", 2, tooOld),
                                        "limbo", new OnlineCount("limbo", 0, tooOld),
                                        "proxy", new OnlineCount("proxy", 6, tooOld)),
                                List.of())
                        .read()
                        .counts()
                        .isEmpty(),
                "every row shares one writer, so a stale write makes all four unknown at once");
    }

    @Test
    void nobodyWrittenMeansNoKeyAtAllNotAnEmptyListUnderProxy() {
        assertTrue(
                api(Map.of(), List.of()).read().roster().isEmpty(),
                "an empty list is a claim; absence is the honest answer");
    }

    @Test
    void aFreshPlayerIsOnTheirOwnServersListAndOnTheNetworks() {
        final Map<String, List<OnlinePlayer>> roster = api(
                        Map.of(), List.of(new OnlinePlayer(ADA, "Ada", "smp", NOW.minusSeconds(1))))
                .read()
                .roster();

        assertEquals(List.of("Ada"), names(roster.get("smp")));
        assertEquals(
                List.of("Ada"),
                names(roster.get(ServicesApi.PROXY)),
                "the proxy's row is the network, so everybody fresh is on its list");
        assertEquals(2, roster.size(), "and nothing else was invented");
    }

    @Test
    void aPlayerWithNoServerIsOnTheNetworksListAndOnNobodyElses() {
        final Map<String, List<OnlinePlayer>> roster = api(Map.of(), List.of(new OnlinePlayer(ADA, "Ada", null, NOW)))
                .read()
                .roster();

        assertEquals(
                List.of("Ada"),
                names(roster.get(ServicesApi.PROXY)),
                "the proxy counts them, so the proxy's list has them");
        assertEquals(1, roster.size(), "no backend may claim a player no backend has");
    }

    @Test
    void aPlayerOlderThanTheCutoffVanishesNoEmptyNameNoPlaceholderNoZero() {
        final Instant tooOld = NOW.minus(ServicesApi.STALE_AFTER).minusSeconds(1);

        final Map<String, List<OnlinePlayer>> roster = api(
                        Map.of(), List.of(new OnlinePlayer(ADA, "Ada", "smp", tooOld)))
                .read()
                .roster();

        assertTrue(roster.isEmpty(), "a stale player is not a nameless one, they are no row at all");
    }

    @Test
    void aPlayerRightAtTheCutoffIsStillTrustedTheSameAsACountIs() {
        final Instant justInside = NOW.minus(ServicesApi.STALE_AFTER);

        assertEquals(
                List.of("Ada"),
                names(api(Map.of(), List.of(new OnlinePlayer(ADA, "Ada", "smp", justInside)))
                        .read()
                        .roster()
                        .get("smp")));
    }

    @Test
    void theStaleOnesDropOutAndTheFreshOnesStayInTheSameRead() {
        final Instant tooOld = NOW.minus(ServicesApi.STALE_AFTER).minusSeconds(1);

        final Map<String, List<OnlinePlayer>> roster = api(
                        Map.of(),
                        List.of(new OnlinePlayer(ADA, "Ada", "smp", NOW), new OnlinePlayer(BEN, "Ben", "smp", tooOld)))
                .read()
                .roster();

        assertEquals(List.of("Ada"), names(roster.get("smp")));
    }

    @Test
    void eachListComesBackInNameOrderSoTheThreeFacesDrawnDoNotReshuffle() {
        final Map<String, List<OnlinePlayer>> roster = api(
                        Map.of(),
                        List.of(
                                new OnlinePlayer(CIA, "cia", "smp", NOW),
                                new OnlinePlayer(BEN, "Ben", "smp", NOW),
                                new OnlinePlayer(ADA, "ada", "smp", NOW)))
                .read()
                .roster();

        assertEquals(List.of("ada", "Ben", "cia"), names(roster.get("smp")));
    }

    @Test
    void theCountsAndTheRosterAreReadAgainstOneInstantNotTwo() {
        // A clock that moves on every call would judge counts and players at different moments around the cutoff.
        final Instant justInside = NOW.minus(ServicesApi.STALE_AFTER);
        final ServicesApi api = new ServicesApi(
                new FakeDirectory(Map.of("smp", new OnlineCount("smp", 1, justInside))),
                new FakeRoster(List.of(new OnlinePlayer(ADA, "Ada", "smp", justInside))),
                new SteppingClock(NOW));

        final ServicesApi.Online online = api.read();

        assertTrue(online.counts().containsKey("smp"));
        assertTrue(
                online.roster().containsKey("smp"),
                "one clock reading per response - both halves right at the cutoff, or neither");
    }

    @Test
    void whatADeploymentWithNoDatabaseAnswersIsAbsenceNotZeroes() {
        assertTrue(ServicesApi.Online.NONE.counts().isEmpty());
        assertTrue(ServicesApi.Online.NONE.roster().isEmpty());
    }

    private static ServicesApi api(final Map<String, OnlineCount> counts, final List<OnlinePlayer> players) {
        return new ServicesApi(new FakeDirectory(counts), new FakeRoster(players), CLOCK);
    }

    private static List<String> names(final List<OnlinePlayer> players) {
        return players == null
                ? List.of()
                : players.stream().map(OnlinePlayer::name).toList();
    }

    /** Answers whatever it was constructed with. */
    private static final class FakeDirectory implements OnlineDirectory {

        private final Map<String, OnlineCount> rows;

        FakeDirectory(final Map<String, OnlineCount> rows) {
            this.rows = new LinkedHashMap<>(rows);
        }

        @Override
        public void write(final Map<String, Integer> counts) {
            throw new UnsupportedOperationException("ServicesApi only reads");
        }

        @Override
        public Map<String, OnlineCount> current() {
            return rows;
        }
    }

    /** The same, for the player list. */
    private static final class FakeRoster implements OnlineRoster {

        private final List<OnlinePlayer> rows;

        FakeRoster(final List<OnlinePlayer> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        public void replace(final Collection<Presence> connected) {
            throw new UnsupportedOperationException("ServicesApi only reads");
        }

        @Override
        public List<OnlinePlayer> current() {
            return rows;
        }
    }

    /** A clock that moves on by a second every time it is asked. */
    private static final class SteppingClock extends Clock {

        private Instant now;

        SteppingClock(final Instant start) {
            this.now = start;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            final Instant answer = now;
            now = now.plusSeconds(1);
            return answer;
        }
    }
}

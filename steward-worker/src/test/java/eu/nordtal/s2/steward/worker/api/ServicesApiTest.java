package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.common.online.OnlineCount;
import eu.nordtal.s2.common.online.OnlineDirectory;
import eu.nordtal.s2.common.online.OnlinePlayer;
import eu.nordtal.s2.common.online.OnlineRoster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ServicesApi} does with what {@link OnlineDirectory} and {@link OnlineRoster} hand
 * back - entirely in memory, against fake directories, the same way {@code PlaytimeWriterTest}
 * drives {@code PlaytimeStore} without a database.
 */
class ServicesApiTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID ADA = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID BEN = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID CIA = UUID.fromString("00000000-0000-4000-8000-000000000003");

    // ---------------------------------------------------------------- the counts (steward/86)

    @Test
    @DisplayName("a subject with no row at all is simply not in the answer")
    void aSubjectNeverWrittenIsAbsent() {
        assertTrue(api(Map.of(), List.of()).read().counts().isEmpty());
    }

    @Test
    @DisplayName("a fresh row's count is returned exactly - including a genuine zero")
    void aFreshRowIsReturnedAsItStands() {
        final Map<String, Integer> players = api(Map.of(
                "smp", new OnlineCount("smp", 3, NOW.minusSeconds(1)),
                "limbo", new OnlineCount("limbo", 0, NOW)), List.of()).read().counts();

        assertEquals(3, players.get("smp"));
        assertTrue(players.containsKey("limbo"), "a genuine 0 must still be present, not dropped");
        assertEquals(0, players.get("limbo"));
    }

    @Test
    @DisplayName("a row just inside the staleness cutoff is still trusted")
    void aRowRightAtTheCutoffIsStillTrusted() {
        final Instant justInside = NOW.minus(ServicesApi.STALE_AFTER);

        assertEquals(7, api(Map.of("smp", new OnlineCount("smp", 7, justInside)), List.of())
                .read().counts().get("smp"));
    }

    @Test
    @DisplayName("a row older than the cutoff is treated exactly like no row at all")
    void aStaleRowIsDropped() {
        // proxy stopped writing a while ago - this is the case the class exists for,
        // never mind what number happened to be sitting in the row when it stopped.
        final Instant tooOld = NOW.minus(ServicesApi.STALE_AFTER).minusSeconds(1);

        assertFalse(api(Map.of("smp", new OnlineCount("smp", 99, tooOld)), List.of())
                        .read().counts().containsKey("smp"),
                "a stale row must read exactly like no row - not like the last number it saw");
    }

    @Test
    @DisplayName("proxy being stale takes every subject down with it, since it is the only writer")
    void allFourGoStaleTogetherWhenProxyStopsWriting() {
        final Instant tooOld = NOW.minus(ServicesApi.STALE_AFTER).minusSeconds(1);

        assertTrue(api(Map.of(
                "smp", new OnlineCount("smp", 4, tooOld),
                "hunger-games", new OnlineCount("hunger-games", 2, tooOld),
                "limbo", new OnlineCount("limbo", 0, tooOld),
                "proxy", new OnlineCount("proxy", 6, tooOld)), List.of())
                        .read().counts().isEmpty(),
                "every row shares one writer, so a stale write makes all four unknown at once");
    }

    // ---------------------------------------------------------------- the roster (steward/111)

    @Test
    @DisplayName("nobody written means no key at all - not an empty list under proxy")
    void anEmptyRosterProducesNoKeys() {
        assertTrue(api(Map.of(), List.of()).read().roster().isEmpty(),
                "an empty list is a claim; absence is the honest answer");
    }

    @Test
    @DisplayName("a fresh player is on their own server's list and on the network's")
    void aFreshPlayerAppearsUnderBothKeys() {
        final Map<String, List<OnlinePlayer>> roster = api(Map.of(),
                List.of(new OnlinePlayer(ADA, "Ada", "smp", NOW.minusSeconds(1)))).read().roster();

        assertEquals(List.of("Ada"), names(roster.get("smp")));
        assertEquals(List.of("Ada"), names(roster.get(ServicesApi.PROXY)),
                "the proxy's row is the network, so everybody fresh is on its list");
        assertEquals(2, roster.size(), "and nothing else was invented");
    }

    @Test
    @DisplayName("a player with no server is on the network's list and on nobody else's")
    void aPlayerBetweenServersIsOnlyOnTheNetworkList() {
        final Map<String, List<OnlinePlayer>> roster = api(Map.of(),
                List.of(new OnlinePlayer(ADA, "Ada", null, NOW))).read().roster();

        assertEquals(List.of("Ada"), names(roster.get(ServicesApi.PROXY)),
                "the proxy counts them, so the proxy's list has them");
        assertEquals(1, roster.size(), "no backend may claim a player no backend has");
    }

    @Test
    @DisplayName("a player older than the cutoff VANISHES - no empty name, no placeholder, no zero")
    void aStalePlayerIsGoneEntirely() {
        final Instant tooOld = NOW.minus(ServicesApi.STALE_AFTER).minusSeconds(1);

        final Map<String, List<OnlinePlayer>> roster = api(Map.of(),
                List.of(new OnlinePlayer(ADA, "Ada", "smp", tooOld))).read().roster();

        assertTrue(roster.isEmpty(), "a stale player is not a nameless one, they are no row at all");
    }

    @Test
    @DisplayName("a player right at the cutoff is still trusted, the same as a count is")
    void aPlayerRightAtTheCutoffIsStillTrusted() {
        final Instant justInside = NOW.minus(ServicesApi.STALE_AFTER);

        assertEquals(List.of("Ada"), names(api(Map.of(),
                List.of(new OnlinePlayer(ADA, "Ada", "smp", justInside)))
                .read().roster().get("smp")));
    }

    @Test
    @DisplayName("the stale ones drop out and the fresh ones stay, in the same read")
    void staleAndFreshDoNotTakeEachOtherWithThem() {
        final Instant tooOld = NOW.minus(ServicesApi.STALE_AFTER).minusSeconds(1);

        final Map<String, List<OnlinePlayer>> roster = api(Map.of(), List.of(
                new OnlinePlayer(ADA, "Ada", "smp", NOW),
                new OnlinePlayer(BEN, "Ben", "smp", tooOld))).read().roster();

        assertEquals(List.of("Ada"), names(roster.get("smp")));
    }

    @Test
    @DisplayName("each list comes back in name order, so the three faces drawn do not reshuffle")
    void theListIsSortedByName() {
        final Map<String, List<OnlinePlayer>> roster = api(Map.of(), List.of(
                new OnlinePlayer(CIA, "cia", "smp", NOW),
                new OnlinePlayer(BEN, "Ben", "smp", NOW),
                new OnlinePlayer(ADA, "ada", "smp", NOW))).read().roster();

        assertEquals(List.of("ada", "Ben", "cia"), names(roster.get("smp")));
    }

    @Test
    @DisplayName("the counts and the roster are read against one instant, not two")
    void bothHalvesShareOneReading() {
        // A clock that moves on every call would let the counts be judged at one moment and the
        // players at another - a difference of a tick around the cutoff, and nothing in the answer
        // to say which of the two was right.
        final Instant justInside = NOW.minus(ServicesApi.STALE_AFTER);
        final ServicesApi api = new ServicesApi(
                new FakeDirectory(Map.of("smp", new OnlineCount("smp", 1, justInside))),
                new FakeRoster(List.of(new OnlinePlayer(ADA, "Ada", "smp", justInside))),
                new SteppingClock(NOW));

        final ServicesApi.Online online = api.read();

        assertTrue(online.counts().containsKey("smp"));
        assertTrue(online.roster().containsKey("smp"),
                "one clock reading per response - both halves right at the cutoff, or neither");
    }

    @Test
    @DisplayName("what a deployment with no database answers is absence, not zeroes")
    void theEmptyAnswerIsEmpty() {
        assertTrue(ServicesApi.Online.NONE.counts().isEmpty());
        assertTrue(ServicesApi.Online.NONE.roster().isEmpty());
    }

    // ---------------------------------------------------------------- plumbing

    private static ServicesApi api(final Map<String, OnlineCount> counts,
                                   final List<OnlinePlayer> players) {
        return new ServicesApi(new FakeDirectory(counts), new FakeRoster(players), CLOCK);
    }

    private static List<String> names(final List<OnlinePlayer> players) {
        return players == null ? List.of() : players.stream().map(OnlinePlayer::name).toList();
    }

    /** Answers whatever it was constructed with; there is no database behind this test. */
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

    /** Moves on by a second every time it is asked, which is what {@link #bothHalvesShareOneReading} catches. */
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

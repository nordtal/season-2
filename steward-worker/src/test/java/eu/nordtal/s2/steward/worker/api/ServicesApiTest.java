package eu.nordtal.s2.steward.worker.api;

import eu.nordtal.s2.common.online.OnlineCount;
import eu.nordtal.s2.common.online.OnlineDirectory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link ServicesApi} does with what {@link OnlineDirectory} hands back - entirely in memory,
 * against a fake directory, the same way {@code PlaytimeWriterTest} drives {@code PlaytimeStore}
 * without a database.
 */
class ServicesApiTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("a subject with no row at all is simply not in the answer")
    void aSubjectNeverWrittenIsAbsent() {
        final ServicesApi api = new ServicesApi(new FakeDirectory(Map.of()), CLOCK);

        assertTrue(api.players().isEmpty());
    }

    @Test
    @DisplayName("a fresh row's count is returned exactly - including a genuine zero")
    void aFreshRowIsReturnedAsItStands() {
        final FakeDirectory directory = new FakeDirectory(Map.of(
                "smp", new OnlineCount("smp", 3, NOW.minusSeconds(1)),
                "limbo", new OnlineCount("limbo", 0, NOW)));
        final ServicesApi api = new ServicesApi(directory, CLOCK);

        final Map<String, Integer> players = api.players();
        assertEquals(3, players.get("smp"));
        assertTrue(players.containsKey("limbo"), "a genuine 0 must still be present, not dropped");
        assertEquals(0, players.get("limbo"));
    }

    @Test
    @DisplayName("a row just inside the staleness cutoff is still trusted")
    void aRowRightAtTheCutoffIsStillTrusted() {
        final Instant justInside = NOW.minus(ServicesApi.STALE_AFTER);
        final FakeDirectory directory = new FakeDirectory(
                Map.of("smp", new OnlineCount("smp", 7, justInside)));

        assertEquals(7, new ServicesApi(directory, CLOCK).players().get("smp"));
    }

    @Test
    @DisplayName("a row older than the cutoff is treated exactly like no row at all")
    void aStaleRowIsDropped() {
        // network-control stopped writing a while ago - this is the case the class exists for,
        // never mind what number happened to be sitting in the row when it stopped.
        final Instant tooOld = NOW.minus(ServicesApi.STALE_AFTER).minusSeconds(1);
        final FakeDirectory directory = new FakeDirectory(
                Map.of("smp", new OnlineCount("smp", 99, tooOld)));

        assertFalse(new ServicesApi(directory, CLOCK).players().containsKey("smp"),
                "a stale row must read exactly like no row - not like the last number it saw");
    }

    @Test
    @DisplayName("network-control being stale takes every subject down with it, since it is the only writer")
    void allFourGoStaleTogetherWhenNetworkControlStopsWriting() {
        final Instant tooOld = NOW.minus(ServicesApi.STALE_AFTER).minusSeconds(1);
        final FakeDirectory directory = new FakeDirectory(Map.of(
                "smp", new OnlineCount("smp", 4, tooOld),
                "hunger-games", new OnlineCount("hunger-games", 2, tooOld),
                "limbo", new OnlineCount("limbo", 0, tooOld),
                "network-control", new OnlineCount("network-control", 6, tooOld)));

        assertTrue(new ServicesApi(directory, CLOCK).players().isEmpty(),
                "every row shares one writer, so a stale write makes all four unknown at once");
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
}

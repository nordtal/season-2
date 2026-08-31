package eu.nordtal.s2.networkcontrol.playtime;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;
import eu.nordtal.s2.networkcontrol.MutableClock;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The play-time counter's arithmetic, in memory. What goes into {@code player_playtime} is the
 * question docs/smp.md#prestige--a-crest-earned-by-time cares about - the prestige crest is
 * derived from those seconds - and it is entirely a matter of subtracting instants, so it is
 * testable without a database.
 * <p>
 * The SQL itself ({@code seconds = seconds + N}) is not exercised here; that is
 * {@link PlaytimeStore}'s one statement, and the store is a functional interface precisely so this
 * class can be driven by a list.
 * </p>
 */
class PlaytimeWriterTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaytimeWriterTest.class);
    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DISCORD_ID = "300000000000000001";
    private static final String OTHER_DISCORD_ID = "300000000000000002";

    private MutableClock clock;
    private RecordingStore store;
    private LoginRoster roster;
    private PlaytimeWriter writer;

    @BeforeEach
    void freshWriter() {
        clock = new MutableClock(Instant.parse("2026-08-31T12:00:00Z"));
        store = new RecordingStore();
        roster = new LoginRoster();
        writer = new PlaytimeWriter(store, roster, LOGGER, clock);
    }

    // ---------------------------------------------------------------- the two write paths

    @Test
    void disconnectingWritesTheWholeSession() {
        join(PLAYER, DISCORD_ID);
        clock.advance(Duration.ofMinutes(45));

        writer.flush(PLAYER);

        assertEquals(List.of(DISCORD_ID + "+" + Duration.ofMinutes(45).toSeconds()), store.writes);
    }

    @Test
    void aPeriodicFlushWritesOnlyWhatHasHappenedSinceTheLastOne() {
        // "It writes the total on disconnect and periodically in between, so a crash costs minutes
        // rather than a whole session" - and the write is an addition, so each flush must carry the
        // slice and not the running total.
        join(PLAYER, DISCORD_ID);

        clock.advance(Duration.ofSeconds(60));
        writer.flushAll();
        clock.advance(Duration.ofSeconds(60));
        writer.flushAll();
        clock.advance(Duration.ofSeconds(15));
        writer.flushAll();

        assertEquals(List.of(DISCORD_ID + "+60", DISCORD_ID + "+60", DISCORD_ID + "+15"), store.writes,
                "three slices adding up to 135 seconds, never 60 + 120 + 135");
    }

    @Test
    void everyConnectedPlayerIsFlushedByOnePass() {
        join(PLAYER, DISCORD_ID);
        join(OTHER, OTHER_DISCORD_ID);
        clock.advance(Duration.ofSeconds(30));

        assertEquals(2, writer.flushAll());
        assertEquals(2, store.writes.size());
    }

    // ---------------------------------------------------------------- not losing time

    @Test
    void subSecondRemaindersSurviveAFlushInsteadOfBeingThrownAway() {
        // Flushing every 60s at 60.4s intervals would otherwise lose 0.4s each time - about six
        // minutes over a 24-hour session, which is a whole prestige threshold's worth of nothing.
        join(PLAYER, DISCORD_ID);

        clock.advance(Duration.ofMillis(60_400));
        writer.flushAll();
        clock.advance(Duration.ofMillis(60_400));
        writer.flushAll();
        clock.advance(Duration.ofMillis(60_400));
        writer.flushAll();

        assertEquals(List.of(DISCORD_ID + "+60", DISCORD_ID + "+60", DISCORD_ID + "+61"), store.writes,
                "the third flush picks up the 1.2s the first two carried forward");
    }

    @Test
    void nothingIsWrittenForASessionShorterThanASecond() {
        join(PLAYER, DISCORD_ID);
        clock.advance(Duration.ofMillis(400));

        assertFalse(writer.flush(PLAYER));
        assertTrue(store.writes.isEmpty(), "an INSERT of zero seconds is pure noise");
    }

    @Test
    void aFailedFlushKeepsTheTimeOwedRatherThanDroppingIt() {
        join(PLAYER, DISCORD_ID);
        clock.advance(Duration.ofSeconds(60));

        store.failing = true;
        assertEquals(0, writer.flushAll(), "a flush that threw wrote nothing");
        store.failing = false;

        clock.advance(Duration.ofSeconds(30));
        writer.flushAll();

        assertEquals(List.of(DISCORD_ID + "+90"), store.writes,
                "the 60 seconds the failed flush could not write are still owed, so the next one "
                        + "carries all 90");
    }

    // ---------------------------------------------------------------- session lifecycle

    @Test
    void aDisconnectedPlayerIsNoLongerCounted() {
        join(PLAYER, DISCORD_ID);
        clock.advance(Duration.ofSeconds(30));
        writer.flush(PLAYER);
        forget(PLAYER);

        clock.advance(Duration.ofHours(4));
        assertEquals(0, writer.flushAll(), "an offline player must not keep accruing time");
        assertEquals(1, store.writes.size());
    }

    @Test
    void aPlayerTheLoginPathNeverIdentifiedIsNotCountedAtAll() {
        // The only way in is a login the fallback cache admitted while the database was down, so
        // there is no Discord id - and player_playtime is keyed by exactly that.
        writer.begin(PLAYER, "unknown-player");
        clock.advance(Duration.ofHours(1));

        assertEquals(0, writer.tracked());
        assertEquals(0, writer.flushAll());
        assertTrue(store.writes.isEmpty(), "a guessed key would corrupt somebody else's total");
    }

    @Test
    void aReconnectStartsAFreshSessionAndTheTwoAddUpInTheDatabase() {
        join(PLAYER, DISCORD_ID);
        clock.advance(Duration.ofSeconds(90));
        forget(PLAYER);

        clock.advance(Duration.ofHours(2));
        join(PLAYER, DISCORD_ID);
        clock.advance(Duration.ofSeconds(10));
        writer.flushAll();

        assertEquals(List.of(DISCORD_ID + "+90", DISCORD_ID + "+10"), store.writes,
                "the two hours offline are not counted, and both slices are additions so the row "
                        + "ends up with 100 without this class ever holding a total");
    }

    // ---------------------------------------------------------------- helpers

    private void join(final UUID mcUuid, final String discordId) {
        roster.remember(mcUuid, new AccessState(mcUuid, discordId, MemberState.MEMBER, true,
                clock.instant().plus(Duration.ofDays(1)), false, false, Locale.ENGLISH, SeasonPhase.SMP));
        writer.begin(mcUuid, "player-" + discordId);
    }

    private void forget(final UUID mcUuid) {
        // Stands in for the DisconnectEvent handler, which is one flush plus one removal.
        writer.flush(mcUuid);
        writer.forget(mcUuid);
    }

    /** Records what it was asked to add, or refuses to. */
    private static final class RecordingStore implements PlaytimeStore {

        private final List<String> writes = new ArrayList<>();
        private boolean failing;

        @Override
        public void add(final String discordId, final long seconds) {
            if (failing) {
                throw new IllegalStateException("the database is unreachable");
            }
            writes.add(discordId + "+" + seconds);
        }
    }
}

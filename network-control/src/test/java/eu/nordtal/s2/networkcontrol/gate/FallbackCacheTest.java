package eu.nordtal.s2.networkcontrol.gate;

import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.MemberState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link FallbackCache} entirely in memory - no database, no Docker. It is a pure
 * function of what it was told and how much time has passed, which is exactly what
 * {@link MutableClock} lets these tests control without a single {@code Thread.sleep}.
 */
class FallbackCacheTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRANGER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DISCORD_ID = "300000000000000001";

    private MutableClock clock;
    private FallbackCache cache;

    @BeforeEach
    void freshCache() {
        clock = new MutableClock(Instant.parse("2026-08-30T12:00:00Z"));
        cache = new FallbackCache(Duration.ofMinutes(15), clock);
    }

    @Test
    void anUnknownAccountIsRefused() {
        assertFalse(cache.mayJoin(STRANGER));
    }

    @Test
    void aRecentlySeenAccountWithActiveAccessIsLetIn() {
        cache.remember(PLAYER, activeState());

        assertTrue(cache.mayJoin(PLAYER));
    }

    @Test
    void theCachedLocaleIsRememberedAlongsideTheDecision() {
        cache.remember(PLAYER, activeState(Locale.GERMAN));

        assertEquals(Locale.GERMAN, cache.localeOf(PLAYER));
    }

    @Test
    void anUnknownAccountsLocaleFallsBackToEnglish() {
        assertEquals(Locale.ENGLISH, cache.localeOf(STRANGER));
    }

    @Test
    void aStateWithoutActiveAccessIsNeverStoredAtAll() {
        cache.remember(PLAYER, inactiveState());

        assertFalse(cache.mayJoin(PLAYER));
        assertEquals(0, cache.size(), "a state that could never let anyone in is not worth keeping");
    }

    @Test
    void refusesEverybodyOnceTheWindowHasPassed() {
        cache.remember(PLAYER, activeState());
        assertTrue(cache.mayJoin(PLAYER), "precondition");

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertFalse(cache.mayJoin(PLAYER));
    }

    @Test
    void anEntryRightAtTheEdgeOfTheWindowIsStillUsable() {
        cache.remember(PLAYER, activeState());

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));

        assertTrue(cache.mayJoin(PLAYER));
    }

    @Test
    void aLaterUnsuccessfulStateEvictsAnEarlierPositiveOne() {
        // The database must not be able to hand out a stale "yes" once it has told us "no" more
        // recently - even though both calls succeeded, so this is not the DB-unreachable path at
        // all. remember() is what a healthy accessState() call also goes through.
        cache.remember(PLAYER, activeState());
        assertTrue(cache.mayJoin(PLAYER), "precondition");

        cache.remember(PLAYER, inactiveState());

        assertFalse(cache.mayJoin(PLAYER));
    }

    @Test
    void reMemberingRefreshesTheWindow() {
        cache.remember(PLAYER, activeState());
        clock.advance(Duration.ofMinutes(10));
        cache.remember(PLAYER, activeState());
        clock.advance(Duration.ofMinutes(10));

        // 20 minutes since the first remember(), but only 10 since the second - still inside the
        // 15-minute window because the entry was refreshed, not merely re-read.
        assertTrue(cache.mayJoin(PLAYER));
    }

    @Test
    void aNonPositiveWindowIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new FallbackCache(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new FallbackCache(Duration.ofMinutes(-1)));
    }

    // ---------------------------------------------------------------- helpers

    private AccessState activeState() {
        return activeState(Locale.ENGLISH);
    }

    private AccessState activeState(final Locale locale) {
        return new AccessState(PLAYER, DISCORD_ID, MemberState.MEMBER, true,
                clock.instant().plus(Duration.ofDays(1)), false, false, locale);
    }

    private AccessState inactiveState() {
        return new AccessState(PLAYER, DISCORD_ID, MemberState.MEMBER, false, null, false, false, Locale.ENGLISH);
    }

    /** A settable {@link Clock}, so these tests advance time instead of sleeping through it. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(final Instant now) {
            this.now = now;
        }

        void advance(final Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}

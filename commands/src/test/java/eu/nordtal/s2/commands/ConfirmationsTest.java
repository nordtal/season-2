package eu.nordtal.s2.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "type it again" confirmation, driven by a settable clock rather than by sleeping.
 *
 * <p>Every case here is about a way the mechanism could look like it works and not: confirming a
 * different command, confirming somebody else's, confirming twice, or confirming after the window
 * has passed. None of them is visible from the class's own source, and the command it guards is the
 * one that disconnects every player without access.</p>
 */
class ConfirmationsTest {

    private Instant now = Instant.parse("2026-09-04T12:00:00Z");
    private final Clock clock = new Clock() {
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
            return now;
        }
    };

    private final Confirmations confirmations = new Confirmations(Duration.ofSeconds(30), clock);

    private static final NordtalUser TILL = user("11111111-2222-3333-4444-555555555555");
    private static final NordtalUser SOMEBODY_ELSE = user("99999999-8888-7777-6666-555555555555");

    @Test
    @DisplayName("the first ask is not a confirmation; the second one is")
    void twoInvocations() {
        assertFalse(confirmations.confirm(TILL, "/phase set SMP"));
        assertTrue(confirmations.confirm(TILL, "/phase set SMP"));
    }

    @Test
    @DisplayName("a confirmation is consumed, so the third invocation asks again")
    void confirmingDoesNotDisarmTheCommand() {
        // Otherwise the window would be a period during which the command is unguarded rather than
        // one confirmation wide.
        confirmations.confirm(TILL, "/phase set SMP");
        assertTrue(confirmations.confirm(TILL, "/phase set SMP"));
        assertFalse(confirmations.confirm(TILL, "/phase set SMP"),
                "a confirmed command has to be asked for again from scratch");
    }

    @Test
    @DisplayName("a pending confirmation confirms only the command it was asked about")
    void theArgumentsArePartOfTheKey() {
        // The case this exists for: /phase set MAINTENANCE lets only admins in, /phase set SMP
        // disconnects everybody without access. Keying on the person alone would let the second one
        // ride the first one's confirmation.
        assertFalse(confirmations.confirm(TILL, "/phase set MAINTENANCE"));
        assertFalse(confirmations.confirm(TILL, "/phase set SMP"),
                "a different command must not be confirmed by a pending one");
        assertTrue(confirmations.confirm(TILL, "/phase set SMP"));
    }

    @Test
    @DisplayName("one admin cannot confirm another admin's command")
    void confirmationsAreNotShared() {
        assertFalse(confirmations.confirm(TILL, "/phase set SMP"));
        assertFalse(confirmations.confirm(SOMEBODY_ELSE, "/phase set SMP"));
        assertTrue(confirmations.confirm(TILL, "/phase set SMP"));
    }

    @Test
    @DisplayName("a confirmation that arrives after the window is a fresh ask, not a switch")
    void theWindowIsEnforced() {
        assertFalse(confirmations.confirm(TILL, "/phase set SMP"));
        now = now.plusSeconds(31);
        assertFalse(confirmations.confirm(TILL, "/phase set SMP"),
                "walking away from a keyboard must not leave a phase switch armed");
        assertTrue(confirmations.confirm(TILL, "/phase set SMP"));
    }

    @Test
    @DisplayName("confirming on the last second of the window still works")
    void theBoundaryIsInclusive() {
        assertFalse(confirmations.confirm(TILL, "/phase set SMP"));
        now = now.plusSeconds(30);
        assertTrue(confirmations.confirm(TILL, "/phase set SMP"));
    }

    @Test
    @DisplayName("expired entries are dropped rather than accumulating for the life of the process")
    void nothingIsKeptForever() {
        confirmations.confirm(TILL, "/phase set SMP");
        confirmations.confirm(TILL, "/phase launch 2026-10-01 18:00");
        assertEquals(2, confirmations.size());

        now = now.plusSeconds(31);
        confirmations.confirm(SOMEBODY_ELSE, "/phase show");
        assertEquals(1, confirmations.size(), "both stale entries should have been swept");
    }

    @Test
    @DisplayName("cancelling forgets the pending confirmation")
    void forgettingWorks() {
        confirmations.confirm(TILL, "/phase set SMP");
        confirmations.forget(TILL, "/phase set SMP");
        assertFalse(confirmations.confirm(TILL, "/phase set SMP"));
    }

    @Test
    @DisplayName("consume never arms, so the second step of a two-command flow cannot arm itself")
    void consumeIsCheckOnly() {
        // The bug this method exists to make impossible: /hg start warns and /hg start confirm goes
        // through, so the second step cannot use confirm() - that arms on a miss, and a bare
        // /hg start confirm typed twice would then start a game below the recommended minimum
        // having never shown the warning.
        assertFalse(confirmations.consume(TILL, "/hg start"));
        assertFalse(confirmations.consume(TILL, "/hg start"),
                "consume must not leave anything behind for the next call to find");
        assertEquals(0, confirmations.size());
    }

    @Test
    @DisplayName("arm then consume is the two-command flow, and consuming twice does not repeat")
    void armAndConsume() {
        confirmations.arm(TILL, "/hg start");
        assertTrue(confirmations.consume(TILL, "/hg start"));
        assertFalse(confirmations.consume(TILL, "/hg start"),
                "one arming is one confirmation, not a window during which the command is open");
    }

    @Test
    @DisplayName("an armed confirmation expires the same way a retype one does")
    void armingExpires() {
        confirmations.arm(TILL, "/hg start");
        now = now.plusSeconds(31);
        assertFalse(confirmations.consume(TILL, "/hg start"));
    }

    @Test
    @DisplayName("the console has no identity of its own and still gets its own key")
    void theConsoleIsAnIdentityToo() {
        final NordtalUser console = new StubUser(null, null, "console");
        assertFalse(confirmations.confirm(console, "/phase set SMP"));
        assertFalse(confirmations.confirm(TILL, "/phase set SMP"),
                "a player must not confirm what the console asked for");
        assertTrue(confirmations.confirm(console, "/phase set SMP"));
    }

    private static NordtalUser user(final String uuid) {
        return new StubUser(UUID.fromString(uuid), "100000000000000001", "tester");
    }

    /** Only the three things {@link Confirmations} reads: the two identities and the name. */
    private record StubUser(UUID mcUuid, String discord, String name) implements NordtalUser {

        @Override
        public Optional<String> discordId() {
            return Optional.ofNullable(discord);
        }

        @Override
        public Optional<UUID> minecraftUuid() {
            return Optional.ofNullable(mcUuid);
        }

        @Override
        public Locale locale() {
            return Locale.ENGLISH;
        }

        @Override
        public boolean admin() {
            return true;
        }

        @Override
        public Origin origin() {
            return mcUuid == null ? Origin.CONSOLE : Origin.GAME;
        }

        @Override
        public void reply(final String messageKey, final Map<String, ?> placeholders) {
            throw new UnsupportedOperationException("this stub only carries an identity");
        }

        @Override
        public String phrase(final String messageKey) {
            throw new UnsupportedOperationException("this stub only carries an identity");
        }

        @Override
        public void replyLiteral(final String text) {
            throw new UnsupportedOperationException("this stub only carries an identity");
        }
    }
}

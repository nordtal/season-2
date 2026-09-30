package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The "type it again" confirmation, driven by a settable clock rather than by sleeping. */
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

    private static final NordtalUser OWNER = user("11111111-2222-3333-4444-555555555555");
    private static final NordtalUser SOMEBODY_ELSE = user("99999999-8888-7777-6666-555555555555");

    @Test
    void theFirstAskIsNotAConfirmationTheSecondOneIs() {
        assertFalse(confirmations.confirm(OWNER, "/phase set SMP"));
        assertTrue(confirmations.confirm(OWNER, "/phase set SMP"));
    }

    @Test
    void aConfirmationIsConsumedSoTheThirdInvocationAsksAgain() {
        // Otherwise the window would leave the command unguarded instead of covering one confirmation.
        confirmations.confirm(OWNER, "/phase set SMP");
        assertTrue(confirmations.confirm(OWNER, "/phase set SMP"));
        assertFalse(
                confirmations.confirm(OWNER, "/phase set SMP"),
                "a confirmed command has to be asked for again from scratch");
    }

    @Test
    void aPendingConfirmationConfirmsOnlyTheCommandItWasAskedAbout() {
        // /phase set MAINTENANCE lets only admins in, so confirming SMP must not confirm it.
        assertFalse(confirmations.confirm(OWNER, "/phase set MAINTENANCE"));
        assertFalse(
                confirmations.confirm(OWNER, "/phase set SMP"),
                "a different command must not be confirmed by a pending one");
        assertTrue(confirmations.confirm(OWNER, "/phase set SMP"));
    }

    @Test
    void oneAdminCannotConfirmAnotherAdminsCommand() {
        assertFalse(confirmations.confirm(OWNER, "/phase set SMP"));
        assertFalse(confirmations.confirm(SOMEBODY_ELSE, "/phase set SMP"));
        assertTrue(confirmations.confirm(OWNER, "/phase set SMP"));
    }

    @Test
    void aConfirmationThatArrivesAfterTheWindowIsAFreshAskNotASwitch() {
        assertFalse(confirmations.confirm(OWNER, "/phase set SMP"));
        now = now.plusSeconds(31);
        assertFalse(
                confirmations.confirm(OWNER, "/phase set SMP"),
                "walking away from a keyboard must not leave a phase switch armed");
        assertTrue(confirmations.confirm(OWNER, "/phase set SMP"));
    }

    @Test
    void confirmingOnTheLastSecondOfTheWindowStillWorks() {
        assertFalse(confirmations.confirm(OWNER, "/phase set SMP"));
        now = now.plusSeconds(30);
        assertTrue(confirmations.confirm(OWNER, "/phase set SMP"));
    }

    @Test
    void expiredEntriesAreDroppedRatherThanAccumulatingForTheLifeOfTheProcess() {
        confirmations.confirm(OWNER, "/phase set SMP");
        confirmations.confirm(OWNER, "/phase launch 2026-10-01 18:00");
        assertEquals(2, confirmations.size());

        now = now.plusSeconds(31);
        confirmations.confirm(SOMEBODY_ELSE, "/phase show");
        assertEquals(1, confirmations.size(), "both stale entries should have been swept");
    }

    @Test
    void cancellingForgetsThePendingConfirmation() {
        confirmations.confirm(OWNER, "/phase set SMP");
        confirmations.forget(OWNER, "/phase set SMP");
        assertFalse(confirmations.confirm(OWNER, "/phase set SMP"));
    }

    @Test
    void consumeNeverArmsSoTheSecondStepOfATwoCommandFlowCannotArmItself() {
        // /hg start warns, and only /hg start confirm may go through.
        assertFalse(confirmations.consume(OWNER, "/hg start"));
        assertFalse(
                confirmations.consume(OWNER, "/hg start"),
                "consume must not leave anything behind for the next call to find");
        assertEquals(0, confirmations.size());
    }

    @Test
    void armThenConsumeIsTheTwoCommandFlowAndConsumingTwiceDoesNotRepeat() {
        confirmations.arm(OWNER, "/hg start");
        assertTrue(confirmations.consume(OWNER, "/hg start"));
        assertFalse(
                confirmations.consume(OWNER, "/hg start"),
                "one arming is one confirmation, not a window during which the command is open");
    }

    @Test
    void anArmedConfirmationExpiresTheSameWayARetypeOneDoes() {
        confirmations.arm(OWNER, "/hg start");
        now = now.plusSeconds(31);
        assertFalse(confirmations.consume(OWNER, "/hg start"));
    }

    @Test
    void theConsoleHasNoIdentityOfItsOwnAndStillGetsItsOwnKey() {
        final NordtalUser console = new StubUser(null, null, "console");
        assertFalse(confirmations.confirm(console, "/phase set SMP"));
        assertFalse(
                confirmations.confirm(OWNER, "/phase set SMP"), "a player must not confirm what the console asked for");
        assertTrue(confirmations.confirm(console, "/phase set SMP"));
    }

    private static NordtalUser user(final String uuid) {
        return new StubUser(UUID.fromString(uuid), "100000000000000001", "tester");
    }

    /** Only the three things {@link Confirmations} reads: the two identities and the name. */
    private record StubUser(UUID mcUuid, String discord, String name) implements NordtalUser {

        @Override
        public Optional<DiscordId> discordId() {
            return Optional.ofNullable(discord).map(DiscordId::of);
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
        public void reply(final eu.nordtal.s2.messages.MessageRef message) {
            throw new UnsupportedOperationException("this stub only carries an identity");
        }

        @Override
        public String phrase(final eu.nordtal.s2.messages.MessageRef message) {
            throw new UnsupportedOperationException("this stub only carries an identity");
        }

        @Override
        public void replyLiteral(final String text) {
            throw new UnsupportedOperationException("this stub only carries an identity");
        }
    }
}

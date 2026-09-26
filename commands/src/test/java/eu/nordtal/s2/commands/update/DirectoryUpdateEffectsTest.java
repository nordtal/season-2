package eu.nordtal.s2.commands.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateSource;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The row says who asked and from where, from the user rather than from a per-process constant.
 *
 * The proxy picks {@code CONSOLE} for every player who types there.
 */
class DirectoryUpdateEffectsTest {

    private final FakeUpdateDirectory directory = new FakeUpdateDirectory();
    private final DirectoryUpdateEffects effects =
            new DirectoryUpdateEffects(directory, Runnable::run, (what, failure) -> {}, (id, user) -> {});

    @Test
    void aPlayerIsGameAndRecordedByName() {
        effects.submit(UpdateKind.REPORT, FakeUser.inGame());
        final FakeUpdateDirectory.Submitted row = directory.submitted.getFirst();
        assertEquals(UpdateSource.GAME, row.source());
        assertEquals("tester", row.requestedBy());
    }

    @Test
    void aDiscordMemberIsDiscordAndRecordedByIdWhichIsWhatDiscordUserIsKeyedBy() {
        effects.submit(UpdateKind.REPORT, FakeUser.inDiscord());
        final FakeUpdateDirectory.Submitted row = directory.submitted.getFirst();
        assertEquals(UpdateSource.DISCORD, row.source());
        assertEquals("100000000000000002", row.requestedBy());
    }

    @Test
    void theConsoleIsConsoleAndNobodyNotTheWordConsole() {
        effects.submit(UpdateKind.RESTART, FakeUser.console());
        final FakeUpdateDirectory.Submitted row = directory.submitted.getFirst();
        assertEquals(UpdateSource.CONSOLE, row.source());
        assertNull(row.requestedBy(), "requested_by means a person; a shell in the container is not one");
    }

    @Test
    void noKindIsWrittenWithACountdownOnItStewardWorkerStartsThatOnceItKnows() {
        // A countdown must not run before anybody knows whether there is anything to install.
        effects.submit(UpdateKind.REPORT, FakeUser.inGame());
        effects.submit(UpdateKind.UPDATE, FakeUser.inGame());
        effects.submit(UpdateKind.RESTART, FakeUser.inGame());

        assertEquals(
                List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO),
                directory.submitted.stream()
                        .map(FakeUpdateDirectory.Submitted::delay)
                        .toList(),
                "the countdown is UpdateDirectory#startCountdown's, on the row the worker has"
                        + " claimed and resolved");
    }
}

package eu.nordtal.s2.commands.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.update.RunRefused;
import eu.nordtal.s2.common.update.UpdateKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code /update down <service>} and {@code /update start [service]}.
 *
 * Only the scope is asserted: naming no service is the difference between one server and the network.
 */
class HoldServiceTest {

    private static FakeUpdateDirectory ask(
            final eu.nordtal.s2.commands.Declaration declaration, final Map<String, Object> arguments) {
        final FakeUpdateDirectory directory = new FakeUpdateDirectory();
        new HoldService(declaration)
                .run(
                        FakeUser.console(),
                        new Values(declaration, arguments),
                        new DirectoryUpdateEffects(directory, Runnable::run, (what, failure) -> {}, (id, user) -> {}));
        return directory;
    }

    @Test
    void updateDownSmpWritesADownForSmpAlone() {
        final FakeUpdateDirectory directory = ask(UpdateCommands.DOWN, Map.of("service", "smp"));

        assertEquals(1, directory.submitted.size());
        assertEquals(UpdateKind.DOWN, directory.submitted.getFirst().kind());
        assertEquals(
                List.of("smp"),
                directory.submitted.getFirst().services(),
                "the service was dropped on the way to the row, which makes this a DOWN with an"
                        + " empty scope - and an empty scope is the whole network");
    }

    @Test
    void aRefusedTakeDownSaysWhyInsteadOfBlamingTheDatabase() {
        final FakeUpdateDirectory directory = new FakeUpdateDirectory();
        directory.refusing = RunRefused.alreadyHeld(List.of("smp"));
        final FakeUser user = FakeUser.console();

        new HoldService(UpdateCommands.DOWN)
                .run(
                        user,
                        new Values(UpdateCommands.DOWN, Map.of("service", "smp")),
                        new DirectoryUpdateEffects(directory, Runnable::run, (what, failure) -> {}, (id, who) -> {}));

        assertEquals("update.already-down", user.only().key());
        assertEquals("smp", user.only().of("services"));
    }

    @Test
    void aRunRefusedBecauseAnotherIsOpenSaysSo() {
        final FakeUpdateDirectory directory = new FakeUpdateDirectory();
        directory.refusing = RunRefused.runOpen(new eu.nordtal.s2.common.update.UpdateRequest(
                7L,
                UpdateKind.UPDATE,
                eu.nordtal.s2.common.update.UpdateStatus.RUNNING,
                eu.nordtal.s2.common.update.UpdateSource.DISCORD,
                "a",
                java.time.Instant.now(),
                java.time.Instant.now(),
                null,
                null,
                null));
        final FakeUser user = FakeUser.console();

        new RunUpdate(UpdateCommands.NOW)
                .run(
                        user,
                        new Values(UpdateCommands.NOW, Map.of()),
                        new DirectoryUpdateEffects(directory, Runnable::run, (what, failure) -> {}, (id, who) -> {}));

        assertEquals("update.busy", user.only().key());
    }

    @Test
    void updateStartSmpWritesAStartForSmpAlone() {
        final FakeUpdateDirectory directory = ask(UpdateCommands.START, Map.of("service", "smp"));

        assertEquals(UpdateKind.START, directory.submitted.getFirst().kind());
        assertEquals(List.of("smp"), directory.submitted.getFirst().services());
    }

    @Test
    void updateStartWithNoServiceAsksForEverythingThatIsHeld() {
        // Unlike `down`, whose argument is required, an empty list here means every held service.
        final FakeUpdateDirectory directory = ask(UpdateCommands.START, Map.of());

        assertEquals(UpdateKind.START, directory.submitted.getFirst().kind());
        assertEquals(List.of(), directory.submitted.getFirst().services());
    }
}

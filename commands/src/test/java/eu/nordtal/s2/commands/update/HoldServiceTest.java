package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.FakeUser;
import eu.nordtal.s2.common.update.RunRefused;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.update.UpdateKind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code /update down <service>} and {@code /update start [service]} (season-2-ops/125).
 *
 * <p>What is worth asserting here is the scope and nothing else. Everything that happens after the
 * row is written belongs to steward-worker, and everything before it is the adapter's; what this
 * command decides is which kind and which services - and the difference between naming a service
 * and naming none is, on the stopping side, the difference between one server and the network.</p>
 */
class HoldServiceTest {

    private static FakeUpdateDirectory ask(final eu.nordtal.s2.commands.Declaration declaration,
                                           final Map<String, Object> arguments) {
        final FakeUpdateDirectory directory = new FakeUpdateDirectory();
        new HoldService(declaration).run(FakeUser.console(),
                new Values(declaration, arguments),
                new DirectoryUpdateEffects(directory, Runnable::run, (what, failure) -> { },
                        (id, user) -> { }));
        return directory;
    }

    @Test
    @DisplayName("/update down smp writes a DOWN for smp alone")
    void downCarriesItsService() {
        final FakeUpdateDirectory directory =
                ask(UpdateCommands.DOWN, Map.of("service", "smp"));

        assertEquals(1, directory.submitted.size());
        assertEquals(UpdateKind.DOWN, directory.submitted.getFirst().kind());
        assertEquals(List.of("smp"), directory.submitted.getFirst().services(),
                "the service was dropped on the way to the row, which makes this a DOWN with an"
                        + " empty scope - and an empty scope is the whole network");
    }

    @Test
    @DisplayName("a refused take-down says why, instead of blaming the database")
    void aRefusedRunIsNamedAsSuch() {
        final FakeUpdateDirectory directory = new FakeUpdateDirectory();
        directory.refusing = RunRefused.alreadyHeld(List.of("smp"));
        final FakeUser user = FakeUser.console();

        new HoldService(UpdateCommands.DOWN).run(user,
                new Values(UpdateCommands.DOWN, Map.of("service", "smp")),
                new DirectoryUpdateEffects(directory, Runnable::run, (what, failure) -> { }, (id, who) -> { }));

        assertEquals("update.already-down", user.only().key());
        assertEquals("smp", user.only().of("services"));
    }

    @Test
    @DisplayName("a run refused because another is open says so")
    void aBusyNetworkIsNamedAsSuch() {
        final FakeUpdateDirectory directory = new FakeUpdateDirectory();
        directory.refusing = RunRefused.runOpen(new eu.nordtal.s2.common.update.UpdateRequest(7L,
                UpdateKind.UPDATE, eu.nordtal.s2.common.update.UpdateStatus.RUNNING,
                eu.nordtal.s2.common.update.UpdateSource.DISCORD, "a", java.time.Instant.now(),
                java.time.Instant.now(), null, null, null));
        final FakeUser user = FakeUser.console();

        new RunUpdate(UpdateCommands.NOW).run(user, new Values(UpdateCommands.NOW, Map.of()),
                new DirectoryUpdateEffects(directory, Runnable::run, (what, failure) -> { }, (id, who) -> { }));

        assertEquals("update.busy", user.only().key());
    }

    @Test
    @DisplayName("/update start smp writes a START for smp alone")
    void startCarriesItsService() {
        final FakeUpdateDirectory directory =
                ask(UpdateCommands.START, Map.of("service", "smp"));

        assertEquals(UpdateKind.START, directory.submitted.getFirst().kind());
        assertEquals(List.of("smp"), directory.submitted.getFirst().services());
    }

    @Test
    @DisplayName("/update start with no service asks for everything that is held")
    void startWithoutAServiceIsEverythingHeld() {
        // The asymmetry with `down`, which the declaration enforces by requiring its argument.
        // Empty here means "every service somebody is holding down" - the recovery an operator
        // wants when they no longer remember which ones they stopped.
        final FakeUpdateDirectory directory = ask(UpdateCommands.START, Map.of());

        assertEquals(UpdateKind.START, directory.submitted.getFirst().kind());
        assertEquals(List.of(), directory.submitted.getFirst().services());
    }
}

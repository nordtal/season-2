package eu.nordtal.s2.commands.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every process registers {@code /update} itself, because nothing else will.
 *
 * It never travels, so a process that forgets has no {@code /update} and nothing fails or logs.
 */
class UpdateIsServedEverywhereTest {

    /** The four places a command tree is wired; {@code discord-bot} has no surface a declaration carries. */
    private static final List<String> ADAPTERS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/command/SmpCommand.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/command/HungerGamesCommand.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/command/LimboCommand.java",
            "proxy/src/main/templates/eu/nordtal/s2/proxy/ProxyPlugin.java");

    @Test
    void allFourProcessesWithASurfaceRegisterTheUpdateCommands() {
        for (final String file : ADAPTERS) {
            assertTrue(
                    read(file).contains("UpdateCommands.all()"),
                    file + " does not register /update. It is Target.LOCAL, so no inbox will run it"
                            + " for this process and no test but this one can notice: the command"
                            + " simply does not exist there, silently.");
        }
    }

    @Test
    void everyUpdateCommandIsLocalAdminOnlyAndConsoleOnly() {
        // Every admin command loses GAME and DISCORD, /update included.
        for (final Declaration declaration : UpdateCommands.declarations()) {
            assertEquals(
                    Target.LOCAL,
                    declaration.target(),
                    declaration.name() + " is not LOCAL. Giving /update a real target puts a second"
                            + " live process in front of the command somebody types because the"
                            + " network is already misbehaving.");
            assertTrue(declaration.adminOnly(), declaration.name() + " is not admin-only, and it takes servers away.");
            assertTrue(
                    declaration.surfaces().contains(Surface.CONSOLE),
                    declaration.name() + " lost the console, which must never happen.");
            assertFalse(
                    declaration.surfaces().contains(Surface.GAME), declaration.name() + " is still reachable in game.");
            assertFalse(
                    declaration.surfaces().contains(Surface.DISCORD),
                    declaration.name() + " is still reachable from Discord.");
        }
    }

    @Test
    void theFourThatStopServersAreConfirmedTheOtherThreeAreNot() {
        // /update down takes servers away; the other three take them away and give them back.
        assertEquals(
                List.of("/backup now", "/update down", "/update now", "/update restart"),
                UpdateCommands.declarations().stream()
                        .filter(Declaration::irreversible)
                        .map(Declaration::name)
                        .sorted()
                        .toList(),
                "taking four servers away from everybody on them is the plainest irreversible"
                        + " command in the network; a report and a cancel are neither. /backup now"
                        + " is in the list for exactly the same reason as the other two - it is the"
                        + " stopping that is irreversible, not the writing.");
    }

    @Test
    void everyKindThatStopsServersHasACommandThatAsksForIt() {
        // A kind that stops servers must be reachable from some command.
        final java.util.Set<eu.nordtal.s2.common.update.UpdateKind> asked = new java.util.HashSet<>();
        for (final eu.nordtal.s2.commands.NordtalCommand<eu.nordtal.s2.commands.update.UpdateEffects> command :
                UpdateCommands.all()) {
            final eu.nordtal.s2.common.update.UpdateKind kind = kindOf(command);
            if (kind != null) {
                asked.add(kind);
            }
        }
        for (final eu.nordtal.s2.common.update.UpdateKind kind : eu.nordtal.s2.common.update.UpdateKind.values()) {
            if (kind == eu.nordtal.s2.common.update.UpdateKind.APPLY) {
                // Retired and deliberately unreachable; see UpdateKind.APPLY.
                continue;
            }
            if (!kind.stopsServers()) {
                continue;
            }
            assertTrue(
                    asked.contains(kind),
                    "nothing can ask for " + kind + ". steward-worker would"
                            + " run it, the CHECK would accept it and no surface could write one.");
        }
    }

    /** Which kind a command submits, by running it against a directory that records rows. */
    private static eu.nordtal.s2.common.update.UpdateKind kindOf(
            final eu.nordtal.s2.commands.NordtalCommand<eu.nordtal.s2.commands.update.UpdateEffects> command) {
        final FakeUpdateDirectory directory = new FakeUpdateDirectory();
        command.run(
                eu.nordtal.s2.commands.FakeUser.console(),
                eu.nordtal.s2.commands.Values.none(command.declaration()),
                new DirectoryUpdateEffects(directory, Runnable::run, (what, failure) -> {}, (id, user) -> {}));
        return directory.submitted.isEmpty()
                ? null
                : directory.submitted.getFirst().kind();
    }

    private static String read(final String relative) {
        try {
            return Files.readString(repositoryRoot().resolve(relative), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no settings.gradle.kts above the working directory");
        }
        return candidate;
    }
}

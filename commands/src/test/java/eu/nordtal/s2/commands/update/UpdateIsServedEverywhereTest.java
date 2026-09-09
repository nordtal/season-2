package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every process registers {@code /update} itself, because nothing else will.
 *
 * <h2>What {@link Target#LOCAL} costs, and why it needs a test</h2>
 * Every other command in the network is served by exactly one process and reached from the others
 * through a {@code command_request} row - so forgetting to register one <em>somewhere</em> is
 * impossible: the inbox that owns it is the only place it can run. {@code /update} is the exception
 * by design. Its effect is a row in a table all five processes already have a pool for, so it never
 * travels; and the price of never travelling is that <b>each adapter has to opt in by hand</b>.
 *
 * <p>A process that forgets simply has no {@code /update}. Nothing fails, nothing is logged, and the
 * way it is found is an admin typing it on the one server where it is missing - which, for a command
 * whose whole reason to be local is "the network is misbehaving", is the worst possible moment. That
 * is finding 139's shape: both halves present, nothing joining them.</p>
 */
class UpdateIsServedEverywhereTest {

    /** The five places a command tree is wired, and the one that can forget. */
    private static final List<String> ADAPTERS = List.of(
            "smp/src/main/java/eu/nordtal/s2/smp/command/SmpCommand.java",
            "hunger-games/src/main/java/eu/nordtal/s2/hungergames/command/HungerGamesCommand.java",
            "limbo/src/main/java/eu/nordtal/s2/limbo/command/LimboCommand.java",
            "network-control/src/main/templates/eu/nordtal/s2/networkcontrol/NetworkControlPlugin.java",
            "discord-bot/src/main/java/eu/nordtal/s2/discordbot/AccessBot.java");

    @Test
    @DisplayName("all five processes register the update commands")
    void nobodyForgets() {
        for (final String file : ADAPTERS) {
            assertTrue(read(file).contains("UpdateCommands.all()"),
                    file + " does not register /update. It is Target.LOCAL, so no inbox will run it"
                            + " for this process and no test but this one can notice: the command"
                            + " simply does not exist there, silently.");
        }
    }

    @Test
    @DisplayName("every update command is LOCAL, admin-only, and on both surfaces")
    void theDeclarationsSayWhatTheyAre() {
        for (final Declaration declaration : UpdateCommands.declarations()) {
            assertEquals(Target.LOCAL, declaration.target(),
                    declaration.name() + " is not LOCAL. Giving /update a real target puts a second"
                            + " live process in front of the command somebody types because the"
                            + " network is already misbehaving.");
            assertTrue(declaration.adminOnly(),
                    declaration.name() + " is not admin-only, and it takes servers away.");
            assertTrue(declaration.surfaces().contains(Surface.GAME)
                            && declaration.surfaces().contains(Surface.DISCORD),
                    declaration.name() + " has to be on both surfaces - being able to update from"
                            + " Discord alone is how a network with a broken bot becomes one nobody"
                            + " can update.");
        }
    }

    @Test
    @DisplayName("the three that stop servers are confirmed, the other two are not")
    void whatIsIrreversible() {
        assertEquals(List.of("/backup now", "/update now", "/update restart"),
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
    @DisplayName("every kind that stops servers has a command that asks for it")
    void everyStoppingKindIsReachable() {
        // The gap this closes has happened once already, on the other side: the head start was
        // configured, migrated and documented on 2026-09-01 and had no READER at all until
        // 2026-09-07. A kind in the enum, in the CHECK and in the updater's switch, with nothing
        // anywhere able to write one, is the same shape - and it looks exactly like a feature.
        final java.util.Set<eu.nordtal.s2.common.update.UpdateKind> asked =
                new java.util.HashSet<>();
        for (final eu.nordtal.s2.commands.NordtalCommand<eu.nordtal.s2.commands.update.UpdateEffects>
                command : UpdateCommands.all()) {
            final eu.nordtal.s2.common.update.UpdateKind kind = kindOf(command);
            if (kind != null) {
                asked.add(kind);
            }
        }
        for (final eu.nordtal.s2.common.update.UpdateKind kind
                : eu.nordtal.s2.common.update.UpdateKind.values()) {
            if (kind == eu.nordtal.s2.common.update.UpdateKind.APPLY) {
                // Retired 2026-09-07 and deliberately unreachable - see UpdateKind.APPLY. Named
                // here rather than skipped by a general rule, so that putting it back is a visible
                // edit to this test.
                continue;
            }
            if (!kind.stopsServers()) {
                continue;
            }
            assertTrue(asked.contains(kind), "nothing can ask for " + kind + ". The updater would"
                    + " run it, the CHECK would accept it and no surface could write one.");
        }
    }

    /** Which kind a command submits, by running it against a directory that records rows. */
    private static eu.nordtal.s2.common.update.UpdateKind kindOf(
            final eu.nordtal.s2.commands.NordtalCommand<eu.nordtal.s2.commands.update.UpdateEffects>
                    command) {
        final FakeUpdateDirectory directory = new FakeUpdateDirectory();
        command.run(eu.nordtal.s2.commands.FakeUser.console(),
                eu.nordtal.s2.commands.Values.none(command.declaration()),
                new DirectoryUpdateEffects(directory, Runnable::run, (what, failure) -> { },
                        (id, user) -> { }));
        return directory.submitted.isEmpty() ? null : directory.submitted.getFirst().kind();
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

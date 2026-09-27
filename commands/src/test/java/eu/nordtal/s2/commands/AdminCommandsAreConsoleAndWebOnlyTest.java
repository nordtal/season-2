package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Holds every admin command off the surfaces a player or a Discord member reaches.
 *
 * This holds the catalogue only; the tree itself is held by the adapter tests in paper-common and proxy.
 */
class AdminCommandsAreConsoleAndWebOnlyTest {

    /**
     * Nothing in the catalogue is a Discord command.
     *
     * Unlike the test below, this also catches a non-admin declaration, which no adapter would register.
     */
    @Test
    void nothingIsDeclaredOnDiscord() {
        final List<String> onDiscord = Catalogue.all().stream()
                .filter(declaration -> declaration.surfaces().contains(Surface.DISCORD))
                .map(Declaration::name)
                .toList();
        assertTrue(
                onDiscord.isEmpty(),
                "the Discord adapter was deleted, so a declaration on Surface.DISCORD registers"
                        + " nothing at all and is a surface that only looks reachable: " + onDiscord);
    }

    /**
     * Admin declarations allowed to carry {@link Surface#GAME} or {@link Surface#DISCORD}; each entry needs a reason.
     */
    private static final Set<String> EXCEPTIONS = Set.of();

    @Test
    void adminCommandsStayOffGameAndDiscord() {
        final List<String> violations = new ArrayList<>();
        for (final Declaration declaration : Catalogue.all()) {
            if (!declaration.adminOnly()) {
                continue;
            }
            final String name = declaration.name();
            if (EXCEPTIONS.contains(name)) {
                continue;
            }
            final List<Surface> stray = List.of(Surface.GAME, Surface.DISCORD).stream()
                    .filter(declaration.surfaces()::contains)
                    .toList();
            if (!stray.isEmpty()) {
                violations.add(name + " carries " + stray);
            }
        }
        assertTrue(
                violations.isEmpty(),
                "these admin commands are still reachable from the game or from Discord:\n  "
                        + String.join("\n  ", violations));
    }

    /** Admin declarations that never carried {@link Surface#CONSOLE}, so lacking it takes nothing away. */
    private static final Set<String> NEVER_HAD_CONSOLE = Set.of(
            // AnnounceCommands.ANNOUNCE is never typed by a human: the SMP writes it at a milestone.
            "/announce");

    @Test
    void consoleIsNeverTakenAway() {
        final List<String> lost = new ArrayList<>();
        for (final Declaration declaration : Catalogue.all()) {
            if (!declaration.adminOnly()
                    || EXCEPTIONS.contains(declaration.name())
                    || NEVER_HAD_CONSOLE.contains(declaration.name())) {
                continue;
            }
            if (!declaration.surfaces().contains(Surface.CONSOLE)) {
                lost.add(declaration.name());
            }
        }
        assertTrue(
                lost.isEmpty(),
                "these admin commands lost Surface.CONSOLE, the one surface that must never be"
                        + " lost - without it they are reachable from nowhere a human sits:\n  "
                        + String.join("\n  ", lost));
    }
}

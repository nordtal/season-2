package eu.nordtal.s2.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every admin command, off both surfaces a player or a Discord member reaches, with no undecided ones.
 *
 * This reads the catalogue rather than each module's own test, because it is the one test that
 * can see all ten command files at once, the same reason {@link Catalogue} itself exists: a claim
 * about the whole set can only be checked by holding the whole set.
 *
 * {@link PhaseCommands}'s four admin declarations ({@code show}, {@code set}, {@code launch},
 * {@code smp-start}) are deliberately out of this test's scope; see {@link #EXCEPTIONS} for why.
 *
 * A missing {@link Surface#GAME} or {@link Surface#DISCORD} is read where the Brigadier tree is
 * built ({@code PaperCommands#gate}, {@code VelocityCommands#gate}), so a declaration decides
 * whether the command exists for a player at all - the set asserted here is the registration
 * itself, not a description of a later refusal. {@link #consoleIsNeverTakenAway()} follows from
 * the same fact: a declaration that lost {@link Surface#CONSOLE} is reachable from nowhere a
 * human sits.
 *
 * What this file cannot see is the tree itself - it holds the catalogue and no adapter. The other
 * half is held where a tree can be built: {@code AdminCommandsAreGoneFromTheGameTest}
 * (paper-common) and {@code VelocityCommandsGameSurfaceTest} (proxy), both of which ask a
 * built node's {@code requires} for an admin player and for the console.
 */
class AdminCommandsAreConsoleAndWebOnlyTest {

    /**
     * Nothing in the catalogue is a Discord command.
     *
     * Not the same assertion as the one below it: that one is about admin commands, and this is
     * about the surface itself, so a non-admin declaration carrying {@link Surface#DISCORD} would
     * still be caught.
     *
     * Adding {@link Surface#DISCORD} back to a declaration would not fail to compile and would not
     * fail at runtime; it would simply register nothing, because no adapter reads it.
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
     * Every admin declaration allowed to carry {@link Surface#GAME} or {@link Surface#DISCORD}, and why.
     *
     * A new entry means editing a list a person has to read.
     *
     * Empty: {@code /phase show}, {@code /phase set}, {@code /phase launch} and
     * {@code /phase smp-start} are declared on GAME and DISCORD and deliberately not on CONSOLE, so
     * the admin-only cut would have left them with no surface at all, which {@link Declaration}'s
     * constructor refuses outright. The owner's answer was CONSOLE and WEB instead, so
     * {@code PhaseCommands} moved and this set stays empty.
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

    /**
     * Admin declarations that never carried {@link Surface#CONSOLE}.
     *
     * Their absence below is not this cut taking anything away.
     */
    private static final Set<String> NEVER_HAD_CONSOLE = Set.of(
            // AnnounceCommands.ANNOUNCE is never typed by a human: the SMP writes it at a milestone.
            "/announce");

    @Test
    void consoleIsNeverTakenAway() {
        // PhaseCommands's four are excluded here too: they never had CONSOLE to begin with, see EXCEPTIONS above.
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

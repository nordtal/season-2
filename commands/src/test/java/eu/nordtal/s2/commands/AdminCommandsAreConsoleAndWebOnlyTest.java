package eu.nordtal.s2.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "alles Admin nur noch Konsole und Web" (owner, season-2-ops/18) - every admin command, off both
 * surfaces a player or a Discord member reaches, with no undecided ones.
 *
 * <h2>Why this reads the catalogue and not each module's own test</h2>
 * Every {@code *CommandsTest} that used to assert "on every surface" for its own module was proof
 * that a change to <em>that</em> file would be seen - not proof that nothing else in the network
 * still carried {@link Surface#GAME} or {@link Surface#DISCORD} on an admin declaration. This is
 * the one test that can see all ten command files at once, the same reason {@link Catalogue}
 * itself exists: a claim about the whole set can only be checked by holding the whole set.
 *
 * <h2>Seen red before the cut, 2026-09-15</h2>
 * Run against the catalogue exactly as {@code season-2-ops/18} found it - before any of
 * {@code AccessCommands}, {@code HungerGamesCommands}, {@code LimboCommands},
 * {@code NetworkCommands}, {@code SmpCommands} or {@code UpdateCommands} were touched - this test
 * failed and named every admin declaration that still carried {@link Surface#GAME} or
 * {@link Surface#DISCORD}:
 *
 * <pre>
 * /access status carries [GAME, DISCORD]
 * /access grant carries [GAME]
 * /access revoke carries [GAME]
 * /access unlink carries [GAME]
 * /access settle carries [GAME]
 * /access reload carries [GAME, DISCORD]
 * /hg start carries [GAME, DISCORD]
 * /hg ready-status carries [GAME, DISCORD]
 * /hg reload carries [GAME, DISCORD]
 * /limbo reload carries [GAME, DISCORD]
 * /network reload carries [GAME, DISCORD]
 * /smp reload carries [GAME, DISCORD]
 * /smp farmreset now carries [GAME, DISCORD]
 * /smp objective complete carries [GAME, DISCORD]
 * /smp milestone unlock carries [GAME, DISCORD]
 * /smp aura carries [GAME, DISCORD]
 * /smp access carries [GAME, DISCORD]
 * /update check carries [GAME, DISCORD]
 * /update now carries [GAME, DISCORD]
 * /update restart carries [GAME, DISCORD]
 * /update cancel carries [GAME, DISCORD]
 * /backup now carries [GAME, DISCORD]
 * </pre>
 *
 * That is twenty-two declarations, not the seventeen rows the ticket's inventory table counted -
 * the table grouped several commands per row ("hg ready-status, hg reload" as one line,
 * "update cancel/check/now/restart" as one line, "smp access/aura/reload" as one line). The
 * <em>set</em> of commands matches the ticket exactly; only the row count differs, because the
 * ticket counts rows and this counts declarations. Worth a note back to whoever wrote the
 * inventory, not a reason to doubt the cut.
 *
 * <p>{@link PhaseCommands}'s four admin declarations ({@code show}, {@code set}, {@code launch},
 * {@code smp-start}) were <b>not</b> in that red output, and are not named above - see
 * {@link #EXCEPTIONS} for why they stay out of this test's scope entirely rather than being cut.</p>
 */
class AdminCommandsAreConsoleAndWebOnlyTest {

    /**
     * Every admin declaration allowed to still carry {@link Surface#GAME} or
     * {@link Surface#DISCORD}, and why - in the shape {@code GateTest} (steward-ui) uses for its own
     * named exception sets, so that a fifth entry means editing a list a person has to read.
     */
    private static final Set<String> EXCEPTIONS = Set.of(
            // PhaseCommands.SHOW / SET / LAUNCH / SMP_START. These four are declared on
            // GAME_AND_DISCORD and DELIBERATELY NOT on Surface.CONSOLE - the class's own javadoc
            // says so: "deliberately not the console, which would be a second notion of who may do
            // this on a proxy that already knows who is an admin". Applying ops/18's cut here would
            // leave all four with an EMPTY surface set, which Declaration's own constructor refuses
            // ("is declared on no surface, so nothing would register it") - so the cut cannot be
            // applied blindly, and applying it while also adding Surface.CONSOLE would reverse a
            // separate, deliberate decision this ticket never mentions and does not analyse.
            // NOT RESOLVED THIS SESSION - the two decisions conflict and only the owner can say
            // which one gives way. Left exactly as PhaseCommands already had it; flagged in the
            // session report rather than guessed at here.
            "/phase show", "/phase set", "/phase launch", "/phase smp-start");

    @Test
    @DisplayName("no admin command is reachable from the game or from Discord, except the four named")
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
        assertTrue(violations.isEmpty(),
                "\"alles Admin nur noch Konsole und Web\" (owner, season-2-ops/18): these admin"
                        + " commands are still reachable from the game or from Discord:\n  "
                        + String.join("\n  ", violations));
    }

    /**
     * Admin declarations that never carried {@link Surface#CONSOLE} in the first place, so their
     * absence below is not this cut taking anything away.
     */
    private static final Set<String> NEVER_HAD_CONSOLE = Set.of(
            // AnnounceCommands.ANNOUNCE: Set.of(Surface.SYSTEM, Surface.WEB), untouched by ops/18 -
            // it is never typed by a human at all. The SMP writes it by itself at a milestone
            // (Surface.SYSTEM) or an admin writes one by hand on the web (Surface.WEB); nobody has
            // ever run it from a console, and AdminCheck already lets `source = 'CONSOLE'` through
            // by definition for the case where one did. Confirmed by reading AnnounceCommands.java
            // directly - this is not a guess.
            "/announce");

    @Test
    @DisplayName("no admin command lost the console while losing game and Discord")
    void consoleIsNeverTakenAway() {
        // The cut removes GAME and DISCORD; it must never also remove CONSOLE as a side effect of
        // rewriting a Set.of(...) literal by hand. PhaseCommands's four are excluded here too: they
        // never had CONSOLE to begin with (see EXCEPTIONS above), so asserting its presence for them
        // would be asserting something ops/18 never established and PhaseCommands never claimed.
        final List<String> lost = new ArrayList<>();
        for (final Declaration declaration : Catalogue.all()) {
            if (!declaration.adminOnly() || EXCEPTIONS.contains(declaration.name())
                    || NEVER_HAD_CONSOLE.contains(declaration.name())) {
                continue;
            }
            if (!declaration.surfaces().contains(Surface.CONSOLE)) {
                lost.add(declaration.name());
            }
        }
        assertTrue(lost.isEmpty(),
                "these admin commands lost Surface.CONSOLE - STOP, this is the one surface that must"
                        + " never be lost:\n  " + String.join("\n  ", lost));
    }
}

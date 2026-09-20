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
 *
 * <h2>What this test holds after steward/106, 2026-09-17 - the question the ticket asked</h2>
 * Both assertions below are unchanged, word for word. What changed underneath them is what the
 * claim <em>buys</em>, and that is worth writing down rather than leaving the reader to assume this
 * file still means what it meant on 2026-09-15.
 *
 * <p>Until 2026-09-17 a missing {@link Surface#GAME} was read once, at the moment somebody typed
 * the command, and all it decided was <em>which sentence to refuse them with</em>. The command was
 * still registered, still tab-completed, still in the tree. steward/106 moved that same reading
 * forward to where the Brigadier tree is built ({@code PaperCommands#gate},
 * {@code VelocityCommands#gate}), so the declaration now decides whether the command
 * <b>exists</b> for a player at all. The set asserted here is therefore no longer a description of
 * a refusal - it is the registration itself.</p>
 *
 * <p>Two consequences, and both are the reason this file was answered rather than deleted:</p>
 * <ul>
 *   <li>{@link #adminCommandsStayOffGameAndDiscord()} is now the <em>cause</em> of twenty-two
 *       commands being absent from every player's client. Adding {@link Surface#GAME} back to one
 *       of them no longer softens a message; it puts the command back in the game.</li>
 *   <li>{@link #consoleIsNeverTakenAway()} became load-bearing rather than tidy. Before, a
 *       declaration that lost {@link Surface#CONSOLE} was still registered and would merely have
 *       answered {@code command.not-from-console}; now it is gated out of the player's tree by the
 *       surface check <em>and</em> refused at the console by its own, which leaves it reachable
 *       from nowhere a human sits. Its failure message says so.</li>
 * </ul>
 *
 * <p>What this file still cannot see is the tree itself - it holds the catalogue and no adapter. The
 * other half is held where a tree can be built: {@code AdminCommandsAreGoneFromTheGameTest}
 * (paper-common) and {@code VelocityCommandsGameSurfaceTest} (proxy), both of which ask a
 * built node's {@code requires} for an admin player and for the console.</p>
 */
class AdminCommandsAreConsoleAndWebOnlyTest {

    /**
     * Every admin declaration allowed to still carry {@link Surface#GAME} or
     * {@link Surface#DISCORD}, and why - in the shape {@code GateTest} (steward-ui) uses for its own
     * named exception sets, so that a fifth entry means editing a list a person has to read.
     */
    /**
     * Nothing in the catalogue is a Discord command any more (season-2-community/10).
     *
     * <p>Not the same assertion as the one below it: that one is about admin commands, and this is
     * about the surface itself. {@code /smp status} was the last declaration carrying it and it is
     * not an admin command, so it fell outside every rule this class held - and on its own it kept
     * 611 lines of JDA adapter alive in {@code discord-bot} for one read-only command that Steward
     * answers twice over.</p>
     *
     * <p><b>This is what makes the deletion stay deleted.</b> Adding {@link Surface#DISCORD} back
     * to a declaration would not fail to compile and would not fail at runtime; it would simply
     * register nothing, because the adapter that used to read it is gone. That is the failure mode
     * worth a test: a surface that is declared and silently unreachable. The enum value itself
     * stays until season-2-ops/157 takes it and {@link Surface#GAME} together.</p>
     */
    @Test
    @DisplayName("no declaration is a Discord command, because nothing turns one into one any more")
    void nothingIsDeclaredOnDiscord() {
        final List<String> onDiscord = Catalogue.all().stream()
                .filter(declaration -> declaration.surfaces().contains(Surface.DISCORD))
                .map(Declaration::name)
                .toList();
        assertTrue(onDiscord.isEmpty(),
                "the Discord adapter was deleted in season-2-community/10, so a declaration on"
                        + " Surface.DISCORD registers nothing at all and is a surface that only"
                        + " looks reachable: " + onDiscord);
    }

    /**
     * Empty, and the comment is here so that the next person to add an entry has to argue for it.
     *
     * <p>It held {@code /phase show}, {@code /phase set}, {@code /phase launch} and
     * {@code /phase smp-start} for one night. Those four were declared on GAME and DISCORD and
     * deliberately not on CONSOLE, so the cut from {@code season-2-ops/18} would have left them
     * with no surface at all, which {@link Declaration}'s constructor refuses outright. That is a
     * conflict between two deliberate decisions and not something a test should pick a side in, so
     * it was carried to the owner instead of guessed at.</p>
     *
     * <p>Till's answer, 2026-09-16: CONSOLE <b>and</b> WEB. The Season page in Steward is where a
     * person reads the phase, so it is where they set it, and a WEB row carries the asker's Discord
     * id where a CONSOLE row carries no identity at all. {@code PhaseCommands} moved; this set is
     * empty; the rule has no exceptions.</p>
     */
    private static final Set<String> EXCEPTIONS = Set.of();

    @Test
    @DisplayName("no admin command is reachable from the game or from Discord")
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
                        + " never be lost. Since steward/106 losing it is not an inconvenience: the"
                        + " adapters keep a command without Surface.GAME out of every player's tree,"
                        + " so one without CONSOLE as well can be reached from nowhere a human"
                        + " sits:\n  " + String.join("\n  ", lost));
    }
}

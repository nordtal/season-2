package eu.nordtal.s2.commands.phase;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.SeasonDates;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The four {@code /phase} subcommands, declared once for every surface.
 *
 * <p>The path is {@code ["phase", "show"]} on both surfaces, because Discord cannot invoke a command
 * that has subcommands. The proxy additionally runs it for a bare {@code /phase}, which is a
 * Brigadier tree detail rather than a second command.</p>
 */
public final class PhaseCommands {

    /** Where {@code /phase} runs: the proxy owns the row's readers, so it owns the command. */
    private static final Target TARGET = Target.PROXY;

    /**
     * The console and the web interface, and neither chat nor Discord.
     *
     * <h2>This used to be the exact opposite, and the reversal is the decision</h2>
     * It read {@code Set.of(GAME, DISCORD)} with a comment arguing that the console would be "a
     * second notion of who may do this on a proxy that already knows who is an admin". That
     * argument was sound on its own and lost to a larger one: {@code season-2-ops/18}, Till's own
     * words, <i>"alles Admin nur noch Konsole und Web"</i>. Every other admin command in the
     * repository moved; a single set of four that stayed reachable from chat would be the exception
     * nobody remembers, and {@code /phase launch} is the most consequential command in the project
     * - it starts the season.
     *
     * <p>{@link Surface#WEB} rather than {@link Surface#CONSOLE} alone, decided 2026-09-16: the
     * Season page in Steward is where a person already reads the phase, so it is where they should
     * be able to set it. A WEB row is pinned to the asker's Discord id by a CHECK in V18, so
     * "who launched the season" stays answerable - which a CONSOLE row, pinned by V11 to having no
     * identity at all, cannot answer for the one command where it matters most.</p>
     *
     * <p>The old comment's observation survives it and is worth keeping: anybody holding that
     * console can edit {@code season_phase} directly anyway. That is an argument about what a
     * console <em>can</em> do, not about where this command should be offered.</p>
     */
    private static final Set<Surface> CONSOLE_AND_WEB = Set.of(Surface.CONSOLE, Surface.WEB);

    /** {@code /phase show} - and the bare {@code /phase} on the proxy. Reads, changes nothing. */
    public static final Declaration SHOW =
            new Declaration(List.of("phase", "show"), TARGET, CONSOLE_AND_WEB, true, false, List.of());

    /** {@code /phase set <phase>} - irreversible, and the one that disconnects people. */
    public static final Declaration SET = new Declaration(
            List.of("phase", "set"),
            TARGET,
            CONSOLE_AND_WEB,
            true,
            true,
            List.of(Argument.choice("phase", phaseNames())));

    /**
     * {@code /phase launch <when>} - when the network opens.
     *
     * <p><b>Not</b> irreversible: setting the date again is an exact undo. It only drives the MOTD
     * countdown and the pre-opening disconnect screens, so a wrong value is visible at once and
     * costs one more command to fix.</p>
     */
    public static final Declaration LAUNCH =
            new Declaration(List.of("phase", "launch"), TARGET, CONSOLE_AND_WEB, true, false, List.of(whenArgument()));

    /**
     * {@code /phase smp-start <when>} - when paid access starts running.
     *
     * <p>Irreversible, unlike {@link #LAUNCH}: moving it shifts every access period that has not
     * started yet, and moving it back shifts them again against the clock rather than undoing the
     * first shift - a grant that started in between is no longer moved at all.</p>
     */
    public static final Declaration SMP_START = new Declaration(
            List.of("phase", "smp-start"), TARGET, CONSOLE_AND_WEB, true, true, List.of(whenArgument()));

    /** Every {@code /phase} command, in the order they read best in a help listing. */
    public static List<NordtalCommand<PhaseEffects>> all() {
        return List.of(new ShowPhase(), new SetPhase(), SetSeasonDate.launch(), SetSeasonDate.smpStart());
    }

    /** Every {@code /phase} declaration. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }

    private PhaseCommands() {}

    /** Greedy, not a word: a date and a time are separated by a space. */
    private static Argument whenArgument() {
        return Argument.greedy("when");
    }

    private static List<String> phaseNames() {
        return Arrays.stream(SeasonPhase.values()).map(Enum::name).toList();
    }

    /** The phase names, for the message that says which ones exist. */
    public static String names() {
        return Arrays.stream(SeasonPhase.values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    /** What {@code /phase launch} and {@code /phase smp-start} suggest for "take the date away". */
    public static String clearKeyword() {
        return SeasonDates.CLEAR;
    }
}

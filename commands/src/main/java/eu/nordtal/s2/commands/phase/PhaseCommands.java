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
     * Chat and Discord - deliberately <b>not</b> the console, which would be a second notion of who
     * may do this on a proxy that already knows who is an admin. Anybody with that console can edit
     * {@code season_phase} directly.
     */
    private static final Set<Surface> GAME_AND_DISCORD =
            Set.of(Surface.GAME, Surface.DISCORD);

    /** {@code /phase show} - and the bare {@code /phase} on the proxy. Reads, changes nothing. */
    public static final Declaration SHOW = new Declaration(
            List.of("phase", "show"), TARGET, GAME_AND_DISCORD, true, false, List.of());

    /** {@code /phase set <phase>} - irreversible, and the one that disconnects people. */
    public static final Declaration SET = new Declaration(
            List.of("phase", "set"), TARGET, GAME_AND_DISCORD, true, true,
            List.of(Argument.choice("phase", phaseNames())));

    /**
     * {@code /phase launch <when>} - when the network opens.
     *
     * <p><b>Not</b> irreversible: setting the date again is an exact undo. It only drives the MOTD
     * countdown and the pre-opening disconnect screens, so a wrong value is visible at once and
     * costs one more command to fix.</p>
     */
    public static final Declaration LAUNCH = new Declaration(
            List.of("phase", "launch"), TARGET, GAME_AND_DISCORD, true, false,
            List.of(whenArgument()));

    /**
     * {@code /phase smp-start <when>} - when paid access starts running.
     *
     * <p>Irreversible, unlike {@link #LAUNCH}: moving it shifts every access period that has not
     * started yet, and moving it back shifts them again against the clock rather than undoing the
     * first shift - a grant that started in between is no longer moved at all.</p>
     */
    public static final Declaration SMP_START = new Declaration(
            List.of("phase", "smp-start"), TARGET, GAME_AND_DISCORD, true, true,
            List.of(whenArgument()));

    /** Every {@code /phase} command, in the order they read best in a help listing. */
    public static List<NordtalCommand<PhaseEffects>> all() {
        return List.of(new ShowPhase(), new SetPhase(), SetSeasonDate.launch(),
                SetSeasonDate.smpStart());
    }

    /** Every {@code /phase} declaration. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }

    private PhaseCommands() {
    }

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

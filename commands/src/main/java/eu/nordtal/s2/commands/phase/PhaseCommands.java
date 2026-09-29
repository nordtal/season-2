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
 * The path is {@code ["phase", "show"]} because Discord cannot invoke a command that has subcommands.
 */
public final class PhaseCommands {

    /** Where {@code /phase} runs: the proxy owns the row's readers, so it owns the command. */
    private static final Target TARGET = Target.PROXY;

    /** Console and the Steward Season page, where a WEB row still records who launched the season. */
    private static final Set<Surface> CONSOLE_AND_WEB = Set.of(Surface.CONSOLE, Surface.WEB);

    /** {@code /phase show}, also the bare {@code /phase} on the proxy; changes nothing. */
    public static final Declaration SHOW =
            new Declaration(List.of("phase", "show"), TARGET, CONSOLE_AND_WEB, true, false, List.of());

    /** {@code /phase set <phase>}: irreversible, and the one that disconnects people. */
    public static final Declaration SET = new Declaration(
            List.of("phase", "set"),
            TARGET,
            CONSOLE_AND_WEB,
            true,
            true,
            List.of(Argument.choice("phase", phaseNames())));

    /**
     * {@code /phase launch <when>}: when the network opens.
     *
     * Not irreversible: setting the date again is an exact undo.
     */
    public static final Declaration LAUNCH =
            new Declaration(List.of("phase", "launch"), TARGET, CONSOLE_AND_WEB, true, false, List.of(whenArgument()));

    /**
     * {@code /phase smp-start <when>}: when paid access starts running.
     *
     * Irreversible: moving it back shifts unstarted periods again rather than undoing the first shift.
     */
    public static final Declaration SMP_START = new Declaration(
            List.of("phase", "smp-start"), TARGET, CONSOLE_AND_WEB, true, true, List.of(whenArgument()));

    public static List<NordtalCommand<PhaseEffects>> all() {
        return List.of(new ShowPhase(), new SetPhase(), SetSeasonDate.launch(), SetSeasonDate.smpStart());
    }

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

    /** Returns the phase names, for the message that says which ones exist. */
    public static String names() {
        return Arrays.stream(SeasonPhase.values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    /** Returns what {@code /phase launch} and {@code /phase smp-start} suggest for "take the date away". */
    public static String clearKeyword() {
        return SeasonDates.CLEAR;
    }
}

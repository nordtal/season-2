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
 * The four {@code /phase} subcommands, declared once.
 *
 * <h2>This is the module's proof, and it was chosen for that</h2>
 * {@code /phase} is the only command in the network that existed on <b>two</b> surfaces before
 * {@code :commands} did - a Brigadier command on the proxy and a JDA command in the bot, written
 * separately, three days apart. So it is the only one on which "does this layer make one
 * implementation out of two, or a third one standing next to them?" can actually be answered rather
 * than assumed. Settled with the owner on 2026-09-04.
 *
 * <p>What the two used to disagree about, all of which is now decided in one place:</p>
 * <ul>
 *   <li>The bot's replies were <b>hardcoded English</b> - "You are not an admin.", "That is not a
 *       season phase." - while the proxy rendered message keys against the asker's database locale.
 *       docs/architecture.md#commands calls a hardcoded English string a bug rather than a
 *       shortcut, and the bot had eleven of them in this command alone.</li>
 *   <li>The bot confirmed a switch with a button; the proxy switched immediately. The phase decides
 *       who may join, and a switch to {@code SMP} disconnects everybody without active access - so
 *       the surface that skipped the confirmation was the one where it mattered most.</li>
 *   <li>Only the bot answered {@code show} with all three values; the proxy read the opening from
 *       its cache and {@code smp_start} from the database, in two messages arriving in either
 *       order.</li>
 * </ul>
 *
 * <h2>The bare {@code /phase} is an adapter's business</h2>
 * The path here is {@code ["phase", "show"]} on both surfaces, because Discord cannot invoke a
 * command that has subcommands on its own. The proxy additionally executes it for a bare
 * {@code /phase}, which is a Brigadier tree detail and not a second command; the name a person
 * types is the same in both places, which is what {@link Declaration#path()} exists to guarantee.
 */
public final class PhaseCommands {

    /** Where {@code /phase} runs: the proxy owns the row's readers, so it owns the command. */
    private static final Target TARGET = Target.PROXY;

    /**
     * Chat and Discord - and deliberately <b>not</b> the console.
     *
     * <p>That is not an oversight and it is not this module's decision to take. docs/season-phases.md
     * rejected the console for {@code /phase} on 2026-08-31, with a reason that has not moved: it
     * would be "a second, different notion of who may do this on a proxy that already knows exactly
     * who is an admin". The proxy's console is the shell of a container; anybody who has it can edit
     * {@code season_phase} directly, which is what that document already names as the last resort.
     *
     * <p>{@code /hg} is the opposite case and was decided the other way on 2026-09-04 - the console
     * had to gain it, because every {@code /hg} handler cast its sender to a player and the start of
     * the event therefore hung on one client being able to connect. Two commands, two answers; the
     * set is per declaration so that both can be true.</p>
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
     * <p><b>Not</b> marked irreversible, and the difference from its twin below is the whole reason
     * the flag is per command rather than per category. Setting this date again is an exact undo:
     * it drives the MOTD countdown and the three pre-opening disconnect screens, so a wrong value is
     * visible to everybody within seconds and costs one more command to fix. A flag set on
     * everything that writes would train an admin to type every command twice, which is how a
     * confirmation stops being read.</p>
     */
    public static final Declaration LAUNCH = new Declaration(
            List.of("phase", "launch"), TARGET, GAME_AND_DISCORD, true, false,
            List.of(whenArgument()));

    /**
     * {@code /phase smp-start <when>} - when paid access starts running.
     *
     * <p>Irreversible in the sense the flag means, unlike {@link #LAUNCH}: moving it shifts every
     * access period that has not started yet, across every account, and moving it back does not undo
     * the shift row by row - it shifts them again, against whatever the clock says by then. A grant
     * that started in between is no longer moved at all. {@code PhaseDirectoryIntegrationTest} owns
     * that arithmetic, and it is other people's paid time.</p>
     */
    public static final Declaration SMP_START = new Declaration(
            List.of("phase", "smp-start"), TARGET, GAME_AND_DISCORD, true, true,
            List.of(whenArgument()));

    /**
     * Every {@code /phase} command, in the order they read best in a help listing.
     *
     * <p>New instances each call, which is what the two callers want: the proxy's tree and the
     * bot's registry are separate surfaces and share no state. Nothing here is stateful anyway -
     * the confirmation window belongs to the adapter.</p>
     */
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

    /**
     * A date is greedy, not a word.
     *
     * <p>Brigadier would otherwise hand over {@code "2026-10-01"} and call {@code "18:00"} an
     * unexpected second argument - which parses, and fails at the moment somebody types a real
     * date.</p>
     */
    private static Argument whenArgument() {
        return Argument.greedy("when");
    }

    private static List<String> phaseNames() {
        return Arrays.stream(SeasonPhase.values()).map(Enum::name).toList();
    }

    /** The five names, for the message that says which ones exist. */
    public static String names() {
        return Arrays.stream(SeasonPhase.values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    /** What {@code /phase launch} and {@code /phase smp-start} suggest for "take the date away". */
    public static String clearKeyword() {
        return SeasonDates.CLEAR;
    }
}

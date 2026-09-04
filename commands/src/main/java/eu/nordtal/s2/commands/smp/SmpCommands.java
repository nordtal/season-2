package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;

import java.util.List;
import java.util.Set;

/**
 * {@code /smp} - the escape hatches, declared once and adapted per surface.
 *
 * <h2>All six are on both platforms, and that is the change</h2>
 * Until 2026-09-05 every one of these existed only as chat on the SMP server, so an admin who was
 * not able to connect - which includes the case where the SMP itself is why they cannot - had no way
 * to reach any of them. That is the same gap {@code /hg} had, and the same one {@code /phase}
 * already did not have.
 *
 * <h2>What is deliberately NOT here</h2>
 * {@code /smp navigate} opens an inventory and {@code /smp poi add} reads the caller's position;
 * both are chat commands about being somewhere, and a Discord half of them would be a different
 * command wearing the same name. {@code /smp update} already travels, through
 * {@code update_request}, and is not folded in: it is answered by a container that is not a command
 * target and its report is text that must not be rendered twice.
 *
 * <h2>Which three ask first</h2>
 * {@code farmreset}, {@code objective complete} and {@code milestone unlock}. {@code aura} does not,
 * because applying the negative is an exact undo, and {@code reload} does not, because re-reading a
 * file changes nothing that was not already on disk. A flag on everything that writes is a flag
 * nobody reads.
 */
public final class SmpCommands {

    private SmpCommands() {
    }

    private static final Set<Surface> EVERYWHERE =
            Set.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE);

    /** {@code /smp reload} - the sounds, the milestone track and the message bundles. */
    public static final Declaration RELOAD = new Declaration(
            List.of("smp", "reload"), Target.SMP, EVERYWHERE, true, false, List.of());

    /**
     * {@code /smp farmreset now} - deletes the farm world folder and regenerates it.
     *
     * <p>The literal {@code now} is kept from the chat command it was. It reads as a deliberate act
     * in a way {@code /smp farmreset} does not, and the confirmation is keyed on the whole line, so
     * dropping it would also silently invalidate every pending confirmation.</p>
     */
    public static final Declaration FARM_RESET = new Declaration(
            List.of("smp", "farmreset", "now"), Target.SMP, EVERYWHERE, true, true, List.of());

    /** {@code /smp objective complete <key>} - closes one objective, paying out what was collected. */
    public static final Declaration COMPLETE_OBJECTIVE = new Declaration(
            List.of("smp", "objective", "complete"), Target.SMP, EVERYWHERE, true, true,
            List.of(Argument.word("key")));

    /** {@code /smp milestone unlock <key>} - unlocks a whole milestone by hand. */
    public static final Declaration UNLOCK_MILESTONE = new Declaration(
            List.of("smp", "milestone", "unlock"), Target.SMP, EVERYWHERE, true, true,
            List.of(Argument.word("key")));

    /**
     * {@code /smp aura <player> <delta>} - a correction, with its reason recorded.
     *
     * <p>The bounds are the ones the chat command already had. They are on the declaration rather
     * than in a handler so that Brigadier, JDA and the request row all refuse the same numbers -
     * the third of those being the one that matters, because a row can be written by an older
     * build.</p>
     */
    public static final Declaration AURA = new Declaration(
            List.of("smp", "aura"), Target.SMP, EVERYWHERE, true, false,
            List.of(Argument.player("player"), Argument.integer("delta", -10_000, 10_000)));

    /** {@code /smp access <player>} - is this person linked, do they have access, are they paying? */
    public static final Declaration ACCESS = new Declaration(
            List.of("smp", "access"), Target.SMP, EVERYWHERE, true, false,
            List.of(Argument.player("player")));

    /** Every {@code /smp} command, for an adapter to register and for the catalogue. */
    public static List<NordtalCommand<SmpEffects>> all() {
        return List.of(new ReloadSmp(), new ResetFarmWorld(), new CompleteObjective(),
                new UnlockMilestone(), new ChangeAura(), new ShowAccess());
    }

    /** Every {@code /smp} declaration. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

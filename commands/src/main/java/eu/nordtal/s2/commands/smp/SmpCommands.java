package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import java.util.List;
import java.util.Set;

/**
 * {@code /smp}: the admin's escape hatches, declared once and adapted per surface.
 *
 * Commands about where the caller stands, and {@code /smp update} with its own row, are not declared here.
 */
public final class SmpCommands {

    private SmpCommands() {}

    /** {@code reload}, {@code aura} and {@code access}: console only. */
    private static final Set<Surface> CONSOLE_ONLY = Set.of(Surface.CONSOLE);

    /** The three decided at a desk, which get a button in Steward. */
    private static final Set<Surface> CONSOLE_AND_WEB = Set.of(Surface.CONSOLE, Surface.WEB);

    /** {@code /smp reload}: the sounds, the milestone track and the message bundles. */
    public static final Declaration RELOAD =
            new Declaration(List.of("smp", "reload"), Target.SMP, CONSOLE_ONLY, true, false, List.of());

    /** {@code /smp objective complete <key>}: closes one objective, paying out what was collected. */
    public static final Declaration COMPLETE_OBJECTIVE = new Declaration(
            List.of("smp", "objective", "complete"),
            Target.SMP,
            CONSOLE_AND_WEB,
            true,
            true,
            List.of(Argument.word("key")));

    /** {@code /smp milestone unlock <key>}: unlocks a whole milestone by hand. */
    public static final Declaration UNLOCK_MILESTONE = new Declaration(
            List.of("smp", "milestone", "unlock"),
            Target.SMP,
            CONSOLE_AND_WEB,
            true,
            true,
            List.of(Argument.word("key")));

    /**
     * {@code /smp aura <player> <delta>}: a correction, with its reason recorded.
     *
     * Bounded on the declaration, so Brigadier, JDA and a row written by an older build refuse the same numbers.
     */
    public static final Declaration AURA = new Declaration(
            List.of("smp", "aura"),
            Target.SMP,
            CONSOLE_ONLY,
            true,
            false,
            List.of(Argument.player("player"), Argument.integer("delta", -10_000, 10_000)));

    /** {@code /smp access <player>}: whether this person is linked, has access and is paying. */
    public static final Declaration ACCESS = new Declaration(
            List.of("smp", "access"), Target.SMP, CONSOLE_ONLY, true, false, List.of(Argument.player("player")));

    public static List<NordtalCommand<SmpEffects>> all() {
        return List.of(
                new ReloadSmp(), new CompleteObjective(), new UnlockMilestone(), new ChangeAura(), new ShowAccess());
    }

    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

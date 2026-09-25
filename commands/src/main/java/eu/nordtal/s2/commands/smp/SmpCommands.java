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
 * <p>{@code /aura} and {@code /smp status} left on 2026-09-25, the same way the proxy's player
 * commands did: they are what a player types, on one server, with no argument that ever travels
 * through a row, so they are plain Paper Brigadier in the {@code smp} plugin. What is left here is
 * the admin's.</p>
 *
 * <h2>Which two ask first</h2>
 * {@code objective complete} and {@code milestone unlock}. {@code aura} does not, because applying
 * the negative is an exact undo, and {@code reload} does not, because re-reading a file changes
 * nothing that was not already on disk. A flag on everything that writes is a flag nobody reads.
 *
 * <p>{@code farmreset} was the third and the reason the flag exists at all - it deleted a world a
 * player could be standing in. The farm world went on 2026-09-20 (season-2-ingame/30) and nothing
 * took its place; resources come out of the SMP world now.</p>
 */
public final class SmpCommands {

    private SmpCommands() {
    }

    /**
     * {@code reload}, {@code aura} and {@code access}: console only, 2026-09-15 (ops/18).
     *
     * <p>"alles Admin nur noch Konsole und Web" took {@link Surface#GAME} and {@link
     * Surface#DISCORD} off every admin command. These three have no button in Steward either -
     * see the note on {@link #CONSOLE_AND_WEB} for the three that do - so console is the only
     * surface left standing for them.</p>
     */
    private static final Set<Surface> CONSOLE_ONLY = Set.of(Surface.CONSOLE);

    /**
     * The three that Till decided (2026-09-12) get a button in Steward - console and web only
     * since 2026-09-15 (ops/18) took game and Discord off every admin command, this trio included.
     *
     * <p>Not every admin command does: {@code /smp aura} and {@code /smp access} take a player who
     * is standing in front of you, and a web form asking for a name somebody has to type correctly
     * is worse than the chat command that completes it. These three are the ones that are decided
     * at a desk.</p>
     */
    private static final Set<Surface> CONSOLE_AND_WEB = Set.of(Surface.CONSOLE, Surface.WEB);

    /** {@code /smp reload} - the sounds, the milestone track and the message bundles. */
    public static final Declaration RELOAD = new Declaration(
            List.of("smp", "reload"), Target.SMP, CONSOLE_ONLY, true, false, List.of());

    /** {@code /smp objective complete <key>} - closes one objective, paying out what was collected. */
    public static final Declaration COMPLETE_OBJECTIVE = new Declaration(
            List.of("smp", "objective", "complete"), Target.SMP, CONSOLE_AND_WEB, true, true,
            List.of(Argument.word("key")));

    /** {@code /smp milestone unlock <key>} - unlocks a whole milestone by hand. */
    public static final Declaration UNLOCK_MILESTONE = new Declaration(
            List.of("smp", "milestone", "unlock"), Target.SMP, CONSOLE_AND_WEB, true, true,
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
            List.of("smp", "aura"), Target.SMP, CONSOLE_ONLY, true, false,
            List.of(Argument.player("player"), Argument.integer("delta", -10_000, 10_000)));

    /** {@code /smp access <player>} - is this person linked, do they have access, are they paying? */
    public static final Declaration ACCESS = new Declaration(
            List.of("smp", "access"), Target.SMP, CONSOLE_ONLY, true, false,
            List.of(Argument.player("player")));

    /** Every {@code /smp} command, for an adapter to register and for the catalogue. */
    public static List<NordtalCommand<SmpEffects>> all() {
        return List.of(new ReloadSmp(), new CompleteObjective(), new UnlockMilestone(),
                new ChangeAura(), new ShowAccess());
    }

    /** Every {@code /smp} declaration. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;

import java.util.List;
import java.util.Set;

/**
 * {@code /update} - what is new, and the one run that installs it.
 *
 * <h2>Two commands, because there are two amounts of damage</h2>
 * {@code /update} reports and changes nothing. {@code /update now} is the confirmation, and behind
 * it is the whole sequence: a countdown every player sees, the servers whose jars change stopped,
 * the schema and the jars moved with nothing running on them, and each server started again and
 * watched until it reports healthy.
 *
 * <p>There is no third command, and there used to be. {@code install} swapped jars into running
 * servers and is finding 147; it was deleted rather than repaired, because the button <em>was</em>
 * the defect. {@code /update restart} is what remains of it in spirit - the same sequence with
 * nothing installed, for "that server is wedged, take it round once".</p>
 *
 * <h2>Why {@link Target#LOCAL}</h2>
 * Because the effect is a row in {@code update_request}, and every process already has that pool.
 * Addressing it at the bot would put two hops and a second live process in front of the command
 * somebody types <em>because</em> the network is misbehaving. See {@code Target.LOCAL}.
 */
public final class UpdateCommands {

    private UpdateCommands() {
    }

    /**
     * Every {@code /update} command and {@code /backup now}: console only, 2026-09-15 (ops/18).
     *
     * <p>"alles Admin nur noch Konsole und Web" (owner) took {@link Surface#GAME} and {@link
     * Surface#DISCORD} off every admin command, and being able to update from Discord or in game
     * alone is how a network with a broken bot or a broken server becomes one nobody can update from
     * anywhere but a shell - the opposite of what those two surfaces were meant to buy. Console
     * remains, which is the one surface that does not depend on the thing being updated.
     * See {@code season-2-ops/10} and {@code season-2-community/01}, both rewritten in the same
     * change because they assumed the surfaces this constant used to carry.</p>
     */
    private static final Set<Surface> CONSOLE_ONLY = Set.of(Surface.CONSOLE);

    /**
     * {@code /update check} - resolve every source, compare, report. Writes no file.
     *
     * <h2>Why a subcommand, when the bare {@code /update} reads better</h2>
     * Because Discord cannot run a root that has subcommands: a slash command with {@code now},
     * {@code restart} and {@code cancel} under it is a menu, and a menu is not invokable. The
     * report was declared as the bare root on 2026-09-08 and was unreachable in Discord from that
     * moment - the adapter registered it without complaint and Discord never offered it. In game
     * the bare {@code /update} still works: {@code Catalogue#rootDefault} names this command as
     * what a root with nothing of its own runs, the way {@code /phase} is {@code /phase show}.
     */
    public static final Declaration REPORT = new Declaration(
            List.of("update", "check"), Target.LOCAL,
            CONSOLE_ONLY, true, false, List.of());

    /**
     * {@code /update now} - the whole sequence, under one confirmation.
     *
     * <p>Irreversible, and it is the plainest case of it in the network: it takes servers away from
     * everybody who is on them. The countdown is a second chance and not the first one - the
     * confirmation comes before the countdown even starts.</p>
     */
    public static final Declaration NOW = new Declaration(
            List.of("update", "now"), Target.LOCAL,
            CONSOLE_ONLY, true, true, List.of());

    /** {@code /update restart} - the same sequence with nothing installed. */
    public static final Declaration RESTART = new Declaration(
            List.of("update", "restart"), Target.LOCAL,
            CONSOLE_ONLY, true, true, List.of());

    /** {@code /update cancel} - stop the countdown, for as long as one is running. */
    public static final Declaration CANCEL = new Declaration(
            List.of("update", "cancel"), Target.LOCAL,
            CONSOLE_ONLY, true, false, List.of());

    /**
     * {@code /backup now} - the same sequence with a volume backup in the gap.
     *
     * <h2>A different root, declared in this file</h2>
     * The root is {@code backup} and not {@code update}, because it is asked for by somebody who is
     * not updating anything - usually right before a change they are unsure of - and because
     * {@code /update now}'s neighbours are the one set of tab-completions in this network worth
     * keeping thin.
     *
     * <p>The <em>declaration</em> is here rather than in a root class of its own for the opposite
     * reason: it writes the same row into the same table, is drawn by the same
     * {@link UpdateFollower}, and is carried out by the same container. Being in {@link #all()} is
     * what puts it into all five processes without a sixth wiring site somebody has to remember -
     * and {@code Target.LOCAL} is exactly the target where forgetting is silent: nothing travels,
     * so nothing times out, so a process that forgot simply has no {@code /backup}.</p>
     *
     * <p>It is cancelled by {@code /update cancel} like every other countdown, which is the one
     * place the shared root shows through. That is deliberate: there is one countdown in this
     * network and one thing that stops it.</p>
     */
    public static final Declaration BACKUP = new Declaration(
            List.of("backup", "now"), Target.LOCAL,
            CONSOLE_ONLY, true, true, List.of());

    /**
     * {@code /update down <service>} - stop it and leave it stopped (season-2-ops/125).
     *
     * <h2>The service is required, and that is the whole safety of it</h2>
     * An unnamed scope is the whole network everywhere else in this mechanism. Here that would be
     * every server held down until somebody presses a button, which is not something anybody asks
     * for by forgetting a word - so the argument is required and the declaration says so, rather
     * than the command guessing.
     *
     * <p>Irreversible for the same reason {@link #NOW} is: it takes a server away from everybody on
     * it. More so, in fact - this one does not bring it back.</p>
     */
    public static final Declaration DOWN = new Declaration(
            List.of("update", "down"), Target.LOCAL,
            CONSOLE_ONLY, true, true, List.of(Argument.word("service")));

    /**
     * {@code /update start [service]} - take the hold off and start it again.
     *
     * <p>Optional where {@link #DOWN} is required, and not confirmed where {@link #DOWN} is. Both
     * follow from the same reading: nothing here stops anything. Typing it with no service starts
     * everything being held, which is the recovery somebody wants after this process has been
     * restarted and they no longer remember which ones they stopped.</p>
     */
    public static final Declaration START = new Declaration(
            List.of("update", "start"), Target.LOCAL,
            CONSOLE_ONLY, true, false, List.of(Argument.word("service").optional()));

    /** Every command served by {@link UpdateEffects} - the six {@code /update} ones and backup. */
    public static List<NordtalCommand<UpdateEffects>> all() {
        return List.of(new ReportUpdate(), new RunUpdate(UpdateCommands.NOW),
                new RunUpdate(UpdateCommands.RESTART), new CancelUpdate(), new RunBackup(),
                new HoldService(UpdateCommands.DOWN), new HoldService(UpdateCommands.START));
    }

    /** Every declaration in {@link #all()}. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

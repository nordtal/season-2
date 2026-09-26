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
 * Two commands, because there are two amounts of damage: {@code /update} reports and changes
 * nothing. {@code /update now} is the confirmation, and behind it is the whole sequence: a
 * countdown every player sees, the servers whose jars change stopped, the schema and the jars
 * moved with nothing running on them, and each server started again and watched until it reports
 * healthy.
 *
 * There is no command that swaps jars into a running server without the full sequence.
 * {@code /update restart} covers "that server is wedged, take it round once": the same sequence
 * with nothing installed.
 *
 * It runs at {@link Target#LOCAL} because the effect is a row in {@code update_request}, and
 * every process already has that pool. Addressing it at the bot would put two hops and a second
 * live process in front of the command somebody types <em>because</em> the network is
 * misbehaving.
 */
public final class UpdateCommands {

    private UpdateCommands() {}

    /**
     * Every {@code /update} command and {@code /backup now}: console only.
     *
     * Taking {@link Surface#GAME} and {@link Surface#DISCORD} off every admin command is
     * deliberate: being able to update from Discord or in game alone is how a network with a
     * broken bot or a broken server becomes one nobody can update from anywhere but a shell - the
     * opposite of what those two surfaces were meant to buy. Console remains, which is the one
     * surface that does not depend on the thing being updated.
     */
    private static final Set<Surface> CONSOLE_ONLY = Set.of(Surface.CONSOLE);

    /**
     * {@code /update check} - resolve every source, compare, report. Writes no file.
     *
     * It is a subcommand rather than the bare {@code /update}, because Discord cannot run a root
     * that has subcommands: a slash command with {@code now}, {@code restart} and {@code cancel}
     * under it is a menu, and a menu is not invokable. In game the bare {@code /update} still
     * works: {@code Catalogue#rootDefault} names this command as what a root with nothing of its
     * own runs, the way {@code /phase} is {@code /phase show}.
     */
    public static final Declaration REPORT =
            new Declaration(List.of("update", "check"), Target.LOCAL, CONSOLE_ONLY, true, false, List.of());

    /**
     * {@code /update now} - the whole sequence, under one confirmation.
     *
     * Irreversible, and it is the plainest case of it in the network: it takes servers away from
     * everybody who is on them. The countdown is a second chance and not the first one - the
     * confirmation comes before the countdown even starts.
     */
    public static final Declaration NOW =
            new Declaration(List.of("update", "now"), Target.LOCAL, CONSOLE_ONLY, true, true, List.of());

    /** {@code /update restart} - the same sequence with nothing installed. */
    public static final Declaration RESTART =
            new Declaration(List.of("update", "restart"), Target.LOCAL, CONSOLE_ONLY, true, true, List.of());

    /** {@code /update cancel} - stop the countdown, for as long as one is running. */
    public static final Declaration CANCEL =
            new Declaration(List.of("update", "cancel"), Target.LOCAL, CONSOLE_ONLY, true, false, List.of());

    /**
     * {@code /backup now} - the same sequence with a volume backup in the gap.
     *
     * It is a different root, declared in this file: {@code backup} and not {@code update},
     * because it is asked for by somebody who is
     * not updating anything - usually right before a change they are unsure of - and because
     * {@code /update now}'s neighbours are the one set of tab-completions in this network worth
     * keeping thin.
     *
     * The <em>declaration</em> is here rather than in a root class of its own for the opposite
     * reason: it writes the same row into the same table, is drawn by the same
     * {@link UpdateFollower}, and is carried out by the same container. Being in {@link #all()} is
     * what puts it into all five processes without a sixth wiring site somebody has to remember -
     * and {@code Target.LOCAL} is exactly the target where forgetting is silent: nothing travels,
     * so nothing times out, so a process that forgot simply has no {@code /backup}.
     *
     * It is cancelled by {@code /update cancel} like every other countdown, which is the one
     * place the shared root shows through. That is deliberate: there is one countdown in this
     * network and one thing that stops it.
     */
    public static final Declaration BACKUP =
            new Declaration(List.of("backup", "now"), Target.LOCAL, CONSOLE_ONLY, true, true, List.of());

    /**
     * {@code /update down <service>} - stop it and leave it stopped.
     *
     * The service is required, and that is the whole safety of it: an unnamed scope is the whole
     * network everywhere else in this mechanism. Here that would be
     * every server held down until somebody presses a button, which is not something anybody asks
     * for by forgetting a word - so the argument is required and the declaration says so, rather
     * than the command guessing.
     *
     * Irreversible for the same reason {@link #NOW} is: it takes a server away from everybody on
     * it. More so, in fact - this one does not bring it back.
     */
    public static final Declaration DOWN = new Declaration(
            List.of("update", "down"), Target.LOCAL, CONSOLE_ONLY, true, true, List.of(Argument.word("service")));

    /**
     * {@code /update start [service]} - take the hold off and start it again.
     *
     * Optional where {@link #DOWN} is required, and not confirmed where {@link #DOWN} is. Both
     * follow from the same reading: nothing here stops anything. Typing it with no service starts
     * everything being held, which is the recovery somebody wants after this process has been
     * restarted and they no longer remember which ones they stopped.
     */
    public static final Declaration START = new Declaration(
            List.of("update", "start"),
            Target.LOCAL,
            CONSOLE_ONLY,
            true,
            false,
            List.of(Argument.word("service").optional()));

    /** Every command served by {@link UpdateEffects} - the six {@code /update} ones and backup. */
    public static List<NordtalCommand<UpdateEffects>> all() {
        return List.of(
                new ReportUpdate(),
                new RunUpdate(UpdateCommands.NOW),
                new RunUpdate(UpdateCommands.RESTART),
                new CancelUpdate(),
                new RunBackup(),
                new HoldService(UpdateCommands.DOWN),
                new HoldService(UpdateCommands.START));
    }

    /** Every declaration in {@link #all()}. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

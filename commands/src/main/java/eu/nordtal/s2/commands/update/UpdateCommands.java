package eu.nordtal.s2.commands.update;

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

    /** {@code /update} - resolve every source, compare, report. Writes no file. */
    public static final Declaration REPORT = new Declaration(
            List.of("update"), Target.LOCAL,
            Set.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE), true, false, List.of());

    /**
     * {@code /update now} - the whole sequence, under one confirmation.
     *
     * <p>Irreversible, and it is the plainest case of it in the network: it takes servers away from
     * everybody who is on them. The countdown is a second chance and not the first one - the
     * confirmation comes before the countdown even starts.</p>
     */
    public static final Declaration NOW = new Declaration(
            List.of("update", "now"), Target.LOCAL,
            Set.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE), true, true, List.of());

    /** {@code /update restart} - the same sequence with nothing installed. */
    public static final Declaration RESTART = new Declaration(
            List.of("update", "restart"), Target.LOCAL,
            Set.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE), true, true, List.of());

    /** {@code /update cancel} - stop the countdown, for as long as one is running. */
    public static final Declaration CANCEL = new Declaration(
            List.of("update", "cancel"), Target.LOCAL,
            Set.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE), true, false, List.of());

    /** Every {@code /update} command. */
    public static List<NordtalCommand<UpdateEffects>> all() {
        return List.of(new ReportUpdate(), new RunUpdate(UpdateCommands.NOW),
                new RunUpdate(UpdateCommands.RESTART), new CancelUpdate());
    }

    /** Every {@code /update} declaration. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

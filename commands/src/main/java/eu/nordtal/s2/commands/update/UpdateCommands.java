package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.Argument;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import java.util.List;
import java.util.Set;

/**
 * {@code /update}: what is new, and the one run that installs it.
 *
 * {@link Target#LOCAL}, since every process can write the row; nothing swaps jars without the full sequence.
 */
public final class UpdateCommands {

    private UpdateCommands() {}

    /** Console only: the one surface that does not depend on the thing being updated. */
    private static final Set<Surface> CONSOLE_ONLY = Set.of(Surface.CONSOLE);

    /**
     * {@code /update check}: resolves every source, compares and reports; writes no file.
     *
     * A subcommand, since Discord cannot run a root with subcommands; a bare {@code /update} still runs it in game.
     */
    public static final Declaration REPORT =
            new Declaration(List.of("update", "check"), Target.LOCAL, CONSOLE_ONLY, true, false, List.of());

    /** {@code /update now}: the whole sequence, under one confirmation that comes before the countdown. */
    public static final Declaration NOW =
            new Declaration(List.of("update", "now"), Target.LOCAL, CONSOLE_ONLY, true, true, List.of());

    /** {@code /update restart}: the same sequence with nothing installed. */
    public static final Declaration RESTART =
            new Declaration(List.of("update", "restart"), Target.LOCAL, CONSOLE_ONLY, true, true, List.of());

    /** {@code /update cancel}: stops the countdown, for as long as one is running. */
    public static final Declaration CANCEL =
            new Declaration(List.of("update", "cancel"), Target.LOCAL, CONSOLE_ONLY, true, false, List.of());

    /**
     * {@code /backup now}: the same sequence with a volume backup in the gap.
     *
     * A separate root keeps {@code /update now}'s neighbours thin; {@link #all()} puts it into every process.
     */
    public static final Declaration BACKUP =
            new Declaration(List.of("backup", "now"), Target.LOCAL, CONSOLE_ONLY, true, true, List.of());

    /**
     * {@code /update down <service>}: stops it and leaves it stopped.
     *
     * The service is required, since an unnamed scope would hold the whole network down.
     */
    public static final Declaration DOWN = new Declaration(
            List.of("update", "down"), Target.LOCAL, CONSOLE_ONLY, true, true, List.of(Argument.word("service")));

    /**
     * {@code /update start [service]}: takes the hold off and starts it again.
     *
     * Unconfirmed and with an optional service: without one, it starts everything being held.
     */
    public static final Declaration START = new Declaration(
            List.of("update", "start"),
            Target.LOCAL,
            CONSOLE_ONLY,
            true,
            false,
            List.of(Argument.word("service").optional()));

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

    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

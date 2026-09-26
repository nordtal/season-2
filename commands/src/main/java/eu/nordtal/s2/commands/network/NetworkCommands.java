package eu.nordtal.s2.commands.network;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import java.util.List;
import java.util.Set;

/**
 * {@code /network reload} - the proxy's one command besides {@code /phase}.
 *
 * The console is an admin here, and for {@code /phase} it is not.
 * {@code /phase} requires a player because it takes a decision about the season and the audit row
 * records who took it. A reload changes no state anybody can be asked about, and the proxy console
 * is where an operator already is when they have just edited a file on the host. So this one carries
 * {@link Surface#CONSOLE} and {@code /phase} does not - a difference that was already true and is
 * now written down where both can be read at once.
 */
public final class NetworkCommands {

    private NetworkCommands() {}

    /**
     * {@code /network reload} - the wording, and nothing that is wired into a running proxy.
     *
     * Console only: neither {@link Surface#GAME} nor {@link Surface#DISCORD} reaches this
     * declaration. {@link Surface#CONSOLE} is the only surface an admin reaches it through.
     */
    public static final Declaration RELOAD = new Declaration(
            List.of("network", "reload"), Target.PROXY, Set.of(Surface.CONSOLE), true, false, List.of());

    /** Every {@code /network} command. */
    public static List<NordtalCommand<NetworkEffects>> all() {
        return List.of(new ReloadNetwork());
    }

    /** Every {@code /network} declaration. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

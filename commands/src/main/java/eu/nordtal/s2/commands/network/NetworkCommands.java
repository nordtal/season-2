package eu.nordtal.s2.commands.network;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import java.util.List;
import java.util.Set;

/** {@code /network reload}: the proxy's one command besides {@code /phase}. */
public final class NetworkCommands {

    private NetworkCommands() {}

    /** {@code /network reload}: the wording, and nothing wired into a running proxy. */
    public static final Declaration RELOAD = new Declaration(
            List.of("network", "reload"), Target.PROXY, Set.of(Surface.CONSOLE), true, false, List.of());

    public static List<NordtalCommand<NetworkEffects>> all() {
        return List.of(new ReloadNetwork());
    }

    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

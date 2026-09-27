package eu.nordtal.s2.commands;

import eu.nordtal.s2.commands.access.AccessCommands;
import eu.nordtal.s2.commands.hungergames.HungerGamesCommands;
import eu.nordtal.s2.commands.limbo.LimboCommands;
import eu.nordtal.s2.commands.network.NetworkCommands;
import eu.nordtal.s2.commands.phase.PhaseCommands;
import eu.nordtal.s2.commands.smp.SmpCommands;
import eu.nordtal.s2.commands.update.UpdateCommands;
import java.util.List;

/**
 * Every command declaration this network has, in one list, so {@code CatalogueTest} can check the whole surface.
 *
 * It holds declarations, not commands, and proves nothing is wired.
 */
public final class Catalogue {

    private Catalogue() {}

    /** Every declaration, in no particular order. */
    public static List<Declaration> all() {
        return java.util.stream.Stream.of(
                        PhaseCommands.declarations(),
                        SmpCommands.declarations(),
                        HungerGamesCommands.declarations(),
                        LimboCommands.declarations(),
                        NetworkCommands.declarations(),
                        AccessCommands.declarations(),
                        eu.nordtal.s2.commands.announce.AnnounceCommands.declarations(),
                        UpdateCommands.declarations())
                .flatMap(List::stream)
                .toList();
    }

    /**
     * Returns the command a bare root runs instead of printing its help, if there is one and they may run it.
     *
     * @param root  the first segment of a path, as typed after the slash
     * @param admin whether the person is an admin; the shortcut skips Brigadier's {@code requires}, so this is the gate
     * @return the declaration to run for the bare root
     */
    public static java.util.Optional<Declaration> rootDefault(final String root, final boolean admin) {
        return java.util.Optional.ofNullable(ROOT_DEFAULTS.get(root))
                .filter(declaration -> admin || !declaration.adminOnly());
    }

    private static final java.util.Map<String, Declaration> ROOT_DEFAULTS = java.util.Map.of(
            "phase",
            PhaseCommands.SHOW,
            // The bare /update is the report, on surfaces that have a bare form; Discord has none.
            "update",
            eu.nordtal.s2.commands.update.UpdateCommands.REPORT);

    /** Everything one process is expected to be able to run. */
    public static List<Declaration> of(final Target target) {
        return all().stream()
                .filter(declaration -> declaration.target() == target)
                .toList();
    }
}

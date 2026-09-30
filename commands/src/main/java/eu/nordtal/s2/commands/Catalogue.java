package eu.nordtal.s2.commands;

import eu.nordtal.s2.commands.hungergames.HungerGamesCommands;
import eu.nordtal.s2.commands.limbo.LimboCommands;
import eu.nordtal.s2.commands.network.NetworkCommands;
import eu.nordtal.s2.commands.smp.SmpCommands;
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
                        SmpCommands.declarations(),
                        HungerGamesCommands.declarations(),
                        LimboCommands.declarations(),
                        NetworkCommands.declarations())
                .flatMap(List::stream)
                .toList();
    }

    /** Everything one process is expected to be able to run. */
    public static List<Declaration> of(final Target target) {
        return all().stream()
                .filter(declaration -> declaration.target() == target)
                .toList();
    }
}

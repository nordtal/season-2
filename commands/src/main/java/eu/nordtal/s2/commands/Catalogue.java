package eu.nordtal.s2.commands;

import eu.nordtal.s2.commands.access.AccessCommands;
import eu.nordtal.s2.commands.hungergames.HungerGamesCommands;
import eu.nordtal.s2.commands.limbo.LimboCommands;
import eu.nordtal.s2.commands.network.NetworkCommands;
import eu.nordtal.s2.commands.phase.PhaseCommands;
import eu.nordtal.s2.commands.smp.SmpCommands;

import java.util.List;

/**
 * Every command this network has, in one list.
 *
 * <h2>Why a list exists at all</h2>
 * Because "every admin command is available on both platforms" is a claim about a set, and until
 * there was a set it could only be checked by reading four adapters and hoping. The list is what
 * makes {@code CatalogueTest} able to say which commands are missing a surface, which are declared
 * twice, and which carry an argument shape the request row cannot express - none of which is
 * answerable from inside a single command.
 *
 * <h2>It is declarations only, and deliberately holds no commands</h2>
 * A {@link Declaration} is a value: a path, a target, some arguments. A {@link NordtalCommand} needs
 * an effect interface implemented by exactly one process, so a list of <em>those</em> could only
 * exist inside one JVM and would be a different list in each. Keeping this to declarations is what
 * lets one test in one module see the whole network's command surface.
 *
 * <p>Which also means this list does not prove anything is <em>wired</em>. That is what each
 * adapter's own wiring test is for; this one proves the design is coherent, not that it runs.</p>
 */
public final class Catalogue {

    private Catalogue() {
    }

    /** Every declaration, in no particular order. */
    public static List<Declaration> all() {
        return java.util.stream.Stream.of(
                        PhaseCommands.declarations(),
                        SmpCommands.declarations(),
                        HungerGamesCommands.declarations(),
                        LimboCommands.declarations(),
                        NetworkCommands.declarations(),
                        AccessCommands.declarations(),
                        eu.nordtal.s2.commands.announce.AnnounceCommands.declarations())
                .flatMap(List::stream)
                .toList();
    }

    /**
     * The one command a bare root runs instead of printing its help.
     * <p>
     * Every root answers "just the root, nothing after it" with the list of what can be typed
     * underneath - the rule {@code PaperCommands} and {@code VelocityCommands} apply to
     * {@code /smp}, {@code /hg} and {@code /access}. {@code /phase} is the exception, decided by the
     * owner on 2026-09-05 after the local rehearsal showed the fold had quietly changed it: it is
     * the command somebody types while the network is misbehaving, and the thing they want is the
     * phase, not four lines of syntax. So {@code /phase} is {@code /phase show}. Kept as data here
     * rather than as a flag on {@link Declaration}, because it is a fact about a root and there is
     * one of them; {@code CatalogueTest} holds every entry to a two-segment path under its own root
     * with nothing required after it, which is what "runnable with nothing typed" means.
     * </p>
     *
     * @param root the first segment of a path, as typed after the slash
     * @return the declaration to run for the bare root, if that root has one
     */
    public static java.util.Optional<Declaration> rootDefault(final String root) {
        return java.util.Optional.ofNullable(ROOT_DEFAULTS.get(root));
    }

    private static final java.util.Map<String, Declaration> ROOT_DEFAULTS =
            java.util.Map.of("phase", PhaseCommands.SHOW);

    /** Everything one process is expected to be able to run. */
    public static List<Declaration> of(final Target target) {
        return all().stream().filter(declaration -> declaration.target() == target).toList();
    }
}

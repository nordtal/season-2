package eu.nordtal.s2.commands;

import eu.nordtal.s2.commands.phase.PhaseCommands;

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
        return List.of(
                PhaseCommands.SHOW,
                PhaseCommands.SET,
                PhaseCommands.LAUNCH,
                PhaseCommands.SMP_START);
    }

    /** Everything one process is expected to be able to run. */
    public static List<Declaration> of(final Target target) {
        return all().stream().filter(declaration -> declaration.target() == target).toList();
    }
}

package eu.nordtal.s2.commands.limbo;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import java.util.List;
import java.util.Set;

/** {@code /limbo reload}: the waiting room's one command, which swaps the wording without a restart. */
public final class LimboCommands {

    private LimboCommands() {}

    /** {@code /limbo reload}: the wording, never the world. */
    public static final Declaration RELOAD =
            new Declaration(List.of("limbo", "reload"), Target.LIMBO, Set.of(Surface.CONSOLE), true, false, List.of());

    public static List<NordtalCommand<LimboEffects>> all() {
        return List.of(new ReloadLimbo());
    }

    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

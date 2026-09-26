package eu.nordtal.s2.commands.limbo;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.Surface;
import eu.nordtal.s2.commands.Target;
import java.util.List;
import java.util.Set;

/**
 * {@code /limbo reload} - the one command the waiting room has.
 *
 * Every line a player sees here is a title on a black screen, and the whole user interface of this
 * server is eight of them. A wording change that needs a restart is a wording change that takes the
 * waiting room down while somebody is waiting in it, which is the one moment it must not go away.
 *
 * A player on this server is mid-login and has no chat, so this had to travel: it is a slash
 * command in Discord and a chat command on the two servers where somebody actually is.
 *
 * The old gate, {@code limbo.admin}, is gone: the in-game half now goes through the same admin flag
 * as every other command, which is what an admin already had to have as a server operator on all
 * three backends. The console half is unaffected - {@code PaperCommands} accepts a
 * {@code ConsoleCommandSender} by type and asks nothing else.
 */
public final class LimboCommands {

    private LimboCommands() {}

    /**
     * {@code /limbo reload} - the wording, never the world.
     *
     * Console only: neither {@link Surface#GAME} nor {@link Surface#DISCORD} reaches this or any
     * other admin command. The class javadoc's "nobody is standing here to type it" is about a
     * player who is not an admin; it does not argue for an admin's own in-game or Discord path.
     */
    public static final Declaration RELOAD =
            new Declaration(List.of("limbo", "reload"), Target.LIMBO, Set.of(Surface.CONSOLE), true, false, List.of());

    /** Every {@code /limbo} command. */
    public static List<NordtalCommand<LimboEffects>> all() {
        return List.of(new ReloadLimbo());
    }

    /** Every {@code /limbo} declaration. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

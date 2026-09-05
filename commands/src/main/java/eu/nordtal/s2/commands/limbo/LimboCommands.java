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
 * <h2>Why a waiting room has a command at all</h2>
 * Every line a player sees here is a title on a black screen, and the whole user interface of this
 * server is eight of them. A wording change that needs a restart is a wording change that takes the
 * waiting room down while somebody is waiting in it, which is the one moment it must not go away.
 *
 * <h2>Nobody is standing here to type it, which is why it had to travel</h2>
 * A player on this server is mid-login and has no chat. Before 2026-09-05 that left the console as
 * the only way in - and the console of a container, which means a shell on the production host. Now
 * it is a slash command in Discord and a chat command on the two servers where somebody actually is.
 *
 * <h2>What replaced the permission node</h2>
 * The gate was {@code limbo.admin}, declared in {@code paper-plugin.yml} as the only permission node
 * this repository owned, and justified there by "the database is exactly what a broken limbo may not
 * be able to reach". The console half of that survives untouched - {@code PaperCommands} accepts a
 * {@code ConsoleCommandSender} by type and asks nothing else. The in-game half now goes through the
 * same admin flag as every other command, which is what an admin already had to have: since
 * 2026-09-04 an admin <em>is</em> a server operator on all three backends, and {@code default: op}
 * is what the node granted. The node is gone rather than left as a second answer to one question.
 */
public final class LimboCommands {

    private LimboCommands() {
    }

    /** {@code /limbo reload} - the wording, never the world. */
    public static final Declaration RELOAD = new Declaration(
            List.of("limbo", "reload"), Target.LIMBO,
            Set.of(Surface.GAME, Surface.DISCORD, Surface.CONSOLE), true, false, List.of());

    /** Every {@code /limbo} command. */
    public static List<NordtalCommand<LimboEffects>> all() {
        return List.of(new ReloadLimbo());
    }

    /** Every {@code /limbo} declaration. */
    public static List<Declaration> declarations() {
        return all().stream().map(NordtalCommand::declaration).toList();
    }
}

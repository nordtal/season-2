package eu.nordtal.s2.limbo.command;

import eu.nordtal.s2.commands.limbo.LimboEffects;
import eu.nordtal.s2.common.message.Messages;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Executor;

/**
 * {@link LimboEffects} against this server.
 *
 * <p>Off the main thread for the chat path, because this reads files and this server's whole job is
 * to be responsive to players who are already staring at a black screen. Inline for the inbox, for
 * the reason every effects implementation here is: the inbox settles a request row when the command
 * returns.</p>
 */
public final class BukkitLimboEffects implements LimboEffects {

    private final Plugin plugin;
    private final Executor executor;
    private final Messages messages;

    public BukkitLimboEffects(final Plugin plugin, final Executor executor,
                              final Messages messages) {
        this.plugin = plugin;
        this.executor = executor;
        this.messages = messages;
    }

    /** Everything {@code /limbo} does off the main thread, on the plugin's async scheduler. */
    public static Executor async(final Plugin plugin) {
        return task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void async(final Runnable work) {
        executor.execute(work);
    }

    @Override
    public void warn(final String what, final Throwable failure) {
        plugin.getLogger().log(java.util.logging.Level.WARNING, what, failure);
    }

    @Override
    public boolean reloadMessages() {
        try {
            messages.reload();
            messages.unknownOverrideKeys().forEach(unknown -> plugin.getLogger().warning(
                    "the message override names " + unknown + ", which no bundle declares - it"
                            + " is stored and never used; check the spelling"));
            return true;
        } catch (final RuntimeException failure) {
            plugin.getLogger().severe("the messages could not be reloaded, the running ones are "
                    + "unchanged: " + failure.getMessage());
            return false;
        }
    }
}

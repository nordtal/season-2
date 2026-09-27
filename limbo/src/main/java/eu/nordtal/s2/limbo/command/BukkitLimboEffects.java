package eu.nordtal.s2.limbo.command;

import eu.nordtal.s2.commands.limbo.LimboEffects;
import eu.nordtal.s2.common.message.Messages;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * {@link LimboEffects} against this server.
 *
 * Off the main thread for chat, since this reads files; inline for the inbox, which settles its row on return.
 */
public final class BukkitLimboEffects implements LimboEffects {

    private final Plugin plugin;
    private final Executor executor;
    private final Messages messages;
    private final Messages shared;

    /**
     * Takes this plugin's bundle and {@code :commands}' shared one, which reload together so chat and Discord agree.
     */
    public BukkitLimboEffects(
            final Plugin plugin, final Executor executor, final Messages messages, final Messages shared) {
        this.plugin = plugin;
        this.executor = executor;
        this.messages = messages;
        this.shared = shared;
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
            // Unknown keys in the shared bundle go unreported: it holds one root, not this module's own keys.
            shared.reload();
            messages.unknownOverrideKeys()
                    .forEach(unknown -> plugin.getLogger()
                            .warning("the message override names " + unknown + ", which no bundle declares - it"
                                    + " is stored and never used; check the spelling"));
            return true;
        } catch (final RuntimeException failure) {
            plugin.getLogger()
                    .severe("the messages could not be reloaded, the running ones are " + "unchanged: "
                            + failure.getMessage());
            return false;
        }
    }
}

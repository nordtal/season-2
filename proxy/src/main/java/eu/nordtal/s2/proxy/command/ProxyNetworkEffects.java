package eu.nordtal.s2.proxy.command;

import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.commands.network.NetworkEffects;
import eu.nordtal.s2.common.message.Messages;
import java.util.concurrent.Executor;
import org.slf4j.Logger;

/**
 * {@link NetworkEffects} against this proxy.
 *
 * Off the event thread for chat, since this reads files; inline for the inbox, which settles its row on return.
 */
public final class ProxyNetworkEffects implements NetworkEffects {

    private final Executor executor;
    private final Messages messages;
    private final Messages shared;
    private final Logger logger;

    /** Takes this proxy's bundle and {@code :commands}' shared one, which reload together so chat and Discord agree. */
    public ProxyNetworkEffects(
            final Executor executor, final Messages messages, final Messages shared, final Logger logger) {
        this.executor = executor;
        this.messages = messages;
        this.shared = shared;
        this.logger = logger;
    }

    /** Velocity's scheduler, for the path a player typed. */
    public static Executor async(final Object plugin, final ProxyServer proxy) {
        return task -> proxy.getScheduler().buildTask(plugin, task).schedule();
    }

    @Override
    public void async(final Runnable work) {
        executor.execute(work);
    }

    @Override
    public void warn(final String what, final Throwable failure) {
        logger.warn(what, failure);
    }

    @Override
    public boolean reloadMessages() {
        try {
            messages.reload();
            // Unknown keys in the shared bundle go unreported: it holds one root, not this module's own keys.
            shared.reload();
            messages.unknownOverrideKeys()
                    .forEach(unknown -> logger.warn(
                            "the message override names {}, which no bundle declares - it is stored and"
                                    + " never used; check the spelling",
                            unknown));
            return true;
        } catch (final RuntimeException failure) {
            logger.error("the messages could not be reloaded, the running ones are unchanged", failure);
            return false;
        }
    }
}

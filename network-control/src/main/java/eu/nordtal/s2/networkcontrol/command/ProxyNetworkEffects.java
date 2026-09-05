package eu.nordtal.s2.networkcontrol.command;

import com.velocitypowered.api.proxy.ProxyServer;

import eu.nordtal.s2.commands.network.NetworkEffects;
import eu.nordtal.s2.common.message.Messages;

import org.slf4j.Logger;

import java.util.concurrent.Executor;

/**
 * {@link NetworkEffects} against this proxy.
 *
 * <p>Off the event thread for the chat path: this reads files, and a proxy thread blocked on disk is
 * every login blocked on disk. Inline for the command inbox, because the inbox settles a request row
 * when the command returns.</p>
 */
public final class ProxyNetworkEffects implements NetworkEffects {

    private final Executor executor;
    private final Messages messages;
    private final Logger logger;

    public ProxyNetworkEffects(final Executor executor, final Messages messages,
                               final Logger logger) {
        this.executor = executor;
        this.messages = messages;
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
            messages.unknownOverrideKeys().forEach(unknown -> logger.warn(
                    "the message override names {}, which no bundle declares - it is stored and"
                            + " never used; check the spelling", unknown));
            return true;
        } catch (final RuntimeException failure) {
            logger.error("the messages could not be reloaded, the running ones are unchanged",
                    failure);
            return false;
        }
    }
}

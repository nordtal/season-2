package eu.nordtal.s2.networkcontrol.command;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.info.InfoEffects;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;

import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * {@code /discord} and {@code /rules}, printed out of this proxy's own bundle.
 *
 * <h2>Why the text is here and not in the shared bundle</h2>
 * Because it wants a clickable link and a colour, and {@code :commands}' bundle carries no markup at
 * all - Discord reads the same file and would show the tags. So the command names the key and this
 * renders it, the same split the private messages use.
 *
 * <h2>The invite is the same string the login screens use</h2>
 * {@code gate.yml#discord-invite-url}, substituted as {@code {invite}}. An invite that has been
 * re-issued is re-issued in exactly one place; a second copy of it in a message bundle is the copy
 * that stays pointing at a dead link, and the people reading it are by definition the people who
 * cannot get in.
 */
public final class ProxyInfoEffects implements InfoEffects {

    private final ProxyServer proxy;
    private final Messages messages;
    private final String invite;
    private final Executor executor;
    private final Logger logger;

    public ProxyInfoEffects(final ProxyServer proxy, final Messages messages, final String invite,
                            final Executor executor, final Logger logger) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.invite = Objects.requireNonNull(invite, "invite");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
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
    public void show(final NordtalUser user, final String messageKey) {
        // The player rather than the NordtalUser, because the value is MiniMessage and
        // NordtalUser#reply renders against the layered bundle the same way - this is the same
        // audience, reached directly so that the key can be a proxy-only one.
        final java.util.Optional<Player> player = user.minecraftUuid().flatMap(proxy::getPlayer);
        if (player.isEmpty()) {
            // The command is declared on GAME alone, so the adapter has already refused the
            // console. This is the window between typing and answering.
            return;
        }
        player.get().sendMessage(MessageRenderer.of(messages)
                .format(user.locale(), messageKey, "invite", invite));
    }
}

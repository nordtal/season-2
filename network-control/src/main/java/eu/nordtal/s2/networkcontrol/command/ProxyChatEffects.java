package eu.nordtal.s2.networkcontrol.command;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.chat.ChatEffects;
import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.networkcontrol.gate.LoginRoster;

import net.kyori.adventure.text.Component;

import org.slf4j.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Private messages, carried by the one process that can see both people.
 *
 * <h2>The message is a component and never a substituted string</h2>
 * {@link MessageRenderer#format(Locale, String, Map, Object...)}'s component slot, the same
 * mechanism {@code smp}'s chat format uses for a death message. A player's text substituted into a
 * MiniMessage string would be parsed - {@code MessageRenderer} escapes what it substitutes, so it
 * would not be parsed as markup, but it would also be the only place in this class where a rule has
 * to be remembered rather than expressed. As a component it never reaches the parser at all, and
 * somebody called {@code <red>} cannot colour a line about themselves.
 *
 * <h2>Two lines, two languages</h2>
 * The sender is told what they sent in their language and the recipient what arrived in theirs, both
 * out of {@link LoginRoster} - {@code discord_user.locale}, never the Minecraft client's own
 * setting, which docs/i18n.md forbids by name. That is the whole reason this cannot be an ordinary
 * {@code NordtalUser#reply}: a reply goes to the asker, and half of a private message does not.
 *
 * <h2>What a line carries, and what it deliberately does not</h2>
 * The flag and the admin tag, both of which the proxy already holds from the login query. Not the
 * prestige crest and not the aura: those live in the SMP's tables, and fetching them would be a
 * query per message on the process that must not make one. It also keeps a whisper from looking
 * exactly like ordinary chat, which is worth something on its own - a whisper somebody mistakes for
 * public chat is a whisper they answer in public.
 *
 * <h2>Nothing is written down</h2>
 * No log line carries the text, no admin channel is told, no table is touched (Till, 2026-09-08).
 * The only state this class holds is who last spoke to whom, in memory, dropped on disconnect.
 */
public final class ProxyChatEffects implements ChatEffects {

    private final ProxyServer proxy;
    private final LoginRoster roster;
    private final Messages messages;
    private final Executor executor;
    private final Logger logger;

    /**
     * Who each connected player last exchanged a private message with, for {@code /r}.
     *
     * <p>Set by <b>both</b> sides of every message, which is what makes an answer possible without
     * either of them having typed a name. Dropped on disconnect, so this map is the size of the
     * player list and not of the session log.</p>
     */
    private final ConcurrentHashMap<UUID, UUID> partners = new ConcurrentHashMap<>();

    public ProxyChatEffects(final ProxyServer proxy, final LoginRoster roster,
                            final Messages messages, final Executor executor, final Logger logger) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.roster = Objects.requireNonNull(roster, "roster");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void async(final Runnable work) {
        executor.execute(work);
    }

    @Override
    public void warn(final String what, final Throwable failure) {
        // Never the text. A delivery that failed is worth knowing about; what somebody wrote is not
        // ours to keep, and a log file is the one place it would survive.
        logger.warn(what, failure);
    }

    @Override
    public Outcome whisper(final NordtalUser from, final UUID to, final String text) {
        final Optional<Player> sender = from.minecraftUuid().flatMap(proxy::getPlayer);
        final Optional<Player> recipient = proxy.getPlayer(to);
        if (sender.isEmpty() || recipient.isEmpty()) {
            return Outcome.GONE;
        }
        deliver(sender.get(), recipient.get(), text);
        return Outcome.SENT;
    }

    @Override
    public Outcome replyToLast(final NordtalUser from, final String text) {
        final Optional<Player> sender = from.minecraftUuid().flatMap(proxy::getPlayer);
        if (sender.isEmpty()) {
            return Outcome.GONE;
        }
        final UUID partner = partners.get(sender.get().getUniqueId());
        if (partner == null) {
            return Outcome.NO_PARTNER;
        }
        final Optional<Player> recipient = proxy.getPlayer(partner);
        if (recipient.isEmpty()) {
            // Left standing rather than removed: they may come back, and telling somebody "they are
            // not here" twice is better than telling them "nobody has written to you" once they
            // have gone offline.
            return Outcome.GONE;
        }
        deliver(sender.get(), recipient.get(), text);
        return Outcome.SENT;
    }

    /** Both halves of one message, and the reply partner on both sides. */
    private void deliver(final Player sender, final Player recipient, final String text) {
        final Component body = Component.text(text);

        sender.sendMessage(MessageRenderer.of(messages).format(localeOf(sender), "chat.msg.sent",
                Map.of("_message", body),
                "name", recipient.getUsername(),
                "flag", Glyphs.flagFor(localeOf(recipient)),
                "admin", adminTag(recipient)));

        recipient.sendMessage(MessageRenderer.of(messages).format(localeOf(recipient),
                "chat.msg.received",
                Map.of("_message", body),
                "name", sender.getUsername(),
                "flag", Glyphs.flagFor(localeOf(sender)),
                "admin", adminTag(sender)));

        partners.put(sender.getUniqueId(), recipient.getUniqueId());
        partners.put(recipient.getUniqueId(), sender.getUniqueId());
    }

    /**
     * The admin tag with the space in front of it, or nothing at all.
     *
     * <p>Substituted as a {@code {admin}} parameter rather than composed here, so the bundle decides
     * where in the line it sits. It is a private-use code point out of {@code Glyphs} and never
     * written into a {@code .properties} file - the rule this repository has for every glyph.</p>
     */
    private String adminTag(final Player player) {
        return roster.isAdmin(player.getUniqueId()) ? " " + Glyphs.TAG_ADMIN : "";
    }

    private Locale localeOf(final Player player) {
        return roster.localeOf(player.getUniqueId());
    }

    /** Drops both directions of a conversation the moment one side leaves. */
    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        final UUID gone = event.getPlayer().getUniqueId();
        partners.remove(gone);
        partners.values().removeIf(gone::equals);
    }
}

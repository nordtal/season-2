package eu.nordtal.s2.papercommon.chat;

import static eu.nordtal.s2.papercommon.PaperCommonMessages.MESSAGES;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.messagerendering.GameLines;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messagerendering.Names;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.context.PlayerContext;
import eu.nordtal.s2.papercommon.player.Identities;
import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Renders the five lines a player reads all day (said, joined, left, died, earned) per reader, in their language.
 *
 * A death whose {@code deathMessage()} is {@code null} is not news: an arena death, or the gamerule off.
 */
public final class SystemLines implements Listener {

    private final Names names;
    private final Messages messages;
    private final Identities identities;

    /**
     * @param names how this server draws a player a line is about, which differs between servers; called on the main
     *     thread and on Paper's chat thread, so it reads from a cache, never a database
     */
    public SystemLines(final Names names, final Messages messages, final Identities identities) {
        this.names = Objects.requireNonNull(names, "names");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    /**
     * Renders the chat line per recipient: the speaker's flag, a hairline rule, and what was typed.
     *
     * The flag belongs to the speaker and the words around it to the reader.
     */
    @EventHandler(ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final PlayerContext sender = context(event.getPlayer());
        final MessageRenderer renderer = renderer();
        event.renderer((source, displayName, message, viewer) ->
                renderer.format(localeOf(viewer), MESSAGES.system().chat().line(sender, GameLines.text(message))));
    }

    /** Suppresses the vanilla line; {@link #announceJoin(Player)} sends the replacement. */
    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    /**
     * Announces a join once the player's locale has loaded.
     *
     * Called from each module's {@code languageKnown}, one tick after join, once every join handler ran.
     */
    public void announceJoin(final Player player) {
        broadcast(MESSAGES.system().join(context(player)), viewer -> true);
    }

    /** Announces a quit at {@code LOWEST}, before {@code JoinGate#onQuit} forgets the flag and the crest. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        event.quitMessage(null);
        final Player leaving = event.getPlayer();
        // Not to the person leaving: they are already on a disconnect screen.
        broadcast(MESSAGES.system().leave(context(leaving)), viewer -> !viewer.equals(leaving));
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        final Component vanilla = event.deathMessage();
        if (vanilla == null) {
            return;
        }
        event.deathMessage(null);
        broadcast(MESSAGES.system().death(GameLines.of(vanilla)), viewer -> true);
    }

    @EventHandler
    public void onAdvancement(final PlayerAdvancementDoneEvent event) {
        // Null for a recipe unlock, a silent advancement, or the gamerule off.
        if (event.message() == null) {
            return;
        }
        final AdvancementDisplay display = event.getAdvancement().getDisplay();
        if (display == null) {
            return;
        }
        event.message(null);
        broadcast(
                MESSAGES.system().advancement(context(event.getPlayer()), GameLines.of(display.title())),
                viewer -> true);
    }

    /**
     * Announces one system line from outside a Bukkit event, such as an offline hunger games kill.
     *
     * @param message a message from the caller's own spec
     */
    public void announce(final MessageRef message) {
        broadcast(message, viewer -> true);
    }

    /** A player as a line names them. */
    private static PlayerContext context(final Player player) {
        return PlayerContext.of(PlayerId.of(player.getUniqueId()), player.getName());
    }

    /** A renderer that draws every player a line names the way this server draws them. */
    private MessageRenderer renderer() {
        return MessageRenderer.of(messages, names);
    }

    /** Renders {@code message} once per reader, in that reader's language. */
    private void broadcast(final MessageRef message, final Predicate<Player> to) {
        final MessageRenderer renderer = renderer();
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            if (!to.test(viewer)) {
                continue;
            }
            final Locale locale = identities.languageOf(viewer.getUniqueId());
            viewer.sendMessage(renderer.format(locale, message));
        }
    }

    /** Returns the reader's language, or English for an audience that is not a player. */
    private Locale localeOf(final net.kyori.adventure.audience.Audience viewer) {
        return viewer instanceof Player player
                ? identities.languageOf(player.getUniqueId())
                : eu.nordtal.s2.common.language.Locales.DEFAULT;
    }
}

package eu.nordtal.s2.papercommon.chat;

import static eu.nordtal.s2.papercommon.PaperCommonMessages.MESSAGES;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
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

    /**
     * Draws a player in a line about them, the one thing that differs between servers.
     *
     * Called on the main thread and on Paper's chat thread, so it reads from a cache, never a database.
     */
    @FunctionalInterface
    public interface Composition {

        /**
         * @param player whoever the line is about, not whoever reads it
         * @return their name as this server draws it, already styled
         */
        Component of(Player player);
    }

    private final Composition composition;
    private final Messages messages;
    private final PlayerLocales locales;

    public SystemLines(final Composition composition, final Messages messages, final PlayerLocales locales) {
        this.composition = Objects.requireNonNull(composition, "composition");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.locales = Objects.requireNonNull(locales, "locales");
    }

    /**
     * Renders the chat line per recipient: the speaker's flag, a hairline rule, and what was typed.
     *
     * The flag belongs to the speaker and the words around it to the reader.
     */
    @EventHandler(ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final Component sender = composition.of(event.getPlayer());
        final MessageRenderer renderer = MessageRenderer.of(messages);
        event.renderer((source, displayName, message, viewer) ->
                renderer.format(localeOf(viewer), MESSAGES.system().chat().line(sender, Glyphs.SEPARATOR, message)));
    }

    /** Suppresses the vanilla line; {@link #announceJoin(Player)} sends the replacement. */
    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    /**
     * Announces a join once the player's locale has loaded.
     *
     * Called from each module's locale callback, since a line sent at join would be English for the joining player.
     */
    public void announceJoin(final Player player) {
        broadcast(MESSAGES.system().join(Glyphs.ICON_JOIN, composition.of(player)), viewer -> true);
    }

    /** Announces a quit at {@code LOWEST}, before {@code JoinGate#onQuit} forgets the flag and the crest. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        event.quitMessage(null);
        final Player leaving = event.getPlayer();
        final Component who = composition.of(leaving);
        // Not to the person leaving: they are already on a disconnect screen.
        broadcast(MESSAGES.system().leave(Glyphs.ICON_LEAVE, who), viewer -> !viewer.equals(leaving));
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        final Component vanilla = event.deathMessage();
        if (vanilla == null) {
            return;
        }
        event.deathMessage(null);
        broadcast(MESSAGES.system().death(Glyphs.ICON_DEATH, vanilla), viewer -> true);
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
                MESSAGES.system()
                        .advancement(Glyphs.ICON_ADVANCEMENT, composition.of(event.getPlayer()), display.title()),
                viewer -> true);
    }

    /**
     * Announces one system line from outside a Bukkit event, such as an offline hunger games kill.
     *
     * @param message a message from the caller's own spec, its {@code icon} one of {@link Glyphs}' icons
     */
    public void announce(final MessageRef message) {
        broadcast(message, viewer -> true);
    }

    /** Renders {@code message} once per reader, in that reader's language. */
    private void broadcast(final MessageRef message, final Predicate<Player> to) {
        final MessageRenderer renderer = MessageRenderer.of(messages);
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            if (!to.test(viewer)) {
                continue;
            }
            final Locale locale = locales.of(viewer.getUniqueId());
            viewer.sendMessage(renderer.format(locale, message));
        }
    }

    /** Returns the reader's language, or English for an audience that is not a player. */
    private Locale localeOf(final net.kyori.adventure.audience.Audience viewer) {
        return viewer instanceof Player player
                ? locales.of(player.getUniqueId())
                : eu.nordtal.s2.common.message.Locales.DEFAULT;
    }
}

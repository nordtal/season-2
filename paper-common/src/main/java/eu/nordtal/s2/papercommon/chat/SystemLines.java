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
 * The five lines a player reads all day - said, joined, left, died, earned - rendered here rather than by vanilla.
 *
 * Vanilla broadcasts each one as a bare name with no flag or icon, in the server's own language.
 * Each of these five is rendered per reader, in that reader's own language instead.
 *
 * A death message and an advancement title keep vanilla's own {@code TranslatableComponent}: the
 * client renders it, so each reader gets it in their own language, with the mob's name and the
 * killer's weapon in it, off the same packet. The wording is vanilla's and the line is ours - and on
 * the hunger games that line is also the kill feed.
 *
 * A death is announced exactly when vanilla would have announced it: {@code
 * event.deathMessage()} being {@code null} already means the death is not news, whether because it
 * is an arena death reported elsewhere or because {@code /gamerule showDeathMessages false} silenced
 * the whole server. Reading that instead of asking every subsystem in turn keeps this class from
 * needing to know about duels.
 */
public final class SystemLines implements Listener {

    /**
     * How this server draws a player in a line about them.
     *
     * The one thing that genuinely differs between the two servers: the SMP draws a flag, a name
     * and a prestige crest earned over a season, the hunger games draw a flag and a name because
     * nobody has been there longer than an hour. Everything else about these five lines is the
     * same, which is why this is an argument rather than a subclass.
     *
     * Called on the main thread for the four broadcast lines and on Paper's chat thread for the
     * chat line, so an implementation must read from a cache and never from a database.
     */
    @FunctionalInterface
    public interface Composition {

        /**
         * @param player whoever the line is about - <b>not</b> whoever is reading it
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
     * The chat line: the composition, a hairline rule, and what was typed.
     *
     * Paper calls the renderer once per recipient, which is what makes "in the reader's
     * language" free. The two languages in one line are deliberate and are two different people's:
     * the flag belongs to whoever is <em>speaking</em>, because it says what to greet them in, and
     * the words around it belong to whoever is reading.
     */
    @EventHandler(ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final Component sender = composition.of(event.getPlayer());
        final MessageRenderer renderer = MessageRenderer.of(messages);
        event.renderer((source, displayName, message, viewer) ->
                renderer.format(localeOf(viewer), MESSAGES.system().chat().line(sender, Glyphs.SEPARATOR, message)));
    }

    /**
     * Suppresses the vanilla line.
     *
     * <b>The replacement is not sent from here</b>, see {@link #announceJoin(Player)}.
     */
    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    /**
     * The join line, once the joining player's language is known.
     *
     * Called by each module's presence listener from the callback that loads the locale, and not
     * from a join handler, because a join handler is exactly one moment too early: the language is a
     * database read taken off the main thread, so a line broadcast at join would render in English
     * for the very player it is about. Everything else on either server - the HUD, the boards, the
     * tab list - is redrawn on a timer and picks the language up by itself; this is the one message
     * with a single moment.
     *
     * @param player the player who has just arrived, and whose locale has just landed
     */
    public void announceJoin(final Player player) {
        broadcast(MESSAGES.system().join(Glyphs.ICON_JOIN, composition.of(player)), viewer -> true);
    }

    /**
     * {@code LOWEST}, and the priority is load-bearing rather than tidy.
     *
     * {@code smp}'s {@code JoinGate#onQuit} forgets the identity at the default priority, and the
     * identity is what carries the flag and the crest - so a handler that ran after it would
     * announce a departure with a default English flag and a tier-1 crest for everybody. The one
     * ordering this class depends on is therefore written down here rather than left to registration
     * order.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        event.quitMessage(null);
        final Player leaving = event.getPlayer();
        final Component who = composition.of(leaving);
        // Not to the person leaving: they are already on a disconnect screen, unable to read it.
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
        // Nullable by design in Paper: null for a recipe unlock, a silent advancement, or the gamerule off.
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
     * One system line, from somewhere other than a Bukkit event.
     *
     * The five handlers above cover everything vanilla announces. This is for a death vanilla
     * does <em>not</em> announce: a hunger games participant killed through the armor stand standing
     * in for them while they are offline, which carries no {@code EntityDeathEvent} death message.
     * The caller supplies the wording; the icon, the per-reader language and the shape stay here, so
     * such a line cannot drift away from the ones beside it.
     *
     * @param message a message from the caller's own spec, its {@code icon} one of {@link Glyphs}'
     *                icons
     */
    public void announce(final MessageRef message) {
        broadcast(message, viewer -> true);
    }

    /**
     * Renders {@code message} once per reader, in that reader's language.
     *
     * Per reader rather than once: a locale is a cache lookup and these fire a handful of times
     * an hour, which is the opposite end of the scale from the boss bar's four renders a second.
     */
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

    /**
     * The reader's language, or English for an audience that is not a player.
     *
     * The console is such an audience, and so is anything else that has been given a copy of
     * chat; neither has a row in {@code discord_user}, so there is nothing to look up rather than
     * something missing.
     */
    private Locale localeOf(final net.kyori.adventure.audience.Audience viewer) {
        return viewer instanceof Player player
                ? locales.of(player.getUniqueId())
                : eu.nordtal.s2.common.message.Locales.DEFAULT;
    }
}

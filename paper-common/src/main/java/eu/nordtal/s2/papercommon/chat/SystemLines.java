package eu.nordtal.s2.papercommon.chat;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;

import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
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

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The five lines a player reads all day - said, joined, left, died, earned - written by us on every
 * Paper server rather than by vanilla on one of them.
 *
 * <h2>Why replace them at all</h2>
 * Not for decoration. Vanilla broadcasts one line in <em>the server's</em> language to a server
 * where half the players read German, and it broadcasts it as a bare name with no flag, no icon and
 * nothing of the composition every other surface draws - so the lines everybody reads most are the
 * only surface in the season that looks like nothing was done to it. Each of these is sent per
 * reader, in that reader's language.
 *
 * <h2>Why it is here and not in {@code smp}</h2>
 * <b>It was in {@code smp}, and that was the whole of the defect.</b> Until 2026-09-09 the hunger
 * games - the season's flagship event, the one every player attends at the same moment - had
 * vanilla chat, vanilla join and leave and vanilla death messages, in yellow, in one language, with
 * no flag on anybody (finding 149). The class was written for the SMP and nothing about it was
 * about the SMP; the one part that genuinely differed is the composition, which is now an argument.
 *
 * <h2>Two of the five keep vanilla's own component, and that is the point</h2>
 * A death message and an advancement title are {@code TranslatableComponent}s: the <em>client</em>
 * renders them, so a German client reads "wurde von einem Zombie getötet" and an English one reads
 * "was slain by a Zombie", off the same packet, with the mob's name and the killer's weapon in it.
 * Fifty hand-written keys per language could not match that and would go stale on the next
 * Minecraft release. So the wording is vanilla's and the <em>line</em> is ours - and on the hunger
 * games that line is the kill feed, which is why it needs no key of its own there either.
 *
 * <h2>The rule that decides whether a death is announced</h2>
 * <b>We announce a death exactly when vanilla would have.</b> {@code event.deathMessage()} being
 * {@code null} already means somebody has decided this death is not news - {@code smp}'s
 * {@code DuelListener} does it for an arena death, which costs nobody anything and is already
 * reported to the two people it concerns, and {@code /gamerule showDeathMessages false} does it for
 * the whole server. Reading that instead of asking every subsystem in turn is what keeps this class
 * from having to know about duels, and it cannot be got wrong by an event-priority accident.
 */
public final class SystemLines implements Listener {

    /**
     * How this server draws a player in a line about them.
     *
     * <p>The one thing that genuinely differs between the two servers: the SMP draws a flag, a name
     * and a prestige crest earned over a season, the hunger games draw a flag and a name because
     * nobody has been there longer than an hour. Everything else about these five lines is the
     * same, which is why this is an argument rather than a subclass.</p>
     *
     * <p>Called on the main thread for the four broadcast lines and on Paper's chat thread for the
     * chat line, so an implementation must read from a cache and never from a database.</p>
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

    public SystemLines(final Composition composition, final Messages messages,
                       final PlayerLocales locales) {
        this.composition = Objects.requireNonNull(composition, "composition");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.locales = Objects.requireNonNull(locales, "locales");
    }

    /**
     * The chat line: the composition, a hairline rule, and what was typed.
     *
     * <p>Paper calls the renderer once per recipient, which is what makes "in the reader's
     * language" free. The two languages in one line are deliberate and are two different people's:
     * the flag belongs to whoever is <em>speaking</em>, because it says what to greet them in, and
     * the words around it belong to whoever is reading.</p>
     */
    @EventHandler(ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final Component sender = composition.of(event.getPlayer());
        final MessageRenderer renderer = MessageRenderer.of(messages);
        event.renderer((source, displayName, message, viewer) ->
                renderer.format(localeOf(viewer), "system.chat.line",
                        Map.of("_sender", sender, "_message", message),
                        "separator", Glyphs.SEPARATOR));
    }

    /**
     * Suppresses the vanilla line. <b>The replacement is not sent from here</b>, see
     * {@link #announceJoin(Player)}.
     */
    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    /**
     * The join line, once the joining player's language is known.
     *
     * <p>Called by each module's presence listener from the callback that loads the locale, and not
     * from a join handler, because a join handler is exactly one moment too early: the language is a
     * database read taken off the main thread (finding 96), so a line broadcast at join renders in
     * English for the very player it is about. Everything else on either server - the HUD, the
     * boards, the tab list - is redrawn on a timer and picks the language up by itself; this is the
     * one message with a single moment, and on the local stack it was the one German player being
     * told <i>hmtill joined.</i> under a German HUD (finding 116).</p>
     *
     * @param player the player who has just arrived, and whose locale has just landed
     */
    public void announceJoin(final Player player) {
        broadcast("system.join", Glyphs.ICON_JOIN, Map.of("_player", composition.of(player)),
                viewer -> true);
    }

    /**
     * {@code LOWEST}, and the priority is load-bearing rather than tidy.
     *
     * <p>{@code smp}'s {@code JoinGate#onQuit} forgets the identity at the default priority, and the
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
        // Not to the person leaving: they are on a disconnect screen, and the line would be the
        // last thing scrolled past on a chat they can no longer read.
        broadcast("system.leave", Glyphs.ICON_LEAVE, Map.of("_player", who),
                viewer -> !viewer.equals(leaving));
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        final Component vanilla = event.deathMessage();
        if (vanilla == null) {
            return;
        }
        event.deathMessage(null);
        broadcast("system.death", Glyphs.ICON_DEATH, Map.of("_death", vanilla), viewer -> true);
    }

    @EventHandler
    public void onAdvancement(final PlayerAdvancementDoneEvent event) {
        // Nullable by design in Paper: it is already null for a recipe unlock, for an advancement
        // whose display says not to announce it, and when the gamerule is off. Every one of those
        // is a decision that has already been taken, and none of them is ours to overturn.
        if (event.message() == null) {
            return;
        }
        final AdvancementDisplay display = event.getAdvancement().getDisplay();
        if (display == null) {
            return;
        }
        event.message(null);
        broadcast("system.advancement", Glyphs.ICON_ADVANCEMENT,
                Map.of("_player", composition.of(event.getPlayer()),
                        "_advancement", display.title()),
                viewer -> true);
    }

    /**
     * Renders {@code key} once per reader, in that reader's language.
     *
     * <p>Per reader rather than once: a locale is a cache lookup and these fire a handful of times
     * an hour, which is the opposite end of the scale from the boss bar's four renders a second.
     */
    private void broadcast(final String key, final String icon,
                           final Map<String, Component> components, final Predicate<Player> to) {
        final MessageRenderer renderer = MessageRenderer.of(messages);
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            if (!to.test(viewer)) {
                continue;
            }
            final Locale locale = locales.of(viewer.getUniqueId());
            viewer.sendMessage(renderer.format(locale, key, components, "icon", icon));
        }
    }

    /**
     * The reader's language, or English for an audience that is not a player.
     *
     * <p>The console is such an audience, and so is anything else that has been given a copy of
     * chat; neither has a row in {@code discord_user}, so there is nothing to look up rather than
     * something missing.
     */
    private Locale localeOf(final net.kyori.adventure.audience.Audience viewer) {
        return viewer instanceof Player player
                ? locales.of(player.getUniqueId())
                : eu.nordtal.s2.common.message.Locales.DEFAULT;
    }
}

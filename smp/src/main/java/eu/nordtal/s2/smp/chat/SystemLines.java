package eu.nordtal.s2.smp.chat;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.player.Identities;
import eu.nordtal.s2.smp.player.PlayerComposition;

import io.papermc.paper.advancement.AdvancementDisplay;
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
import java.util.function.Predicate;

/**
 * The four lines vanilla writes for everybody, written by us instead: joined, left, died, earned.
 *
 * <h2>Why replace them at all</h2>
 * Not for decoration. Vanilla broadcasts one line in <em>the server's</em> language to a server
 * where half the players read German, and it broadcasts it as a bare name with no flag, no crest
 * and no icon - so the four lines everybody reads all day are the only surface in the season that
 * looks like nothing was done to it. Each of these is sent per reader, in that reader's language,
 * with the same composition their chat line carries.
 *
 * <h2>Two of the four keep vanilla's own component, and that is the point</h2>
 * A death message and an advancement title are {@code TranslatableComponent}s: the <em>client</em>
 * renders them, so a German client reads "wurde von einem Zombie getötet" and an English one reads
 * "was slain by a Zombie", off the same packet, with the mob's name and the killer's weapon in it.
 * Fifty hand-written keys per language could not match that and would go stale on the next
 * Minecraft release. So the wording is vanilla's and the <em>line</em> is ours - which deviates from
 * what {@code docs/presentation.md} section 5 said until 2026-09-04 ("our own, and the vanilla
 * message suppressed"), and the document now says why.
 *
 * <h2>The rule that decides whether a death is announced</h2>
 * <b>We announce a death exactly when vanilla would have.</b> {@code event.deathMessage()} being
 * {@code null} already means somebody has decided this death is not news - {@code DuelListener}
 * does it for an arena death, which costs nothing and is already reported to the two people it
 * concerns, and {@code /gamerule showDeathMessages false} does it for the whole server. Reading
 * that instead of asking every subsystem in turn is what keeps this class from having to know about
 * duels, and it cannot be got wrong by an event-priority accident.
 */
public final class SystemLines implements Listener {

    private final Identities identities;
    private final PlayerComposition composition;
    private final Messages messages;
    private final PlayerLocales locales;

    public SystemLines(final Identities identities, final PlayerComposition composition,
                       final Messages messages, final PlayerLocales locales) {
        this.identities = identities;
        this.composition = composition;
        this.messages = messages;
        this.locales = locales;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        event.joinMessage(null);
        final Component who = composition(event.getPlayer());
        broadcast("smp.system.join", Glyphs.ICON_JOIN, Map.of("_player", who), viewer -> true);
    }

    /**
     * {@code LOWEST}, and the priority is load-bearing rather than tidy.
     *
     * <p>{@code JoinGate#onQuit} forgets the identity at the default priority, and the identity is
     * what carries the flag and the crest - so a handler that ran after it would announce a
     * departure with a default English flag and a tier-1 crest for everybody. The one ordering this
     * class depends on is therefore written down here rather than left to registration order.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        event.quitMessage(null);
        final Player leaving = event.getPlayer();
        final Component who = composition(leaving);
        // Not to the person leaving: they are on a disconnect screen, and the line would be the
        // last thing scrolled past on a chat they can no longer read.
        broadcast("smp.system.leave", Glyphs.ICON_LEAVE, Map.of("_player", who),
                viewer -> !viewer.equals(leaving));
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        final Component vanilla = event.deathMessage();
        if (vanilla == null) {
            return;
        }
        event.deathMessage(null);
        broadcast("smp.system.death", Glyphs.ICON_DEATH, Map.of("_death", vanilla), viewer -> true);
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
        broadcast("smp.system.advancement", Glyphs.ICON_ADVANCEMENT,
                Map.of("_player", composition(event.getPlayer()), "_advancement", display.title()),
                viewer -> true);
    }

    private Component composition(final Player player) {
        return composition.chatPrefix(player.getName(), identities.of(player.getUniqueId()));
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
}

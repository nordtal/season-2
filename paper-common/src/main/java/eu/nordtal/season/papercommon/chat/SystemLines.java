package eu.nordtal.season.papercommon.chat;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.messagerendering.GameLines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.GameContent;
import eu.nordtal.season.papercommon.player.Identities;
import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
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
 * Renders the five system lines (said, joined, left, died, earned) for each reader in their language.
 * A death without a {@code deathMessage()} is not announced.
 */
public final class SystemLines implements Listener {

    record Said(PlayerContext sender, @Example("Hello!") String message) {}

    record Who(PlayerContext player) {}

    /** {@code death} is the game's own line, so each client renders it in its reader's language. */
    record Died(@Example("death.attack.generic") GameContent death) {}

    record Earned(
            PlayerContext player,

            @Example("advancements.story.mine_diamond.title")
            GameContent advancement) {}

    static final DocumentSpec<Said> CHAT_LINE = line("chat-line", Said.class);
    static final DocumentSpec<Who> JOIN = line("join", Who.class);
    static final DocumentSpec<Who> LEAVE = line("leave", Who.class);
    /** Also the hunger games kill feed. */
    static final DocumentSpec<Died> DEATH = line("death", Died.class);

    static final DocumentSpec<Earned> ADVANCEMENT = line("advancement", Earned.class);

    private static <V extends Record> DocumentSpec<V> line(final String name, final Class<V> values) {
        return DocumentSpec.game("paper-common", name, "Chat", DocumentSpec.Shape.CHAT, values);
    }

    private final MessageRenderer renderer;
    private final Identities identities;

    public SystemLines(final MessageRenderer renderer, final Identities identities) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.identities = Objects.requireNonNull(identities, "identities");
    }

    /** Renders the chat line for each recipient: the speaker's flag, a hairline rule and the text. */
    @EventHandler(ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final PlayerContext sender = context(event.getPlayer());
        event.renderer((source, displayName, message, viewer) ->
                renderer.format(localeOf(viewer), CHAT_LINE.with(new Said(sender, GameLines.text(message)))));
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    /** Announces a join. Each module calls it from {@code languageKnown}. */
    public void announceJoin(final Player player) {
        broadcast(JOIN.with(new Who(context(player))), viewer -> true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(final PlayerQuitEvent event) {
        event.quitMessage(null);
        final Player leaving = event.getPlayer();
        broadcast(LEAVE.with(new Who(context(leaving))), viewer -> !viewer.equals(leaving));
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        final Component vanilla = event.deathMessage();
        if (vanilla == null) {
            return;
        }
        event.deathMessage(null);
        broadcast(DEATH.with(new Died(GameLines.of(vanilla))), viewer -> true);
    }

    @EventHandler
    public void onAdvancement(final PlayerAdvancementDoneEvent event) {
        if (event.message() == null) {
            return;
        }
        final AdvancementDisplay display = event.getAdvancement().getDisplay();
        if (display == null) {
            return;
        }
        event.message(null);
        broadcast(
                ADVANCEMENT.with(new Earned(context(event.getPlayer()), GameLines.of(display.title()))),
                viewer -> true);
    }

    /** Announces a system line from outside a Bukkit event, for example an offline Hunger Games kill. */
    public void announce(final Sent<?> line) {
        broadcast(line, viewer -> true);
    }

    private static PlayerContext context(final Player player) {
        return PlayerContext.of(PlayerId.of(player.getUniqueId()), player.getName());
    }

    private void broadcast(final Sent<?> line, final Predicate<Player> to) {
        broadcast(locale -> renderer.format(locale, line), to);
    }

    private void broadcast(final Function<Locale, Component> line, final Predicate<Player> to) {
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            if (!to.test(viewer)) {
                continue;
            }
            final Locale locale = identities.languageOf(viewer.getUniqueId());
            viewer.sendMessage(line.apply(locale));
        }
    }

    private Locale localeOf(final net.kyori.adventure.audience.Audience viewer) {
        return viewer instanceof Player player
                ? identities.languageOf(player.getUniqueId())
                : eu.nordtal.season.common.language.Locales.DEFAULT;
    }
}

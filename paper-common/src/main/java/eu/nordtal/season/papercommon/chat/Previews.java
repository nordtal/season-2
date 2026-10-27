package eu.nordtal.season.papercommon.chat;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Palette;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.packrendering.hud.BossBarLine.Pill;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.command.Replies;
import eu.nordtal.season.papercommon.hud.Hud;
import eu.nordtal.season.papercommon.menu.PreviewMenu;
import eu.nordtal.season.papercommon.tab.TabList;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Shows an admin's player a key's text or a drafted game document, as this server renders it.
 * A key's title, subtitle or action bar is shown there, its other places as a chat line. A document is shown whole: a
 * menu opened read-only, the tab list, a boss bar for a while, a title, an action bar, and as chat lines the rest.
 */
public final class Previews {

    /** How long a drafted boss bar stays over the player's own. */
    static final Duration BOSS_BAR = Duration.ofSeconds(10);

    private final Function<UUID, @Nullable Player> online;
    private final MessageRenderer renderer;
    private final Function<String, Palette> colours;
    private final Scheduler main;

    private final Map<UUID, List<Pill>> bars = new ConcurrentHashMap<>();

    /**
     * @param colours the palette of a service by name, as its settings name the colours
     * @param main runs on the server's main thread, where a window opens and a drafted boss bar ends
     */
    public Previews(
            final Function<UUID, @Nullable Player> online,
            final MessageRenderer renderer,
            final Function<String, Palette> colours,
            final Scheduler main) {
        this.online = Objects.requireNonNull(online, "online");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.colours = Objects.requireNonNull(colours, "colours");
        this.main = Objects.requireNonNull(main, "main");
    }

    /** Shows a key's text with the values an admin gave, the rest filled from the key's schema here. */
    public Answer show(final PlayerId player, final MessagePreview preview) {
        final Player reader = online.apply(player.value());
        if (reader == null) {
            return Answer.refused(ServerRefusal.NOT_HERE.with());
        }
        final Viewer viewer = Viewer.of(Locales.parse(preview.language()));
        // The key's own service paints the text, and that need not be this server.
        final Component text = renderer.format(
                viewer,
                renderer.raw().example(preview.key(), preview.given()),
                preview.text(),
                colours.apply(preview.service()));
        switch (preview.shown()) {
            case TITLE -> reader.showTitle(Title.title(Lines.joined(text), Component.empty()));
            case SUBTITLE -> reader.showTitle(Title.title(Component.empty(), Lines.joined(text)));
            case ACTION_BAR -> reader.sendActionBar(Lines.joined(text));
            default -> reader.sendMessage(text);
        }
        return Answer.done(Sent.of(Replies.PREVIEW_SHOWN));
    }

    /** Shows a drafted game document in {@code language}, every value at its example. */
    public Answer show(final PlayerId player, final String language, final Documents.Packaged drafted) {
        final Player reader = online.apply(player.value());
        if (reader == null) {
            return Answer.refused(ServerRefusal.NOT_HERE.with());
        }
        final Viewer viewer = Viewer.of(Locales.parse(language));
        show(reader, GameDocument.example(renderer, drafted, viewer), drafted);
        return Answer.done(Sent.of(Replies.PREVIEW_SHOWN));
    }

    /** Shows the document where its shape is, every value at its example. Steward sends a Discord one to the bot. */
    private void show(final Player reader, final GameDocument drawn, final Documents.Packaged document) {
        switch (document.declaration().shape()) {
            case MENU ->
                main.execute(() -> new PreviewMenu(drawn, document.document().parts()).open(reader));
            case TAB_LIST -> {
                // The drafted half stands beside the half the player sees now.
                if (document.declaration()
                        .id()
                        .equals(TabList.HEADER.declaration().id())) {
                    reader.sendPlayerListHeaderAndFooter(drawn.text(), orEmpty(reader.playerListFooter()));
                } else {
                    reader.sendPlayerListHeaderAndFooter(orEmpty(reader.playerListHeader()), drawn.text());
                }
            }
            case BOSS_BAR -> {
                final UUID id = reader.getUniqueId();
                final List<Pill> pills = List.of(Pill.of(drawn.plainLine()));
                bars.put(id, pills);
                main.after(BOSS_BAR, () -> bars.remove(id, pills));
            }
            case TITLE -> reader.showTitle(drawn.shownTitle(Title.DEFAULT_TIMES));
            case ACTION_BAR -> reader.sendActionBar(Lines.joined(drawn.text()));
            case CHAT, KICK_SCREEN, SERVER_LIST, HOLOGRAM -> reader.sendMessage(drawn.text());
            case MESSAGE, MODAL, PLAIN, CHANNEL_NAME ->
                throw new IllegalArgumentException(document.declaration().id() + " is a Discord document");
        }
    }

    /** The drafted boss bar the player is shown, as the line the plugin's {@link Hud} draws last. */
    public List<Pill> bossBar(final Player player, final Locale locale) {
        return bars.getOrDefault(player.getUniqueId(), List.of());
    }

    private static Component orEmpty(final @Nullable Component component) {
        return component == null ? Component.empty() : component;
    }
}

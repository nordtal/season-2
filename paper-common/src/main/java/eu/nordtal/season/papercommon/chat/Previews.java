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
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.packrendering.ui.Pill;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.command.Replies;
import eu.nordtal.season.papercommon.hud.Hud;
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
 * A menu opens read-only at its example state; tab lists, boss bars, titles, action bars and chat lines draw in place.
 */
public final class Previews {

    /**
     * Opens a menu at its example state in a drafted document's words, read-only.
     * No click changes anything but the page the menu shows.
     */
    @FunctionalInterface
    public interface Example {

        /** Opens the menu for {@code reader} on the main thread, its words from {@code drafted}. */
        void open(Player reader, GameDocument drafted);
    }

    /** How long a drafted boss bar stays over the player's own. */
    static final Duration BOSS_BAR = Duration.ofSeconds(10);

    private final Function<UUID, @Nullable Player> online;
    private final MessageRenderer renderer;
    private final Function<String, Palette> colours;
    private final Scheduler main;

    private final Map<UUID, List<Pill.Content>> bars = new ConcurrentHashMap<>();
    private final Map<String, Example> examples = new ConcurrentHashMap<>();

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

    /** Opens {@code menu}'s drafts with {@code example}. A drafted menu without one is refused on this server. */
    public void example(final DocumentSpec<?> menu, final Example example) {
        if (menu.declaration().shape() != DocumentSpec.Shape.MENU) {
            throw new IllegalArgumentException(menu.declaration().id() + " is no menu");
        }
        if (examples.putIfAbsent(menu.declaration().id(), Objects.requireNonNull(example, "example")) != null) {
            throw new IllegalStateException(menu.declaration().id() + " has an example already");
        }
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
        final Example example = examples.get(drafted.declaration().id());
        if (drafted.declaration().shape() == DocumentSpec.Shape.MENU && example == null) {
            return Answer.refused(ServerRefusal.NO_EXAMPLE.with());
        }
        final GameDocument drawn = GameDocument.example(renderer, drafted, Viewer.of(Locales.parse(language)));
        if (example != null) {
            main.execute(() -> example.open(reader, drawn));
        } else {
            show(reader, drawn, drafted);
        }
        return Answer.done(Sent.of(Replies.PREVIEW_SHOWN));
    }

    /** Shows a text document where its shape is, every value at its example. Steward sends a Discord one to the bot. */
    private void show(final Player reader, final GameDocument drawn, final Documents.Packaged document) {
        switch (document.declaration().shape()) {
            case MENU -> throw new IllegalStateException(document.declaration().id() + " opens through its example");
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
                final List<Pill.Content> pills = List.of(Pill.Content.of(drawn.plainLine()));
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
    public List<Pill.Content> bossBar(final Player player, final Locale locale) {
        return bars.getOrDefault(player.getUniqueId(), List.of());
    }

    private static Component orEmpty(final @Nullable Component component) {
        return component == null ? Component.empty() : component;
    }
}

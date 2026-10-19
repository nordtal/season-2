package eu.nordtal.season.papercommon.chat;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.messagerendering.Lines;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messagerendering.ToneColours;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Palette;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.packrendering.hud.BossBarLine;
import eu.nordtal.season.papercommon.command.Answer;
import eu.nordtal.season.papercommon.command.Replies;
import eu.nordtal.season.papercommon.menu.PreviewMenu;
import eu.nordtal.season.papercommon.tab.TabList;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.bossbar.BossBar;
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
    private final Scheduler main;

    /** @param main runs on the server's main thread, where a window opens and a boss bar is taken down */
    public Previews(
            final Function<UUID, @Nullable Player> online, final MessageRenderer renderer, final Scheduler main) {
        this.online = Objects.requireNonNull(online, "online");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.main = Objects.requireNonNull(main, "main");
    }

    public Answer show(final PlayerId player, final MessagePreview preview) {
        final Player reader = online.apply(player.value());
        if (reader == null) {
            return Answer.refused(ServerRefusal.NOT_HERE.with());
        }
        final Viewer viewer = Viewer.of(Locales.parse(preview.language()));
        final Documents.Packaged document = preview.document();
        if (document != null) {
            show(reader, GameDocument.example(renderer, document, viewer), document);
            return Answer.done(Sent.of(Replies.PREVIEW_SHOWN));
        }
        final MessageRef message = Objects.requireNonNull(preview.message(), "message");
        final String drafted = Objects.requireNonNull(preview.text(), "text");
        // The key's own service paints the text, and that need not be this server.
        final Component text = preview.colours().isEmpty()
                ? renderer.format(viewer, message, drafted)
                : renderer.format(viewer, message, drafted, paletteOf(preview.colours()));
        switch (preview.shown()) {
            case TITLE -> reader.showTitle(Title.title(Lines.joined(text), Component.empty()));
            case SUBTITLE -> reader.showTitle(Title.title(Component.empty(), Lines.joined(text)));
            case ACTION_BAR -> reader.sendActionBar(Lines.joined(text));
            default -> reader.sendMessage(text);
        }
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
                final BossBar bar = BossBarLine.bar();
                BossBarLine.show(bar, List.of(BossBarLine.Pill.of(drawn.plainLine())));
                reader.showBossBar(bar);
                main.after(BOSS_BAR, () -> reader.hideBossBar(bar));
            }
            case TITLE -> reader.showTitle(drawn.shownTitle(Title.DEFAULT_TIMES));
            case ACTION_BAR -> reader.sendActionBar(Lines.joined(drawn.text()));
            case CHAT, KICK_SCREEN, SERVER_LIST, HOLOGRAM -> reader.sendMessage(drawn.text());
            case MESSAGE, MODAL, PLAIN, CHANNEL_NAME ->
                throw new IllegalArgumentException(document.declaration().id() + " is a Discord document");
        }
    }

    private static Component orEmpty(final @Nullable Component component) {
        return component == null ? Component.empty() : component;
    }

    /** Returns the tones as a palette. A missing or unreadable tone keeps its default. */
    private static Palette paletteOf(final Map<String, String> colours) {
        final Map<Tone, String> declared = new EnumMap<>(Tone.class);
        for (final Tone tone : Tone.values()) {
            final String hex = colours.get(tone.tag());
            if (hex != null) {
                declared.put(tone, hex);
            }
        }
        return ToneColours.parse(declared, unreadable -> {});
    }
}

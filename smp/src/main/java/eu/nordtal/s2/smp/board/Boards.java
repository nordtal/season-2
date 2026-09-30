package eu.nordtal.s2.smp.board;

import static eu.nordtal.s2.smp.SmpMessages.MESSAGES;

import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.PlayerLocales;
import eu.nordtal.s2.messages.context.MilestoneContext;
import eu.nordtal.s2.messages.context.PlayerContext;
import eu.nordtal.s2.smp.SmpMessages;
import eu.nordtal.s2.smp.config.BoardSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.AuraRow;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.milestone.MilestoneNames;
import eu.nordtal.s2.smp.state.SeasonState;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * The two boards at the spawn, rendered <b>per player, in their own language</b>.
 *
 * A Text Display shows one text to everyone, so each board is one non-persistent entity per viewer.
 */
public final class Boards {

    /** Once every five seconds; the numbers behind a board change a few times an hour. */
    private static final long REFRESH_TICKS = 100L;

    private static final int BAR_WIDTH = 20;

    /** Wide enough that a board never wraps, yet an ordinary varint on the client, unlike {@code Integer.MAX_VALUE}. */
    private static final int NO_WRAPPING = 10_000;

    private static final int LEADERBOARD_SIZE = 10;

    private final Plugin plugin;
    private final SmpSpec config;
    private final SeasonState season;
    private final Messages messages;
    private final PlayerLocales locales;

    private final Map<UUID, Map<BoardKind, TextDisplay>> displays = new HashMap<>();

    private volatile List<AuraRow> leaderboard = List.of();
    private final Map<UUID, String> namesByUuid = new HashMap<>();
    private @Nullable BukkitTask task;

    public Boards(
            final Plugin plugin,
            final SmpSpec config,
            final SeasonState season,
            final Messages messages,
            final PlayerLocales locales) {
        this.plugin = plugin;
        this.config = config;
        this.season = season;
        this.messages = messages;
        this.locales = locales;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::renderAll, REFRESH_TICKS, REFRESH_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        displays.values().forEach(byKind -> byKind.values().forEach(TextDisplay::remove));
        displays.clear();
    }

    public void setLeaderboard(final List<AuraRow> rows) {
        this.leaderboard = List.copyOf(rows);
    }

    public void forget(final Player player) {
        final Map<BoardKind, TextDisplay> byKind = displays.remove(player.getUniqueId());
        if (byKind != null) {
            byKind.values().forEach(TextDisplay::remove);
        }
    }

    private void renderAll() {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            for (final BoardSpec spec : config.boards()) {
                final Optional<BoardKind> kind = BoardKind.parse(spec.kind());
                if (kind.isEmpty()) {
                    continue;
                }
                render(player, spec, kind.get());
            }
        }
    }

    private void render(final Player player, final BoardSpec spec, final BoardKind kind) {
        final World world = Bukkit.getWorld(spec.world());
        if (world == null) {
            return;
        }
        // A board is at the spawn, so only people in that world could see it.
        if (!player.getWorld().equals(world)) {
            return;
        }

        final Location at = new Location(world, spec.x(), spec.y(), spec.z(), spec.yaw(), 0f);
        final TextDisplay display = displays.computeIfAbsent(
                        player.getUniqueId(), key -> new EnumMap<>(BoardKind.class))
                .computeIfAbsent(kind, key -> spawn(player, at));

        display.text(text(kind, locales.of(player.getUniqueId()), spec.width()));
    }

    private TextDisplay spawn(final Player owner, final Location at) {
        final TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, entity -> {
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setSeeThrough(false);
            entity.setPersistent(false);
            entity.setViewRange(1.0f);
            // Every line starts at the same x.
            entity.setAlignment(TextDisplay.TextAlignment.LEFT);
            // A wrapped continuation carries no frame; see {@link BoardFrame}.
            entity.setLineWidth(NO_WRAPPING);
        });
        // Hidden from everybody, then shown to its owner: visible for a tick means seen in the wrong language.
        for (final Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(owner)) {
                other.hideEntity(plugin, display);
            }
        }
        return display;
    }

    private Component text(final BoardKind kind, final Locale locale, final int width) {
        return switch (kind) {
            case OBJECTIVE -> objectiveText(locale, width);
            case AURA -> auraText(locale, width);
        };
    }

    // Names travel as parameters so {@code MessageRenderer} escapes them.
    private Component objectiveText(final Locale locale, final int width) {
        final MessageRenderer renderer = MessageRenderer.of(messages);
        final Component title =
                renderer.format(locale, MESSAGES.smp().board().objective().title());
        final List<Component> lines = new ArrayList<>();

        // One read, so the name and the rows are the same milestone's.
        final SeasonState.Active active = season.active();
        if (active.unread()) {
            // Before the first refresh, the title alone rather than "finished".
            return BoardFrame.render(width, title, lines);
        }
        if (active.key() == null) {
            lines.add(renderer.format(locale, MESSAGES.smp().board().objective().finished()));
            return BoardFrame.render(width, title, lines);
        }

        lines.add(renderer.format(
                locale,
                MESSAGES.smp()
                        .board()
                        .objective()
                        .milestone(new MilestoneContext(milestoneName(active.key(), locale)))));
        for (final ObjectiveRow objective : active.objectives()) {
            lines.add(renderer.format(locale, row(objective.key(), objective)));
        }
        return BoardFrame.render(width, title, lines);
    }

    private Component auraText(final Locale locale, final int width) {
        final MessageRenderer renderer = MessageRenderer.of(messages);
        final Component title =
                renderer.format(locale, MESSAGES.smp().board().aura().title());
        final List<Component> lines = new ArrayList<>();

        final List<AuraRow> rows = leaderboard;
        if (rows.isEmpty()) {
            lines.add(renderer.format(locale, MESSAGES.smp().board().aura().empty()));
            return BoardFrame.render(width, title, lines);
        }

        int place = 1;
        for (final AuraRow row : rows.subList(0, Math.min(LEADERBOARD_SIZE, rows.size()))) {
            lines.add(renderer.format(
                    locale,
                    row.aura() > 0
                            ? MESSAGES.smp()
                                    .board()
                                    .aura()
                                    .row(place, new PlayerContext(nameOf(row.mcUuid())), row.aura())
                            : MESSAGES.smp()
                                    .board()
                                    .aura()
                                    .rowZero(place, new PlayerContext(nameOf(row.mcUuid())), row.aura())));
            place++;
        }
        return BoardFrame.render(width, title, lines);
    }

    /** Returns a player's Minecraft name, remembered for the session, since this repository stores none. */
    private String nameOf(final UUID uuid) {
        return namesByUuid.computeIfAbsent(uuid, key -> {
            final String name = Bukkit.getOfflinePlayer(key).getName();
            return name == null ? key.toString().substring(0, 8) : name;
        });
    }

    private String milestoneName(final String key, final Locale locale) {
        return MilestoneNames.of(messages, locale, key);
    }

    private static MessageRef row(final String name, final ObjectiveRow objective) {
        final String bar = ProgressBar.of(objective.ratio(), BAR_WIDTH);
        final SmpMessages.Smp.Board.Objective lines = MESSAGES.smp().board().objective();
        return objective.completed()
                ? lines.rowDone(name, bar, objective.amount(), objective.target())
                : lines.row(name, bar, objective.amount(), objective.target());
    }
}

package eu.nordtal.season.smp.board;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.packrendering.board.BoardFrame;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.smp.aura.AuraRow;
import eu.nordtal.season.smp.config.BoardSpec;
import eu.nordtal.season.smp.config.BoardsSpec;
import eu.nordtal.season.smp.milestone.MilestoneNames;
import eu.nordtal.season.smp.milestone.ObjectiveRow;
import eu.nordtal.season.smp.state.SeasonState;
import java.time.Duration;
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

/**
 * Shows the two boards at the spawn to each player in the language of that player.
 * A Text Display shows one text to everyone, so each board is one non-persistent entity per viewer.
 */
public final class Boards {

    public static final Duration REFRESH = Duration.ofSeconds(5);

    private static final int BAR_WIDTH = 20;

    /** The line width that prevents wrapping. It encodes as an ordinary varint, unlike {@code Integer.MAX_VALUE}. */
    private static final int NO_WRAPPING = 10_000;

    private static final int LEADERBOARD_SIZE = 10;

    record Milestone(MilestoneContext milestone) {}

    /** {@code bar} is the progress bar's text; a {@code done} row reads as finished. */
    record Row(
            @Example("Mine 64 diamonds") String objective,
            @Example("████████████░░░░░░░░") String bar,
            @Example("40") long amount,
            @Example("64") long target,
            boolean done) {}

    /** A player without aura, or below zero, is shown fainter. */
    record AuraLine(
            @Example("1") long place,
            PlayerContext player,
            @Example("4200") long aura) {}

    static final DocumentSpec<DocumentSpec.None> OBJECTIVE_TITLE = board("board-objective-title");
    static final DocumentSpec<DocumentSpec.None> OBJECTIVE_FINISHED = board("board-objective-finished");
    static final DocumentSpec<Milestone> OBJECTIVE_MILESTONE = board("board-objective-milestone", Milestone.class);
    static final DocumentSpec<Row> OBJECTIVE_ROW = board("board-objective-row", Row.class);
    static final DocumentSpec<DocumentSpec.None> AURA_TITLE = board("board-aura-title");
    static final DocumentSpec<DocumentSpec.None> AURA_EMPTY = board("board-aura-empty");
    static final DocumentSpec<AuraLine> AURA_ROW = board("board-aura-row", AuraLine.class);

    private static DocumentSpec<DocumentSpec.None> board(final String name) {
        return DocumentSpec.game("smp", name, "Boards", DocumentSpec.Shape.HOLOGRAM);
    }

    private static <V extends Record> DocumentSpec<V> board(final String name, final Class<V> values) {
        return DocumentSpec.game("smp", name, "Boards", DocumentSpec.Shape.HOLOGRAM, values);
    }

    private final Plugin plugin;
    private final BoardsSpec config;
    private final SeasonState season;
    private final MessageRenderer renderer;
    private final Identities identities;

    private final Map<UUID, Map<BoardKind, TextDisplay>> displays = new HashMap<>();

    private volatile List<AuraRow> leaderboard = List.of();
    private final Map<UUID, String> namesByUuid = new HashMap<>();

    public Boards(
            final Plugin plugin,
            final BoardsSpec config,
            final SeasonState season,
            final MessageRenderer renderer,
            final Identities identities) {
        this.plugin = plugin;
        this.config = config;
        this.season = season;
        this.renderer = renderer;
        this.identities = identities;
    }

    public void stop() {
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

    /** Draws both boards for each player in their world. Main thread only. */
    public void renderAll() {
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
        if (!player.getWorld().equals(world)) {
            return;
        }

        final Location at = new Location(world, spec.x(), spec.y(), spec.z(), spec.yaw(), 0f);
        final TextDisplay display = displays.computeIfAbsent(
                        player.getUniqueId(), key -> new EnumMap<>(BoardKind.class))
                .computeIfAbsent(kind, key -> spawn(player, at));

        display.text(text(kind, identities.languageOf(player.getUniqueId()), spec.width()));
    }

    private TextDisplay spawn(final Player owner, final Location at) {
        final TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, entity -> {
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setSeeThrough(false);
            entity.setPersistent(false);
            entity.setViewRange(1.0f);
            entity.setAlignment(TextDisplay.TextAlignment.LEFT);
            // A wrapped line would lack the frame, see BoardFrame.
            entity.setLineWidth(NO_WRAPPING);
        });
        // The board is hidden from everybody and then shown to its owner, so nobody sees it in the wrong language.
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

    // Names are message parameters, so MessageRenderer escapes them.
    private Component objectiveText(final Locale locale, final int width) {
        final Component title = renderer.format(locale, Sent.of(OBJECTIVE_TITLE));
        final List<Component> lines = new ArrayList<>();

        // A single read keeps the name and the rows of one milestone together.
        final SeasonState.Active active = season.active();
        if (active.unread()) {
            return BoardFrame.render(width, title, lines);
        }
        if (active.key() == null) {
            lines.add(renderer.format(locale, Sent.of(OBJECTIVE_FINISHED)));
            return BoardFrame.render(width, title, lines);
        }

        lines.add(renderer.format(
                locale,
                OBJECTIVE_MILESTONE.with(
                        new Milestone(new MilestoneContext(MilestoneNames.of(renderer, locale, active.key()))))));
        for (final ObjectiveRow objective : active.objectives()) {
            lines.add(renderer.format(locale, row(objective.key(), objective)));
        }
        return BoardFrame.render(width, title, lines);
    }

    private Component auraText(final Locale locale, final int width) {
        final Component title = renderer.format(locale, Sent.of(AURA_TITLE));
        final List<Component> lines = new ArrayList<>();

        final List<AuraRow> rows = leaderboard;
        if (rows.isEmpty()) {
            lines.add(renderer.format(locale, Sent.of(AURA_EMPTY)));
            return BoardFrame.render(width, title, lines);
        }

        int place = 1;
        for (final AuraRow row : rows.subList(0, Math.min(LEADERBOARD_SIZE, rows.size()))) {
            lines.add(renderer.format(
                    locale,
                    AURA_ROW.with(new AuraLine(
                            place, PlayerContext.of(PlayerId.of(row.mcUuid()), nameOf(row.mcUuid())), row.aura()))));
            place++;
        }
        return BoardFrame.render(width, title, lines);
    }

    private String nameOf(final UUID uuid) {
        return namesByUuid.computeIfAbsent(uuid, key -> {
            final String name = Bukkit.getOfflinePlayer(key).getName();
            return name == null ? key.toString().substring(0, 8) : name;
        });
    }

    private static Sent<Row> row(final String name, final ObjectiveRow objective) {
        final String bar = ProgressBar.of(objective.ratio(), BAR_WIDTH);
        return OBJECTIVE_ROW.with(new Row(name, bar, objective.amount(), objective.target(), objective.completed()));
    }
}

package eu.nordtal.s2.smp.board;

import eu.nordtal.s2.common.hud.BoardFrame;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.db.AuraRow;
import eu.nordtal.s2.smp.db.ObjectiveRow;
import eu.nordtal.s2.smp.state.SeasonState;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The two boards at the spawn, rendered <b>per player, in their own language</b>.
 *
 * <h2>Why one entity per viewer</h2>
 * A Text Display carries one piece of text for everyone who can see it. Two people standing side by
 * side reading the same board in two languages is therefore not something one entity can do, and
 * the whole point of this server's i18n is that it never asks anybody to read the other language.
 * So each board is spawned once per viewer and hidden from everyone else with
 * {@link Player#hideEntity}.
 *
 * <p>With a handful of players that is a handful of entities. It is emphatically not a technique
 * that would scale to a hundred, and it does not have to - this is a small community server, and
 * saying so out loud is cheaper than discovering the limit later.
 *
 * <p>The displays are spawned with {@code setPersistent(false)} so a crash cannot leave them in the
 * world, and every one belonging to this plugin is swept at start anyway.
 *
 * <h2>The frame</h2>
 * Built 2026-09-04. {@code nordtal:board} had been fully drawn since 2026-08-31 - corners, edges,
 * dividers, twenty-eight code points - and used by nothing at all; this class wrote plain text onto
 * a Text Display and never named one of them. {@link BoardFrame} owns the composition and the
 * reason the board's width is configuration rather than a measurement.
 */
public final class Boards {

    /** Once every five seconds. The numbers behind a board change a few times an hour. */
    private static final long REFRESH_TICKS = 100L;

    private static final int BAR_WIDTH = 20;

    /**
     * Wide enough that a board never wraps.
     *
     * <p>Not {@code Integer.MAX_VALUE}: Minecraft carries this to the client and a wrap width is
     * an ordinary varint there, so a number nobody would ever reach is safer than the largest one
     * that exists.
     */
    private static final int NO_WRAPPING = 10_000;
    private static final int LEADERBOARD_SIZE = 10;

    private final Plugin plugin;
    private final SmpSpec config;
    private final SeasonState season;
    private final Messages messages;
    private final PlayerLocales locales;

    /** viewer -> board kind -> their own display. */
    private final Map<UUID, Map<BoardKind, TextDisplay>> displays = new HashMap<>();
    private volatile List<AuraRow> leaderboard = List.of();
    private final Map<UUID, String> namesByUuid = new HashMap<>();
    private BukkitTask task;

    public Boards(final Plugin plugin, final SmpSpec config, final SeasonState season,
                  final Messages messages, final PlayerLocales locales) {
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

    /** Hands the board the leaderboard rows an async task has just read. */
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
            for (final SmpSpec.BoardSpec spec : config.boards()) {
                final Optional<BoardKind> kind = BoardKind.parse(spec.kind());
                if (kind.isEmpty()) {
                    continue;
                }
                render(player, spec, kind.get());
            }
        }
    }

    private void render(final Player player, final SmpSpec.BoardSpec spec, final BoardKind kind) {
        final World world = Bukkit.getWorld(spec.world());
        if (world == null) {
            return;
        }
        // Only draw for people who could possibly see it. A board is at the spawn; somebody in the
        // Nether has no use for an entity there, and spawning one per viewer per board for the
        // whole server is exactly the cost this technique has to keep small.
        if (!player.getWorld().equals(world)) {
            return;
        }

        final Location at = new Location(world, spec.x(), spec.y(), spec.z(), spec.yaw(), 0f);
        final TextDisplay display = displays
                .computeIfAbsent(player.getUniqueId(), key -> new EnumMap<>(BoardKind.class))
                .computeIfAbsent(kind, key -> spawn(player, at));

        display.text(text(kind, locales.of(player.getUniqueId()), spec.width()));
    }

    private TextDisplay spawn(final Player owner, final Location at) {
        final TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, entity -> {
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setSeeThrough(false);
            entity.setPersistent(false);
            entity.setViewRange(1.0f);
            // The frame is drawn per line and every line starts at the same x, so the display has
            // to be left-aligned - centring would move each line by half its own width and take
            // the vertical edges with it.
            entity.setAlignment(TextDisplay.TextAlignment.LEFT);
            // And nothing may wrap. A wrapped line's continuation carries no frame at all, so it
            // lands outside the box; an over-long line running past the right edge is the same
            // information and looks like what it is. BoardFrame says why the width cannot simply
            // be computed from the content.
            entity.setLineWidth(NO_WRAPPING);
        });
        // Hidden from everybody, then shown to its one owner - the order matters, because a display
        // that is visible for a tick is a display somebody sees in the wrong language.
        for (final Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(owner)) {
                other.hideEntity(plugin, display);
            }
        }
        return display;
    }

    // ------------------------------------------------------------------ the text

    private Component text(final BoardKind kind, final Locale locale, final int width) {
        return switch (kind) {
            case OBJECTIVE -> objectiveText(locale, width);
            case AURA -> auraText(locale, width);
        };
    }

    private Component objectiveText(final Locale locale, final int width) {
        final Component title = MessageRenderer.of(messages)
                .get(locale, BoardKind.OBJECTIVE.messageKey()).color(NamedTextColor.GOLD);
        final List<Component> lines = new ArrayList<>();

        final Optional<String> active = season.activeKey();
        if (active.isEmpty()) {
            lines.add(MessageRenderer.of(messages).get(locale, "smp.board.objective.finished")
                    .color(NamedTextColor.GRAY));
            return BoardFrame.render(width, title, lines);
        }

        lines.add(Component.text(milestoneName(active.get(), locale)).color(NamedTextColor.WHITE));
        for (final ObjectiveRow objective : season.activeObjectives()) {
            final String name = objectiveName(active.get(), objective.key(), locale);
            lines.add(Component.text(name + "  " + ProgressBar.of(objective.ratio(), BAR_WIDTH)
                            + "  " + objective.amount() + "/" + objective.target())
                    .color(objective.completed() ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        }
        return BoardFrame.render(width, title, lines);
    }

    private Component auraText(final Locale locale, final int width) {
        final Component title = MessageRenderer.of(messages)
                .get(locale, BoardKind.AURA.messageKey()).color(NamedTextColor.GOLD);
        final List<Component> lines = new ArrayList<>();

        final List<AuraRow> rows = leaderboard;
        if (rows.isEmpty()) {
            lines.add(MessageRenderer.of(messages).get(locale, "smp.board.aura.empty")
                    .color(NamedTextColor.GRAY));
            return BoardFrame.render(width, title, lines);
        }

        int place = 1;
        for (final AuraRow row : rows.subList(0, Math.min(LEADERBOARD_SIZE, rows.size()))) {
            lines.add(Component.text(place + ". " + nameOf(row.mcUuid()) + "  " + row.aura())
                    .color(row.aura() > 0 ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY));
            place++;
        }
        return BoardFrame.render(width, title, lines);
    }

    /**
     * A Minecraft name for a UUID.
     *
     * <p>This repository stores no Minecraft names - they are the server's to know and the player's
     * to change - so they are resolved here and remembered for the session. {@code getOfflinePlayer}
     * does not hit the network for a UUID the server has seen before, which every player on this
     * board has been.
     */
    private String nameOf(final UUID uuid) {
        return namesByUuid.computeIfAbsent(uuid, key -> {
            final String name = Bukkit.getOfflinePlayer(key).getName();
            return name == null ? key.toString().substring(0, 8) : name;
        });
    }

    private String milestoneName(final String key, final Locale locale) {
        final String messageKey = "smp.milestone." + key;
        return messages.hasTranslation(locale, messageKey) ? messages.get(locale, messageKey) : key;
    }

    private String objectiveName(final String milestone, final String objective, final Locale locale) {
        final String messageKey = "smp.objective." + milestone + "." + objective;
        return messages.hasTranslation(locale, messageKey) ? messages.get(locale, messageKey) : objective;
    }
}

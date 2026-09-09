package eu.nordtal.s2.hungergames.hud;

import eu.nordtal.s2.common.hud.Bearing;
import eu.nordtal.s2.common.hud.BossBarLine;
import eu.nordtal.s2.common.hud.BossBarLine.Pill;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.game.GameState;
import eu.nordtal.s2.hungergames.game.WinTracker;
import eu.nordtal.s2.hungergames.loot.LootRefill;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The three-line HUD: players (alive/dead plus an arrow to the nearest living player), loot
 * (countdown plus a direction), border (shrink status). Three {@link BossBar} instances per player,
 * redrawn a few times a second.
 *
 * <p>The resource pack makes the vanilla bar itself invisible, so this class only decides what each
 * line says. Each line is one {@link BossBarLine} pill: icon, text, and the bearing arrow riding at
 * the end of the same pill.</p>
 */
public final class HudRenderer {

    private static final int UPDATES_PER_SECOND = 4;

    private final Plugin plugin;
    private final World world;
    private final HungerGamesSpec config;
    private final Messages messages;
    private final PlayerLocales locales;
    private final BorderController border;
    private final GameState state;

    private final Map<UUID, BossBar> playersBars = new HashMap<>();
    private final Map<UUID, BossBar> lootBars = new HashMap<>();
    private final Map<UUID, BossBar> borderBars = new HashMap<>();

    private BukkitTask task;

    /**
     * The living count is read at render time, never pushed in: a push depends on somebody
     * remembering to call it, while reading the tracker four times a second cannot go stale.
     */
    private final WinTracker wins;

    /** Read on every redraw, for the reason given on {@link #wins}. */
    private final LootRefill loot;

    public HudRenderer(final Plugin plugin, final World world, final HungerGamesSpec config,
                       final Messages messages, final PlayerLocales locales, final BorderController border,
                       final GameState state, final WinTracker wins, final LootRefill loot) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
        this.border = border;
        this.state = state;
        this.wins = wins;
        this.loot = loot;
    }

    public void start() {
        final long period = 20L / UPDATES_PER_SECOND;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::renderAll, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (final Player player : world.getPlayers()) {
            hide(player);
        }
        playersBars.clear();
        lootBars.clear();
        borderBars.clear();
    }

    public void hide(final Player player) {
        final UUID uuid = player.getUniqueId();
        removeIfPresent(playersBars, player, uuid);
        removeIfPresent(lootBars, player, uuid);
        removeIfPresent(borderBars, player, uuid);
    }

    private void removeIfPresent(final Map<UUID, BossBar> bars, final Player player, final UUID uuid) {
        final BossBar bar = bars.remove(uuid);
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    private void renderAll() {
        for (final Player player : world.getPlayers()) {
            renderFor(player);
        }
    }

    private void renderFor(final Player player) {
        final java.util.Locale locale = locales.of(player.getUniqueId());
        final BossBar playersBar = playersBars.computeIfAbsent(player.getUniqueId(),
                key -> BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS));
        final BossBar lootBar = lootBars.computeIfAbsent(player.getUniqueId(),
                key -> BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS));
        final BossBar borderBar = borderBars.computeIfAbsent(player.getUniqueId(),
                key -> BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS));

        playersBar.name(BossBarLine.render(List.of(Pill.of(Glyphs.BOSSBAR_ICON_ALIVE,
                withArrow(messages.format(locale, "hg.hud.players",
                                "alive", wins.aliveCount(),
                                "dead", wins.deadCount(state.effectiveParticipants())),
                        nearestPlayerArrow(player))))));

        lootBar.name(BossBarLine.render(List.of(Pill.of(Glyphs.BOSSBAR_ICON_LOOT_POINT,
                withArrow(lootLine(locale), nearestLootArrow(player))))));

        borderBar.name(BossBarLine.render(List.of(Pill.of(Glyphs.BOSSBAR_ICON_BORDER, borderLine(locale)))));

        player.showBossBar(playersBar);
        player.showBossBar(lootBar);
        player.showBossBar(borderBar);
    }

    /** The arrow rides at the end of its text's pill - or nothing does, when there is no target. */
    private static String withArrow(final String text, final String arrow) {
        return arrow.isEmpty() ? text : text + BossBarLine.ICON_GAP + arrow;
    }

    private String lootLine(final java.util.Locale locale) {
        final Instant nextRefillAt = loot.nextRefillAt();
        if (nextRefillAt == null) {
            return messages.get(locale, "hg.hud.loot-none");
        }
        final long secondsLeft = Math.max(0, Duration.between(Instant.now(), nextRefillAt).toSeconds());
        return messages.format(locale, "hg.hud.loot", "time", formatDuration(secondsLeft));
    }

    private String borderLine(final java.util.Locale locale) {
        if (!state.isShrinking()) {
            return messages.get(locale, "hg.hud.border-stable");
        }
        final long secondsLeft = state.shrinkEndsAt() == null
                ? 0 : Math.max(0, Duration.between(Instant.now(), state.shrinkEndsAt()).toSeconds());
        final double distance = Math.max(0, (border.currentSize() - state.shrinkTarget()) / 2.0);
        return messages.format(locale, "hg.hud.border-shrinking",
                "time", formatDuration(secondsLeft), "distance", String.valueOf(Math.round(distance)));
    }

    private String nearestPlayerArrow(final Player player) {
        Player nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (final Player other : world.getPlayers()) {
            if (other.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            final double distanceSquared = other.getLocation().distanceSquared(player.getLocation());
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearest = other;
            }
        }
        if (nearest == null) {
            return "";
        }
        final int index = Bearing.arrowIndex(player.getLocation().getX(), player.getLocation().getZ(),
                player.getLocation().getYaw(), nearest.getLocation().getX(), nearest.getLocation().getZ());
        return Glyphs.BOSSBAR_ARROWS[index];
    }

    private String nearestLootArrow(final Player player) {
        HungerGamesSpec.LootPointSpec nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (final HungerGamesSpec.LootPointSpec point : config.lootPoints()) {
            final Location location = new Location(world, point.x(), point.y(), point.z());
            if (!border.isInside(location)) {
                continue;
            }
            final double dx = point.x() - player.getLocation().getX();
            final double dz = point.z() - player.getLocation().getZ();
            final double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearest = point;
            }
        }
        if (nearest == null) {
            return "";
        }
        final int index = Bearing.arrowIndex(player.getLocation().getX(), player.getLocation().getZ(),
                player.getLocation().getYaw(), nearest.x(), nearest.z());
        return Glyphs.BOSSBAR_ARROWS[index];
    }

    private static String formatDuration(final long totalSeconds) {
        final long minutes = totalSeconds / 60;
        final long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }
}

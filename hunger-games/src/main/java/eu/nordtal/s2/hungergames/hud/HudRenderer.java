package eu.nordtal.s2.hungergames.hud;

import eu.nordtal.s2.common.hud.Bearing;
import eu.nordtal.s2.common.hud.BossBarWidth;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.game.GameState;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The three-line HUD, per docs/hunger-games.md#the-hud: players (alive/dead + arrow to nearest
 * living player), loot (countdown + direction to nearest point), border (shrink status). A stack of
 * three {@link BossBar} instances shown simultaneously per player, updated a few times a second.
 * <p>
 * The vanilla bar itself is made invisible by the resource pack (white_background.png /
 * white_progress.png overridden - docs/hunger-games.md#the-hud); this class only has to compose the
 * text, using {@code Glyphs.BOSSBAR_BG_*}/{@code BOSSBAR_SPACE_*} for the background per
 * {@link BossBarWidth}.
 * </p>
 */
public final class HudRenderer {

    private static final int UPDATES_PER_SECOND = 4;
    private static final int BACKGROUND_WIDTH = 182;

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
    private volatile Instant nextRefillAt;
    private volatile int aliveCount;
    private volatile int deadCount;

    public HudRenderer(final Plugin plugin, final World world, final HungerGamesSpec config,
                       final Messages messages, final PlayerLocales locales, final BorderController border,
                       final GameState state) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
        this.border = border;
        this.state = state;
    }

    public void setCounts(final int alive, final int dead) {
        this.aliveCount = alive;
        this.deadCount = dead;
    }

    public void setNextRefillAt(final Instant instant) {
        this.nextRefillAt = instant;
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

        playersBar.name(bossBarText(BossBarWidth.compose(BACKGROUND_WIDTH) + " "
                + Glyphs.BOSSBAR_ICON_ALIVE + " " + messages.format(locale, "hg.hud.players",
                "alive", aliveCount, "dead", deadCount) + " " + nearestPlayerArrow(player)));

        lootBar.name(bossBarText(BossBarWidth.compose(BACKGROUND_WIDTH) + " "
                + Glyphs.BOSSBAR_ICON_LOOT_POINT + " " + lootLine(locale) + " " + nearestLootArrow(player)));

        borderBar.name(bossBarText(BossBarWidth.compose(BACKGROUND_WIDTH) + " "
                + Glyphs.BOSSBAR_ICON_BORDER + " " + borderLine(locale)));

        player.showBossBar(playersBar);
        player.showBossBar(lootBar);
        player.showBossBar(borderBar);
    }

    private String lootLine(final java.util.Locale locale) {
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

    /**
     * Wraps a composed HUD line in a component that names {@link Glyphs#FONT_BOSSBAR}.
     *
     * <p>Without it the bar's code points resolve against {@code minecraft:default}, where they are
     * undefined or defined as something else - see the note on {@link Glyphs#FONT_BOSSBAR}. The
     * readable text rides in the same component on purpose: the bossbar font carries its own ascii
     * provider.</p>
     *
     * <p><b>And it carries no shadow.</b> Vanilla draws every glyph a second time, one pixel down
     * and right; on a background composed of power-of-two tiles butted against each other that
     * second copy bleeds out of each tile into the next, so the bar the pack draws as one surface
     * arrives with a dark seam at every segment boundary. The shadow costs no advance, so
     * {@link BossBarWidth}'s arithmetic is untouched and nothing moves - the bar only looks wrong,
     * which is why reading the composition never finds it. The whole line is one component, so the
     * readable text loses its shadow too; that is the deliberate trade for keeping the line
     * un-split (owner's call, 2026-09-05).</p>
     */
    private static Component bossBarText(final String line) {
        return Component.text(line)
                .font(Key.key(Glyphs.FONT_BOSSBAR))
                .shadowColor(ShadowColor.none());
    }

}

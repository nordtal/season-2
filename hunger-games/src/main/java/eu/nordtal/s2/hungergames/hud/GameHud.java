package eu.nordtal.s2.hungergames.hud;

import static eu.nordtal.s2.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.s2.hungergames.GameState;
import eu.nordtal.s2.hungergames.border.BorderController;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.game.WinTracker;
import eu.nordtal.s2.hungergames.loot.LootRefill;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.packrendering.Glyphs;
import eu.nordtal.s2.packrendering.hud.Bearing;
import eu.nordtal.s2.packrendering.hud.BossBarLine;
import eu.nordtal.s2.packrendering.hud.BossBarLine.Pill;
import eu.nordtal.s2.papercommon.hud.Hud;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * The game's three boss bar lines on the plugin's {@code Hud}: players, loot and border, from release to decision.
 *
 * The resource pack hides the vanilla bar, so this class only decides what each line says.
 */
public final class GameHud {

    private final World world;
    private final HungerGamesSpec config;
    private final Messages messages;
    private final BorderController border;
    private final GameState state;

    /** Read at render time rather than pushed in, so the living count cannot go stale. */
    private final WinTracker wins;

    /** Read on every redraw, like {@link #wins}. */
    private final LootRefill loot;

    private final Clock clock;

    /** Whether a game is under way; the lines are hidden otherwise. */
    private volatile boolean shown;

    public GameHud(
            final World world,
            final HungerGamesSpec config,
            final Messages messages,
            final BorderController border,
            final GameState state,
            final WinTracker wins,
            final LootRefill loot,
            final Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.world = world;
        this.config = config;
        this.messages = messages;
        this.border = border;
        this.state = state;
        this.wins = wins;
        this.loot = loot;
    }

    /** Declares the three lines, players above loot above border. */
    public void declareOn(final Hud hud) {
        hud.declare(this::playersLine);
        hud.declare(this::lootLine);
        hud.declare(this::borderLine);
    }

    /** Shows the lines to everybody in the arena from the next frame on. */
    public void show() {
        shown = true;
    }

    /** Hides the lines from the next frame on. */
    public void hide() {
        shown = false;
    }

    private boolean showsTo(final Player player) {
        return shown && player.getWorld().equals(world);
    }

    private List<Pill> playersLine(final Player player, final Locale locale) {
        if (!showsTo(player)) {
            return List.of();
        }
        return List.of(Pill.of(
                Glyphs.BOSSBAR_ICON_ALIVE,
                withArrow(
                        messages.format(
                                locale,
                                MESSAGES.hg()
                                        .hud()
                                        .players(wins.aliveCount(), wins.deadCount(state.effectiveParticipants()))),
                        nearestPlayerArrow(player))));
    }

    private List<Pill> lootLine(final Player player, final Locale locale) {
        if (!showsTo(player)) {
            return List.of();
        }
        return List.of(Pill.of(Glyphs.BOSSBAR_ICON_LOOT_POINT, withArrow(lootText(locale), nearestLootArrow(player))));
    }

    private List<Pill> borderLine(final Player player, final Locale locale) {
        if (!showsTo(player)) {
            return List.of();
        }
        return List.of(Pill.of(Glyphs.BOSSBAR_ICON_BORDER, borderText(locale)));
    }

    /** The arrow rides at the end of its text's pill, or nothing does when there is no target. */
    private static String withArrow(final String text, final String arrow) {
        return arrow.isEmpty() ? text : text + BossBarLine.ICON_GAP + arrow;
    }

    private String lootText(final Locale locale) {
        final Instant nextRefillAt = loot.nextRefillAt();
        if (nextRefillAt == null) {
            return messages.format(locale, MESSAGES.hg().hud().lootNone());
        }
        return messages.format(locale, MESSAGES.hg().hud().loot(Duration.between(clock.instant(), nextRefillAt)));
    }

    private String borderText(final Locale locale) {
        if (!state.isShrinking()) {
            return messages.format(locale, MESSAGES.hg().hud().borderStable());
        }
        final Instant endsAt = state.shrinkEndsAt();
        final Duration left = endsAt == null ? Duration.ZERO : Duration.between(clock.instant(), endsAt);
        final double distance = Math.max(0, (border.currentSize() - state.shrinkTarget()) / 2.0);
        return messages.format(locale, MESSAGES.hg().hud().borderShrinking(left, Math.round(distance)));
    }

    private String nearestPlayerArrow(final Player player) {
        final Location playerLocation = Objects.requireNonNull(player.getLocation());
        Player nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (final Player other : world.getPlayers()) {
            if (other.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            final double distanceSquared =
                    Objects.requireNonNull(other.getLocation()).distanceSquared(playerLocation);
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearest = other;
            }
        }
        if (nearest == null) {
            return "";
        }
        final Location nearestLocation = Objects.requireNonNull(nearest.getLocation());
        final int index = Bearing.arrowIndex(
                playerLocation.getX(),
                playerLocation.getZ(),
                playerLocation.getYaw(),
                nearestLocation.getX(),
                nearestLocation.getZ());
        return Glyphs.BOSSBAR_ARROWS.get(index);
    }

    private String nearestLootArrow(final Player player) {
        final Location playerLocation = Objects.requireNonNull(player.getLocation());
        HungerGamesSpec.LootPointSpec nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (final HungerGamesSpec.LootPointSpec point : config.lootPoints()) {
            final Location location = new Location(world, point.x(), point.y(), point.z());
            if (!border.isInside(location)) {
                continue;
            }
            final double dx = point.x() - playerLocation.getX();
            final double dz = point.z() - playerLocation.getZ();
            final double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearest = point;
            }
        }
        if (nearest == null) {
            return "";
        }
        final int index = Bearing.arrowIndex(
                playerLocation.getX(), playerLocation.getZ(), playerLocation.getYaw(), nearest.x(), nearest.z());
        return Glyphs.BOSSBAR_ARROWS.get(index);
    }
}

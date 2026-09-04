package eu.nordtal.s2.hungergames.border;

import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.game.GameState;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.UUID;

/**
 * Drives {@code World#getWorldBorder()} per docs/hunger-games.md#the-border: centred on spawn,
 * shrinking by a fixed step on every death (extending an in-flight shrink rather than restarting
 * it), and a slow passive shrink after a configured quiet period with no death.
 * <p>
 * {@code WorldBorder} does not expose "am I mid-transition" - verified against Paper 26.2's actual
 * interface (only {@code getSize()} and {@code changeSize(double, long ticks)} exist as current,
 * non-deprecated methods; the {@code setSize(double, long seconds)} overload is deprecated for
 * removal since 1.21.11 - no "current target" or "time remaining" getter exists at all) - so
 * {@link GameState} is the source of truth for whether a shrink is in flight and what it targets.
 * </p>
 */
public final class BorderController {

    private final Plugin plugin;
    private final World world;
    private final HungerGamesSpec config;
    private final Messages messages;
    private final PlayerLocales locales;

    private org.bukkit.scheduler.BukkitTask quietPeriodChecker;

    public BorderController(final Plugin plugin, final World world, final HungerGamesSpec config,
                            final Messages messages, final PlayerLocales locales) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
    }

    /** Sets the border up at game start and begins watching for the quiet period. */
    public void begin(final UUID gameId, final GameState state) {
        final WorldBorder border = world.getWorldBorder();
        border.setCenter(world.getSpawnLocation());
        border.setSize(config.borderStartDiameter());

        quietPeriodChecker = Bukkit.getScheduler().runTaskTimer(plugin, () -> checkQuietPeriod(state),
                20L * 30, 20L * 30);
    }

    public void stop() {
        if (quietPeriodChecker != null) {
            quietPeriodChecker.cancel();
            quietPeriodChecker = null;
        }
    }

    /**
     * Called whenever a player dies or an unattended body is eliminated. Extends an in-flight
     * shrink by one step, or starts a fresh death-triggered shrink from the border's current size.
     */
    public void onDeath(final GameState state) {
        state.markDeath(Instant.now());

        final WorldBorder border = world.getWorldBorder();
        final double from = state.isShrinking() ? state.shrinkTarget() : border.getSize();
        final double target = BorderMath.nextShrinkTarget(from, state.borderStep(), config.borderEndDiameter());

        if (target >= from) {
            // Already at the floor; nothing to extend.
            return;
        }

        final long durationMillis = BorderMath.shrinkDurationMillis(
                border.getSize(), target, config.borderWallSpeedBlocksPerSecond());
        // changeSize(double, long ticks) is WorldBorder's current (non-deprecated) API; the
        // deprecated setSize(double, long seconds) overload was removed from Paper 26.2's own
        // interface as of 1.21.11 per its javadoc, verified against the resolved sources jar.
        border.changeSize(target, Math.max(1, durationMillis / 50));

        final Instant endsAt = Instant.now().plusMillis(durationMillis);
        state.beginShrink(target, endsAt, false);
        announce(target, durationMillis / 1000);
    }

    private void checkQuietPeriod(final GameState state) {
        if (state.isShrinking()) {
            // A shrink already running (death-triggered or passive) - and if it has actually
            // finished, clear the flag so the next check can start a fresh passive shrink.
            if (state.shrinkEndsAt() != null && Instant.now().isAfter(state.shrinkEndsAt())) {
                state.endShrink();
            }
            return;
        }

        final WorldBorder border = world.getWorldBorder();
        if (border.getSize() <= config.borderEndDiameter()) {
            return;
        }

        final Instant quietSince = state.lastDeathAt();
        if (quietSince == null) {
            return;
        }
        final long quietSeconds = java.time.Duration.between(quietSince, Instant.now()).toSeconds();
        if (quietSeconds < config.borderQuietPeriodSeconds()) {
            return;
        }

        final double target = config.borderEndDiameter();
        final long durationMillis = BorderMath.passiveShrinkDurationMillis(
                border.getSize(), target, config.borderPassiveShrinkBlocksPerHour());
        border.changeSize(target, Math.max(1, durationMillis / 50));

        final Instant endsAt = Instant.now().plusMillis(durationMillis);
        state.beginShrink(target, endsAt, true);
        announcePassive();
    }

    private void announce(final double target, final long seconds) {
        for (final Player player : world.getPlayers()) {
            player.sendMessage(MessageRenderer.of(messages).format(locales.of(player.getUniqueId()),
                    "hg.border.shrink-started", "target", String.valueOf(Math.round(target)),
                    "seconds", String.valueOf(seconds)));
        }
    }

    private void announcePassive() {
        for (final Player player : world.getPlayers()) {
            player.sendMessage(MessageRenderer.of(messages).get(locales.of(player.getUniqueId()),
                    "hg.border.passive-shrink-started"));
        }
    }

    /**
     * @param location a position in the event world
     * @return whether the position is still inside the current border - used to treat a loot point
     *         the border has passed as absent (docs/hunger-games.md#loot) and to let the border
     *         kill an unattended body (docs/hunger-games.md#disconnects)
     */
    public boolean isInside(final Location location) {
        return world.getWorldBorder().isInside(location);
    }

    public double currentSize() {
        return world.getWorldBorder().getSize();
    }
}

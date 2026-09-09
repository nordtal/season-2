package eu.nordtal.s2.hungergames.border;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
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
 * Drives {@code World#getWorldBorder()}: centred on spawn, shrinking by a fixed step on every death
 * (extending an in-flight shrink rather than restarting it), plus a slow passive shrink after a
 * quiet period with no death.
 *
 * <p>{@code WorldBorder} exposes no "am I mid-transition", target or time-remaining getter, so
 * {@link GameState} is the source of truth for whether a shrink is in flight and what it targets.</p>
 */
public final class BorderController {

    private final Plugin plugin;
    private final World world;
    private final HungerGamesSpec config;
    private final Messages messages;
    private final PlayerLocales locales;
    private final HungerGamesSounds sounds;

    private org.bukkit.scheduler.BukkitTask quietPeriodChecker;

    public BorderController(final Plugin plugin, final World world, final HungerGamesSpec config,
                            final Messages messages, final PlayerLocales locales,
                            final HungerGamesSounds sounds) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
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

    /**
     * {@code COUNTDOWN_TICK}, not {@code NETWORK_EVENT}: a shrink is a clock running out on where
     * the listener may stand, not something that happened to somebody else.
     */
    private void announce(final double target, final long seconds) {
        for (final Player player : world.getPlayers()) {
            player.sendMessage(MessageRenderer.of(messages).format(locales.of(player.getUniqueId()),
                    "hg.border.shrink-started", "target", String.valueOf(Math.round(target)),
                    "seconds", String.valueOf(seconds)));
            sounds.play(player, Feedback.COUNTDOWN_TICK);
        }
    }

    private void announcePassive() {
        for (final Player player : world.getPlayers()) {
            player.sendMessage(MessageRenderer.of(messages).get(locales.of(player.getUniqueId()),
                    "hg.border.passive-shrink-started"));
            sounds.play(player, Feedback.COUNTDOWN_TICK);
        }
    }

    /**
     * @param location a position in the event world
     * @return whether the position is still inside the current border - a loot point the border
     *         has passed counts as absent, and an unattended body outside it dies
     */
    public boolean isInside(final Location location) {
        return world.getWorldBorder().isInside(location);
    }

    public double currentSize() {
        return world.getWorldBorder().getSize();
    }
}

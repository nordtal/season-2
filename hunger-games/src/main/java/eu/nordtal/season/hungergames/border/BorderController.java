package eu.nordtal.season.hungergames.border;

import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.hungergames.GameState;
import eu.nordtal.season.hungergames.config.HungerGamesSpec;
import eu.nordtal.season.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Sent;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Controls the world border. The border is centred on the spawn and shrinks by one step on every death.
 * After {@code borderQuietPeriodSeconds} without a death, it shrinks slowly to the end diameter.
 */
public final class BorderController {

    record Shrink(
            @Example("100") long target, @Example("60") long seconds) {}

    static final DocumentSpec<Shrink> SHRINK_STARTED =
            DocumentSpec.game("hunger-games", "border-shrink-started", "Game", DocumentSpec.Shape.CHAT, Shrink.class);
    static final DocumentSpec<DocumentSpec.None> PASSIVE_SHRINK_STARTED =
            DocumentSpec.game("hunger-games", "border-passive-shrink-started", "Game", DocumentSpec.Shape.CHAT);

    private final Plugin plugin;
    private final World world;
    private final HungerGamesSpec config;
    private final MessageRenderer renderer;
    private final Identities identities;
    private final HungerGamesSounds sounds;

    private Scheduler.@Nullable Task quietPeriodChecker;

    private final Clock clock;

    public BorderController(
            final Plugin plugin,
            final World world,
            final HungerGamesSpec config,
            final MessageRenderer renderer,
            final Identities identities,
            final HungerGamesSounds sounds,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.renderer = renderer;
        this.identities = identities;
        this.sounds = sounds;
    }

    /** Sets the border to the start diameter and starts the quiet period check. */
    public void begin(final UUID gameId, final GameState state) {
        final WorldBorder border = world.getWorldBorder();
        border.setCenter(world.getSpawnLocation());
        border.setSize(config.borderStartDiameter());

        final Duration check = Duration.ofSeconds(30);
        quietPeriodChecker = PaperScheduler.of(plugin).onMainEvery(check, check, () -> checkQuietPeriod(state));
    }

    public void stop() {
        if (quietPeriodChecker != null) {
            quietPeriodChecker.cancel();
            quietPeriodChecker = null;
        }
    }

    /** Shrinks the border by one step after a death or an elimination. */
    public void onDeath(final GameState state) {
        state.markDeath(clock.instant());

        final WorldBorder border = world.getWorldBorder();
        final double from = state.isShrinking() ? state.shrinkTarget() : border.getSize();
        final double target = BorderMath.nextShrinkTarget(from, state.borderStep(), config.borderEndDiameter());

        if (target >= from) {
            return;
        }

        final long durationMillis =
                BorderMath.shrinkDurationMillis(border.getSize(), target, config.borderWallSpeedBlocksPerSecond());
        border.changeSize(target, Math.max(1, durationMillis / 50));

        final Instant endsAt = clock.instant().plusMillis(durationMillis);
        state.beginShrink(target, endsAt, false);
        announce(target, durationMillis / 1000);
    }

    private void checkQuietPeriod(final GameState state) {
        if (state.isShrinking()) {
            if (state.shrinkEndsAt() != null && clock.instant().isAfter(state.shrinkEndsAt())) {
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
        final long quietSeconds =
                java.time.Duration.between(quietSince, clock.instant()).toSeconds();
        if (quietSeconds < config.borderQuietPeriodSeconds()) {
            return;
        }

        final double target = config.borderEndDiameter();
        final long durationMillis = BorderMath.passiveShrinkDurationMillis(
                border.getSize(), target, config.borderPassiveShrinkBlocksPerHour());
        border.changeSize(target, Math.max(1, durationMillis / 50));

        final Instant endsAt = clock.instant().plusMillis(durationMillis);
        state.beginShrink(target, endsAt, true);
        announcePassive();
    }

    private void announce(final double target, final long seconds) {
        for (final Player player : world.getPlayers()) {
            player.sendMessage(renderer.format(
                    identities.languageOf(player.getUniqueId()),
                    SHRINK_STARTED.with(new Shrink(Math.round(target), seconds))));
            sounds.play(player, Feedback.COUNTDOWN_TICK);
        }
    }

    private void announcePassive() {
        for (final Player player : world.getPlayers()) {
            player.sendMessage(
                    renderer.format(identities.languageOf(player.getUniqueId()), Sent.of(PASSIVE_SHRINK_STARTED)));
            sounds.play(player, Feedback.COUNTDOWN_TICK);
        }
    }

    /** Returns whether the location is inside the border. */
    public boolean isInside(final Location location) {
        return world.getWorldBorder().isInside(location);
    }

    public double currentSize() {
        return world.getWorldBorder().getSize();
    }
}

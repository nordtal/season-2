package eu.nordtal.season.smp.progress;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.papercommon.player.Identities;
import eu.nordtal.season.papercommon.time.PaperScheduler;
import eu.nordtal.season.smp.milestone.Milestone;
import eu.nordtal.season.smp.milestone.MilestoneTrack;
import eu.nordtal.season.smp.milestone.Objective;
import eu.nordtal.season.smp.milestone.ObjectiveType;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Credits {@code STATISTIC} objectives with the increase of the vanilla statistics of online players.
 * It reads on the main thread and credits asynchronously.
 * The first read of a statistic per player is a baseline and credits nothing.
 */
public final class StatisticPoller {

    private static final Duration PERIOD = Duration.ofSeconds(5);

    private final Plugin plugin;
    private final java.util.function.Supplier<MilestoneTrack> track;

    private final ObjectiveEngine engine;
    private final Identities identities;

    private final Map<UUID, Map<String, Long>> baselines = new HashMap<>();

    /** The track of the last poll. A different track clears {@code baselines}. */
    private @Nullable MilestoneTrack sampledUnder;

    private Scheduler.@Nullable Task task;

    public StatisticPoller(
            final Plugin plugin,
            final java.util.function.Supplier<MilestoneTrack> track,
            final ObjectiveEngine engine,
            final Identities identities) {
        this.plugin = plugin;
        this.track = track;
        this.engine = engine;
        this.identities = identities;
    }

    public void start() {
        stop();
        task = PaperScheduler.of(plugin).onMainEvery(PERIOD, PERIOD, this::poll);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        baselines.clear();
    }

    public void forget(final UUID player) {
        baselines.remove(player);
    }

    private void poll() {
        final MilestoneTrack now = track.get();
        if (now != sampledUnder) {
            baselines.clear();
            sampledUnder = now;
        }

        final Optional<String> activeKey = activeMilestone;
        if (activeKey.isEmpty()) {
            return;
        }
        final Milestone milestone = now.milestone(activeKey.get()).orElse(null);
        if (milestone == null) {
            return;
        }

        for (final Objective objective : milestone.objectives()) {
            if (objective.type() != ObjectiveType.STATISTIC) {
                continue;
            }
            for (final Player player : Bukkit.getOnlinePlayers()) {
                sample(player, objective);
            }
        }
    }

    private void sample(final Player player, final Objective objective) {
        final long now = read(player, objective);
        final Map<String, Long> forPlayer = baselines.computeIfAbsent(player.getUniqueId(), key -> new HashMap<>());
        final Long previous = forPlayer.put(objective.key(), now);

        if (previous == null || now <= previous) {
            return;
        }
        final long delta = now - previous;
        final DiscordId discordId = identities.discordIdOf(player.getUniqueId()).orElse(null);
        if (discordId == null) {
            return;
        }
        PaperScheduler.of(plugin).execute(() -> engine.credit(discordId, objective.key(), delta, player.getUniqueId()));
    }

    private long read(final Player player, final Objective objective) {
        final Statistic statistic = statisticOf(objective);
        if (statistic == null) {
            return 0L;
        }
        final List<String> subjects = objective.subjects();
        if (subjects == null || subjects.isEmpty()) {
            return statistic.getType() == Statistic.Type.UNTYPED ? player.getStatistic(statistic) : 0L;
        }

        long total = 0L;
        for (final String subject : subjects) {
            total += readOne(player, statistic, subject);
        }
        return total;
    }

    private long readOne(final Player player, final Statistic statistic, final String subject) {
        return switch (statistic.getType()) {
            case BLOCK, ITEM ->
                GameKeys.material(subject)
                        .map(material -> player.getStatistic(statistic, material))
                        .orElse(0);
            case ENTITY ->
                GameKeys.entity(subject)
                        .map(entity -> player.getStatistic(statistic, entity))
                        .orElse(0);
            case UNTYPED -> player.getStatistic(statistic);
        };
    }

    private @Nullable Statistic statisticOf(final Objective objective) {
        return GameKeys.statistic(objective.statistic()).orElse(null);
    }

    private volatile Optional<String> activeMilestone = Optional.empty();

    public void setActiveMilestone(final Optional<String> key) {
        this.activeMilestone = key;
    }
}

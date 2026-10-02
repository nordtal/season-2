package eu.nordtal.s2.smp.progress;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.papercommon.game.GameKeys;
import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveType;
import eu.nordtal.s2.smp.milestone.TrackNames;
import eu.nordtal.s2.smp.player.Identities;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Counts {@code STATISTIC} objectives by polling each player's vanilla statistics and crediting the difference.
 *
 * The first read of a session is only a baseline; reads run on the main thread, crediting async.
 */
public final class StatisticPoller {

    /** Every five seconds: fast enough to feel live, slow enough that nobody notices the reads. */
    private static final long PERIOD_TICKS = 100L;

    private final Plugin plugin;
    /** The milestone track, as a supplier, because {@code /smp reload} replaces it. */
    private final java.util.function.Supplier<MilestoneTrack> track;

    private final ObjectiveEngine engine;
    private final Identities identities;

    private final Map<UUID, Map<String, Long>> baselines = new HashMap<>();

    /** The track the baselines were sampled under; a changed track resets them, as a key may now mean more. */
    private @Nullable MilestoneTrack sampledUnder;

    private @Nullable BukkitTask task;

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
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, PERIOD_TICKS, PERIOD_TICKS);
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
            // First read of the session, or a statistic that went backwards; nothing honest to credit either way.
            return;
        }
        final long delta = now - previous;
        final DiscordId discordId = identities.discordIdOf(player.getUniqueId()).orElse(null);
        if (discordId == null) {
            return;
        }
        Bukkit.getScheduler()
                .runTaskAsynchronously(
                        plugin, () -> engine.credit(discordId, objective.key(), delta, player.getUniqueId()));
    }

    /** Sums the statistic across every subject the objective names. */
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

    /** Reads one subject, which {@link TrackNames} made sure this server has before the track was taken. */
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

    /** Which milestone is accepting progress, pushed in by the async sweep so {@link #poll} never queries. */
    private volatile Optional<String> activeMilestone = Optional.empty();

    public void setActiveMilestone(final Optional<String> key) {
        this.activeMilestone = key;
    }
}

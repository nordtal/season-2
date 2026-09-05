package eu.nordtal.s2.smp.progress;

import eu.nordtal.s2.smp.milestone.Milestone;
import eu.nordtal.s2.smp.milestone.MilestoneTrack;
import eu.nordtal.s2.smp.milestone.Objective;
import eu.nordtal.s2.smp.milestone.ObjectiveType;
import eu.nordtal.s2.smp.player.Identities;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Counts {@code STATISTIC} objectives by reading each player's vanilla statistic and crediting the
 * difference since it was last read.
 *
 * <h2>Why polling rather than events</h2>
 * Decided 2026-09-01. The alternative was a handler per kind of statistic - precise to the second,
 * and a new Java class plus a release for every objective that counts something new. This way a new
 * objective is a line in {@code milestones.yml} and nothing else, which is what makes the track
 * retunable mid-season by somebody who is not writing code. The cost is that progress moves in steps
 * of a few seconds rather than instantly, and a shared objective's bar is a scoreboard rather than a
 * mechanic.
 *
 * <h2>Baselines</h2>
 * A player's statistic is a lifetime total, so the first read of a session credits nothing - it only
 * records where they started. Otherwise the first poll after a restart would credit every block
 * anybody had ever mined, and the season's first objective would complete in a tick.
 *
 * <p>The reads happen on the main thread, because a player's statistics are server state. They are
 * cheap - a map lookup each - and the crediting that follows is handed to an async task.
 */
public final class StatisticPoller {

    /** Every five seconds. Fast enough to feel live, slow enough that nobody notices the reads. */
    private static final long PERIOD_TICKS = 100L;

    private final Plugin plugin;
    /**
     * The milestone track, <b>as a supplier</b>.
     *
     * <p>{@code /smp reload} replaces the plugin's track with a new instance - that is the whole
     * reason {@code milestones.yml} is a separate reloadable file, because a milestone is appended
     * and a target lowered mid-season. A reference captured at enable would go on reading the
     * definitions the server started with, for the rest of the season, and nothing would say so.</p>
     */
    private final java.util.function.Supplier<MilestoneTrack> track;
    private final ObjectiveEngine engine;
    private final Identities identities;

    /** player -> objective key -> the value at the last read. */
    private final Map<UUID, Map<String, Long>> baselines = new HashMap<>();

    /**
     * The track the baselines in {@link #baselines} were sampled under.
     *
     * <p>A baseline is a raw statistic value paired with an objective <em>key</em>, and a reload can
     * change what that key means: an objective counting coal that starts counting coal and iron
     * reads far higher on the next poll, and the whole difference would be credited as progress
     * somebody just made. Nothing about the stored number says which definition produced it, so the
     * only honest answer when the track changes is to start again.</p>
     *
     * <p>It costs one tick of progress per player, which is exactly what the first read of a session
     * already costs and for the same reason - see {@link #sample}.</p>
     */
    private MilestoneTrack sampledUnder;
    private BukkitTask task;

    public StatisticPoller(final Plugin plugin, final java.util.function.Supplier<MilestoneTrack> track,
                           final ObjectiveEngine engine, final Identities identities) {
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
                sample(player, objective, activeKey.get());
            }
        }
    }

    private void sample(final Player player, final Objective objective, final String milestoneKey) {
        final long now = read(player, objective);
        final Map<String, Long> forPlayer =
                baselines.computeIfAbsent(player.getUniqueId(), key -> new HashMap<>());
        final Long previous = forPlayer.put(objective.key(), now);

        if (previous == null || now <= previous) {
            // First read of the session, or a statistic that somehow went backwards. Either way
            // there is nothing honest to credit.
            return;
        }
        final long delta = now - previous;
        final String discordId = identities.discordIdOf(player.getUniqueId()).orElse(null);
        if (discordId == null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> engine.credit(discordId, objective.key(), delta, player.getUniqueId()));
    }

    /**
     * Sums the statistic across every subject the objective names.
     *
     * <p>An objective can count several things as one - "coal, iron and copper ore mined" is one
     * number to the community - so the subjects are added together rather than tracked apart.
     */
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
        try {
            return switch (statistic.getType()) {
                case BLOCK, ITEM -> player.getStatistic(statistic,
                        Material.valueOf(subject.toUpperCase(java.util.Locale.ROOT)));
                case ENTITY -> player.getStatistic(statistic,
                        EntityType.valueOf(subject.toUpperCase(java.util.Locale.ROOT)));
                case UNTYPED -> player.getStatistic(statistic);
            };
        } catch (final IllegalArgumentException exception) {
            // A name the config got wrong. Said once per poll would be spam, so it is said here and
            // the objective simply does not move - which is loud in its own way, on the board.
            return 0L;
        }
    }

    private Statistic statisticOf(final Objective objective) {
        if (objective.statistic() == null || objective.statistic().isBlank()) {
            return null;
        }
        try {
            return Statistic.valueOf(objective.statistic().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Which milestone is accepting progress, pushed in rather than queried.
     *
     * <p>{@link #poll} runs on the main thread every five seconds, so it must not touch the
     * database. The same async sweep that refreshes the boards and the HUD tells this poller too,
     * and being one sweep behind costs nothing: an objective that became active four seconds ago
     * starts counting four seconds late.
     */
    private volatile Optional<String> activeMilestone = Optional.empty();

    public void setActiveMilestone(final Optional<String> key) {
        this.activeMilestone = key;
    }
}

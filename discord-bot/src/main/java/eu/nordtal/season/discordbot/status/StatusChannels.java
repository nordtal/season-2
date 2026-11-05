package eu.nordtal.season.discordbot.status;

import eu.nordtal.season.common.season.SeasonOpening;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.common.time.Coalescing;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.network.NetworkSnapshot;
import eu.nordtal.season.database.network.SnapshotDirectory;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.season.SeasonDirectory;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.jspecify.annotations.Nullable;

/**
 * Renames one channel per language to show the state of the network.
 * Discord allows two renames per ten minutes.
 * A channel is renamed only when its name changes and at most every six minutes.
 */
@Slf4j
public final class StatusChannels {

    static final int MINIMUM_RENAME_MINUTES = 6;

    /** The time that the first signal of a burst waits for the following signals. */
    static final Duration SETTLE = Duration.ofSeconds(2);

    /** How long after the launch its tick runs, so a clock a little behind already reads it as passed. */
    static final Duration PAST_LAUNCH = Duration.ofSeconds(1);

    /** The notification channels that change a name: the season and the hunger games. */
    static final Set<Channel> CHANNELS = EnumSet.of(Channel.SEASON, Channel.HUNGER_GAMES);

    private final JDA jda;
    private final GuildLanguages languages;
    private final DocumentRenderer documents;
    private final SeasonDirectory season;
    private final SnapshotDirectory snapshots;
    private final Clock clock;

    /** The last rename attempt by channel id. The timer and JDA callbacks write it. */
    private final Map<String, Rename> attempts = new ConcurrentHashMap<>();

    /** Where a tick at the launch is scheduled, null until {@link #follow} or without it. */
    private volatile @Nullable Ticking ticking;

    /** The tick waiting for the launch, null while none waits. Only the lane writes it. */
    private volatile @Nullable LaunchTick atLaunch;

    public StatusChannels(
            final JDA jda,
            final GuildLanguages languages,
            final DocumentRenderer documents,
            final SeasonDirectory season,
            final SnapshotDirectory snapshots,
            final Clock clock) {
        this.jda = jda;
        this.languages = languages;
        this.documents = documents;
        this.season = season;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    public boolean configured() {
        return languages.all().stream().anyMatch(GuildLanguages.Language::hasStatusChannel);
    }

    /**
     * Runs a tick for each signal of a channel that changes a name. Signals in quick succession cause one tick.
     * Call it before {@link SignalHub#start}.
     *
     * @param lane the executor that runs the ticks one at a time
     */
    public void follow(final SignalHub hub, final Scheduler scheduler, final Executor lane) {
        ticking = new Ticking(scheduler, lane);
        final Runnable signal = tickOnSignal(scheduler, lane);
        for (final Channel channel : CHANNELS) {
            hub.on(channel, "the status channels", signal);
        }
    }

    /** Returns the signal handler. It runs one tick on the lane after {@link #SETTLE} for all signals of a burst. */
    Runnable tickOnSignal(final Scheduler scheduler, final Executor lane) {
        return new Coalescing(scheduler, SETTLE, () -> lane.execute(this::tick))::request;
    }

    /** Reads the state, renders a name per language and renames the channels whose name changed. */
    public void tick() {
        final List<GuildLanguages.Language> configured = languages.all().stream()
                .filter(GuildLanguages.Language::hasStatusChannel)
                .toList();
        if (configured.isEmpty()) {
            return;
        }

        final SeasonState state = season.state();
        final Instant now = clock.instant();
        armForLaunch(state, now);
        final NetworkSnapshot snapshot =
                StatusName.needsCounts(state, now) ? snapshots.snapshot() : NetworkSnapshot.EMPTY;

        for (final GuildLanguages.Language language : configured) {
            rename(language, StatusName.render(documents, language.locale(), state, snapshot, now), now);
        }
    }

    /** Schedules a tick at a launch date in the future, since its passing sends no signal. */
    private void armForLaunch(final SeasonState state, final Instant now) {
        final Ticking timer = ticking;
        final Instant launch = state.launch();
        final LaunchTick armed = atLaunch;
        if (timer == null || (armed != null && armed.at().equals(launch))) {
            return;
        }
        if (armed != null) {
            armed.task().cancel();
            atLaunch = null;
        }
        if (launch == null || state.opening(now) != SeasonOpening.COUNTDOWN) {
            return;
        }
        final Scheduler.Task task = timer.scheduler()
                .after(
                        Duration.between(now, launch).plus(PAST_LAUNCH),
                        () -> timer.lane().execute(this::tick));
        atLaunch = new LaunchTick(launch, task);
    }

    private void rename(final GuildLanguages.Language language, final String name, final Instant now) {
        final String channelId = language.statusChannelId();
        final Rename previous = attempts.get(channelId);
        if (previous != null && name.equals(previous.confirmed())) {
            return;
        }
        if (previous != null && Duration.between(previous.at(), now).toMinutes() < MINIMUM_RENAME_MINUTES) {
            // The cooldown counts attempts, not successes.
            return;
        }

        final GuildChannel channel = jda.getGuildChannelById(channelId);
        if (channel == null) {
            log.error(
                    "Status channel {} for '{}' does not exist or the bot cannot see it;"
                            + " it would have been named \"{}\"",
                    channelId,
                    language.tag(),
                    name);
            return;
        }

        // The attempt is stored before the request.
        final Rename sent = new Rename(name, now);
        attempts.put(channelId, sent);
        channel.getManager()
                .setName(name)
                .queue(success -> log.debug("Status channel for '{}' is now \"{}\"", language.tag(), name), failure -> {
                    // Forget the name and keep the time, so the rename is retried.
                    attempts.replace(channelId, sent, new Rename(null, sent.at()));
                    log.warn(
                            "Could not rename the status channel for '{}' to \"{}\"; it will be"
                                    + " retried once the cooldown is up",
                            language.tag(),
                            name,
                            failure);
                });
    }

    /** The scheduler a tick at the launch waits on, and the lane it runs on. */
    private record Ticking(Scheduler scheduler, Executor lane) {}

    /** A tick waiting for the launch at {@code at}. */
    private record LaunchTick(Instant at, Scheduler.Task task) {}

    /**
     * The last rename attempt of a channel.
     *
     * @param confirmed the name last sent that did not fail, or {@code null} after a failure
     * @param at the time of the attempt, from which the cooldown runs
     */
    private record Rename(@Nullable String confirmed, Instant at) {}
}

package eu.nordtal.season.discordbot.status;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.time.Coalescing;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.network.NetworkSnapshot;
import eu.nordtal.season.database.network.SnapshotDirectory;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.phase.PhaseDirectory;
import eu.nordtal.season.discordbot.StoredDocuments;
import eu.nordtal.season.discordbot.announce.Announcements;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
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

    /** The notification channels that change a name: the phase and the hunger games. */
    static final Set<Channel> CHANNELS = EnumSet.of(Channel.PHASE, Channel.HUNGER_GAMES);

    record Phase(
            @Example("smp") SeasonPhase phase,
            @Example("pre-event") SeasonPhase previous) {}

    static final DocumentSpec<Phase> ANNOUNCE = DocumentSpec.plain(
                    StoredDocuments.NAMESPACE, "announce-phase", "Announcements", Phase.class)
            .titled("Phase change");

    private final JDA jda;
    private final GuildLanguages languages;
    private final DocumentRenderer documents;
    private final PhaseDirectory phases;
    private final SnapshotDirectory snapshots;
    private final Clock clock;

    /** The last rename attempt by channel id. The timer and JDA callbacks write it. */
    private final Map<String, Rename> attempts = new ConcurrentHashMap<>();

    public StatusChannels(
            final JDA jda,
            final GuildLanguages languages,
            final DocumentRenderer documents,
            final PhaseDirectory phases,
            final SnapshotDirectory snapshots,
            final Clock clock) {
        this(jda, languages, documents, phases, snapshots, clock, null);
    }

    /**
     * Creates the renamer, posting phase changes when announcements are given.
     *
     * @param announcements where a phase change is posted, or {@code null} to only rename
     */
    public StatusChannels(
            final JDA jda,
            final GuildLanguages languages,
            final DocumentRenderer documents,
            final PhaseDirectory phases,
            final SnapshotDirectory snapshots,
            final Clock clock,
            final @Nullable Announcements announcements) {
        this.jda = jda;
        this.languages = languages;
        this.documents = documents;
        this.phases = phases;
        this.snapshots = snapshots;
        this.clock = clock;
        this.announcements = announcements;
    }

    private final @Nullable Announcements announcements;
    /** The phase of the last tick, or null before the first tick. */
    private volatile @Nullable SeasonPhase lastSeen;

    public boolean configured() {
        return languages.all().stream()
                .anyMatch(language ->
                        language.hasStatusChannel() || (announcements != null && language.hasAnnouncementChannel()));
    }

    /**
     * Runs a tick for each signal of a channel that changes a name. Signals in quick succession cause one tick.
     * Call it before {@link SignalHub#start}.
     *
     * @param lane the executor that runs the ticks one at a time
     */
    public void follow(final SignalHub hub, final Scheduler scheduler, final Executor lane) {
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
        final SeasonPhase phase = phases.currentPhase();
        announceIfChanged(phase);

        final List<GuildLanguages.Language> configured = languages.all().stream()
                .filter(GuildLanguages.Language::hasStatusChannel)
                .toList();
        if (configured.isEmpty()) {
            return;
        }

        // Neither is read during MAINTENANCE.
        final Instant launch = phase == SeasonPhase.PRE_LAUNCH ? phases.launch().orElse(null) : null;
        final NetworkSnapshot snapshot = needsCounts(phase) ? snapshots.snapshot() : NetworkSnapshot.EMPTY;
        final Instant now = clock.instant();

        for (final GuildLanguages.Language language : configured) {
            rename(language, StatusName.render(documents, language.locale(), phase, snapshot, launch, now), now);
        }
    }

    /**
     * Posts a phase change to every announcement channel in the language of the channel.
     *
     * The first tick after a start only stores the phase and posts nothing.
     */
    private void announceIfChanged(final SeasonPhase phase) {
        final SeasonPhase previous = lastSeen;
        lastSeen = phase;
        if (announcements == null || previous == null || previous == phase) {
            return;
        }
        announcements.postAll(locale -> documents.plain(ANNOUNCE, new Phase(phase, previous), locale));
    }

    private static boolean needsCounts(final SeasonPhase phase) {
        return phase == SeasonPhase.PRE_EVENT || phase == SeasonPhase.START_EVENT || phase == SeasonPhase.SMP;
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

    /**
     * The last rename attempt of a channel.
     *
     * @param confirmed the name last sent that did not fail, or {@code null} after a failure
     * @param at the time of the attempt, from which the cooldown runs
     */
    private record Rename(@Nullable String confirmed, Instant at) {}
}

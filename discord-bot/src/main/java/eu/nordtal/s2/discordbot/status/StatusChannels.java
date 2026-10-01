package eu.nordtal.s2.discordbot.status;

import static eu.nordtal.s2.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.network.NetworkSnapshot;
import eu.nordtal.s2.database.network.SnapshotDirectory;
import eu.nordtal.s2.database.phase.PhaseDirectory;
import eu.nordtal.s2.discordbot.announce.Announcements;
import eu.nordtal.s2.discordbot.config.Languages;
import eu.nordtal.s2.messages.Messages;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.jspecify.annotations.Nullable;

/**
 * Renames one channel per language so the guild's sidebar says what the network is doing.
 *
 * Discord allows two renames per ten minutes, so a channel is renamed only on change and at most every six minutes.
 */
@Slf4j
public final class StatusChannels {

    /** The floor between two renames of the same channel, half of Discord's budget. */
    static final int MINIMUM_RENAME_MINUTES = 6;

    private final JDA jda;
    private final Languages languages;
    private final Messages messages;
    private final PhaseDirectory phases;
    private final SnapshotDirectory snapshots;
    private final Clock clock;

    /** Channel id to the name last accepted and when it was tried; written from the timer and from JDA callbacks. */
    private final Map<String, Rename> attempts = new ConcurrentHashMap<>();

    public StatusChannels(
            final JDA jda,
            final Languages languages,
            final Messages messages,
            final PhaseDirectory phases,
            final SnapshotDirectory snapshots,
            final Clock clock) {
        this(jda, languages, messages, phases, snapshots, clock, null);
    }

    /**
     * Creates the renamer, posting phase changes when announcements are given.
     *
     * @param announcements where a phase change is posted, or {@code null} to only rename
     */
    public StatusChannels(
            final JDA jda,
            final Languages languages,
            final Messages messages,
            final PhaseDirectory phases,
            final SnapshotDirectory snapshots,
            final Clock clock,
            final @Nullable Announcements announcements) {
        this.jda = jda;
        this.languages = languages;
        this.messages = messages;
        this.phases = phases;
        this.snapshots = snapshots;
        this.clock = clock;
        this.announcements = announcements;
    }

    private final @Nullable Announcements announcements;
    /** The phase the last tick saw; null before the first, so a restart announces nothing. */
    private volatile @Nullable SeasonPhase lastSeen;

    /** Returns whether any language has a status or announcement channel configured. */
    public boolean configured() {
        return languages.all().stream()
                .anyMatch(language ->
                        language.hasStatusChannel() || (announcements != null && language.hasAnnouncementChannel()));
    }

    /** Reads the state, renders a name per language and renames what changed, on every signal of the bot's hub. */
    public void tick() {
        final SeasonPhase phase = phases.currentPhase();
        announceIfChanged(phase);

        final List<Languages.Language> configured = languages.all().stream()
                .filter(Languages.Language::hasStatusChannel)
                .toList();
        if (configured.isEmpty()) {
            return;
        }

        // Reading neither during MAINTENANCE is one fewer query that can fail while the network is already down.
        final Instant launch = phase == SeasonPhase.PRE_LAUNCH ? phases.launch().orElse(null) : null;
        final NetworkSnapshot snapshot = needsCounts(phase) ? snapshots.snapshot() : NetworkSnapshot.EMPTY;
        final Instant now = clock.instant();

        for (final Languages.Language language : configured) {
            rename(language, StatusName.render(messages, language.locale(), phase, snapshot, launch, now), now);
        }
    }

    /**
     * Posts a phase change into every announcement channel, each in its own language.
     *
     * The first tick after a start only remembers, so a restart during SMP does not announce SMP.
     */
    private void announceIfChanged(final SeasonPhase phase) {
        final SeasonPhase previous = lastSeen;
        lastSeen = phase;
        if (announcements == null || previous == null || previous == phase) {
            return;
        }
        announcements.postAll(language ->
                messages.format(language.locale(), MESSAGES.announce().phase(phase.name(), previous.name())));
    }

    private static boolean needsCounts(final SeasonPhase phase) {
        return phase == SeasonPhase.PRE_EVENT || phase == SeasonPhase.START_EVENT || phase == SeasonPhase.SMP;
    }

    private void rename(final Languages.Language language, final String name, final Instant now) {
        final String channelId = language.statusChannelId();
        final Rename previous = attempts.get(channelId);
        if (previous != null && name.equals(previous.confirmed())) {
            return;
        }
        if (previous != null && Duration.between(previous.at(), now).toMinutes() < MINIMUM_RENAME_MINUTES) {
            // Stale by design: the cooldown counts attempts, not successes.
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

        // Recorded before the request, so a missed callback does not resend it.
        final Rename sent = new Rename(name, now);
        attempts.put(channelId, sent);
        channel.getManager()
                .setName(name)
                .queue(success -> log.debug("Status channel for '{}' is now \"{}\"", language.tag(), name), failure -> {
                    // Forget the name, keep the time, so a failed rename is retried.
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
     * What this bot believes a channel is called, and when it last tried to change it.
     *
     * @param confirmed the name last sent and not reported as failed, or {@code null} after a failure
     * @param at when that attempt was made; the cooldown runs off this
     */
    private record Rename(@Nullable String confirmed, Instant at) {}
}

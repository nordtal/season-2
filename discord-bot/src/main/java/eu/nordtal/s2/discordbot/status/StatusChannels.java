package eu.nordtal.s2.discordbot.status;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.network.NetworkSnapshot;
import eu.nordtal.s2.common.network.SnapshotDirectory;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.discordbot.announce.Announcements;
import eu.nordtal.s2.discordbot.config.Languages;
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
 * The whole design is one API limit: Discord allows two renames per ten minutes per channel.
 *
 * It is undocumented (discord/discord-api-docs#1900) and abusing the route is reported to produce immediate hard
 * blocks, so this class is built to stay well inside it rather than to discover where it is:
 *
 * - the tick runs every minute and costs nothing unless the name actually changed - {@link StatusName} is
 *   deliberately coarse so that it rarely does;
 *
 * - a channel is renamed at most once every {@value #MINIMUM_RENAME_MINUTES} minutes whatever the tick decides,
 *   which caps any ten-minute window at two;
 *
 * - each language is its own channel and therefore its own budget.
 *
 * What "changed" is measured against: The last name this bot set, not the name Discord reports. Discord normalises
 * the names of text channels - lower case, spaces to hyphens - so a name read back is frequently not the name that
 * was sent, and comparing against it would rename the channel on every single tick, for ever. (A voice channel keeps
 * the name verbatim, which is why one is the better shape for this; the bot works with either and does not care
 * which it is given.)
 *
 * The consequence is that a restart renames every configured channel once, because nothing is remembered across one.
 * That is one call per language per restart and is the cheapest correct answer available.
 *
 * Failure costs freshness and nothing else: An unreachable database or a channel that is gone leaves the name as it
 * is; the next tick is the retry. Nothing is cleared and nothing is blanked - a sidebar entry frozen at last hour's
 * number is better than one that says the season has no players.
 */
@Slf4j
public final class StatusChannels {

    /**
     * The floor between two renames of the same channel.
     *
     * Half of Discord's budget, so a second rename that lands right on the boundary still cannot break it, and
     * there is room left over for the one rename a restart costs.
     */
    static final int MINIMUM_RENAME_MINUTES = 6;

    private final JDA jda;
    private final Languages languages;
    private final Messages messages;
    private final PhaseDirectory phases;
    private final SnapshotDirectory snapshots;
    private final Clock clock;

    /**
     * Channel id to the name this bot last got Discord to accept, and when it last tried.
     *
     * Concurrent because the failure callback runs on a JDA thread while {@link #tick()} runs on the bot's timer, and
     * the two write the same entry.
     */
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
     * @param announcements where a phase change is posted, or {@code null} to only rename. The
     *                      change is noticed here because this is the one timer in the bot that
     *                      already reads the phase every minute; a second reader for the same row
     *                      would be a second opinion on when it changed
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

    /** @return whether any language has a status channel configured at all */
    public boolean configured() {
        return languages.all().stream()
                .anyMatch(language ->
                        language.hasStatusChannel() || (announcements != null && language.hasAnnouncementChannel()));
    }

    /**
     * One pass: read the state, render a name per language, rename what changed.
     *
     * Called from the bot's timer once a minute.
     */
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
     * A phase that differs from the one the previous tick saw is posted into every announcement channel.
     *
     * Each channel gets it in its own language. The first tick after a start only remembers: a bot that restarts
     * during SMP must not announce SMP.
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
            // Stale by design: the next tick recomputes from scratch, and the cooldown counts attempts, not successes.
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

        // Optimistic: recorded as confirmed before the request, so a missed callback does not resend it.
        final Rename sent = new Rename(name, now);
        attempts.put(channelId, sent);
        channel.getManager()
                .setName(name)
                .queue(success -> log.debug("Status channel for '{}' is now \"{}\"", language.tag(), name), failure -> {
                    // Forget the name, keep the attempt time, so a failed rename is retried rather than stuck stale.
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
     * @param confirmed the name last sent and not reported as failed, or {@code null} when the last
     *                  attempt failed - which makes the next eligible tick send it again
     * @param at        when that attempt was made, successful or not; the cooldown runs off this
     */
    private record Rename(@Nullable String confirmed, Instant at) {}
}

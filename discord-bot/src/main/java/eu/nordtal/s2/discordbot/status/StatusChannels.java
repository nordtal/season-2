package eu.nordtal.s2.discordbot.status;

import eu.nordtal.s2.discordbot.config.Languages;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.network.NetworkSnapshot;
import eu.nordtal.s2.common.network.SnapshotDirectory;
import eu.nordtal.s2.common.phase.PhaseDirectory;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renames one channel per language so the guild's sidebar says what the network is doing.
 *
 * <h2>The whole design is one API limit</h2>
 * Discord allows <b>two renames per ten minutes per channel</b>. It is undocumented
 * (discord/discord-api-docs#1900, read 2026-09-03) and abusing the route is reported to produce
 * immediate hard blocks, so this class is built to stay well inside it rather than to discover
 * where it is:
 * <ul>
 *   <li>the tick runs every minute and costs <b>nothing</b> unless the name actually changed -
 *       {@link StatusName} is deliberately coarse so that it rarely does;</li>
 *   <li>a channel is renamed at most once every {@value #MINIMUM_RENAME_MINUTES} minutes whatever
 *       the tick decides, which caps any ten-minute window at two;</li>
 *   <li>each language is its own channel and therefore its own budget.</li>
 * </ul>
 *
 * <h2>What "changed" is measured against</h2>
 * The last name <b>this bot set</b>, not the name Discord reports. Discord normalises the names of
 * text channels - lower case, spaces to hyphens - so a name read back is frequently not the name
 * that was sent, and comparing against it would rename the channel on every single tick, for ever.
 * (A voice channel keeps the name verbatim, which is why one is the better shape for this; the bot
 * works with either and does not care which it is given.)
 *
 * <p>The consequence is that a restart renames every configured channel once, because nothing is
 * remembered across one. That is one call per language per restart and is the cheapest correct
 * answer available.
 *
 * <h2>Failure costs freshness and nothing else</h2>
 * An unreachable database or a channel that is gone leaves the name as it is; the next tick is the
 * retry. Nothing is cleared and nothing is blanked - a sidebar entry frozen at last hour's number
 * is better than one that says the season has no players.
 */
@Slf4j
public final class StatusChannels {

    /**
     * The floor between two renames of the same channel. Half of Discord's budget, so a second
     * rename that lands right on the boundary still cannot break it, and there is room left over
     * for the one rename a restart costs.
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
     * <p>
     * Concurrent because the failure callback runs on a JDA thread while {@link #tick()} runs on
     * the bot's timer, and the two write the same entry.
     * </p>
     */
    private final Map<String, Rename> attempts = new ConcurrentHashMap<>();

    public StatusChannels(final JDA jda, final Languages languages, final Messages messages,
                          final PhaseDirectory phases, final SnapshotDirectory snapshots,
                          final Clock clock) {
        this.jda = jda;
        this.languages = languages;
        this.messages = messages;
        this.phases = phases;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    /** @return whether any language has a status channel configured at all */
    public boolean configured() {
        return languages.all().stream().anyMatch(Languages.Language::hasStatusChannel);
    }

    /**
     * One pass: read the state, render a name per language, rename what changed. Called from the
     * bot's timer once a minute.
     */
    public void tick() {
        final List<Languages.Language> configured = languages.all().stream()
                .filter(Languages.Language::hasStatusChannel)
                .toList();
        if (configured.isEmpty()) {
            return;
        }

        final SeasonPhase phase = phases.currentPhase();
        // Only PRE_LAUNCH counts down, and only the three phases with a game running need counts.
        // Reading neither during MAINTENANCE is not an optimisation for its own sake: it is one
        // fewer query that can fail while the network is already in trouble.
        final Instant launch = phase == SeasonPhase.PRE_LAUNCH ? phases.launch().orElse(null) : null;
        final NetworkSnapshot snapshot = needsCounts(phase) ? snapshots.snapshot() : NetworkSnapshot.EMPTY;
        final Instant now = clock.instant();

        for (final Languages.Language language : configured) {
            rename(language, StatusName.render(messages, language.locale(), phase, snapshot, launch, now), now);
        }
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
        if (previous != null
                && Duration.between(previous.at(), now).toMinutes() < MINIMUM_RENAME_MINUTES) {
            // The name is stale by design for a few minutes. The next tick tries again; nothing is
            // lost, because the value it would have written is recomputed from scratch each time.
            // This is also what stops a channel Discord keeps refusing from being retried every
            // minute - the cooldown counts attempts, not successes.
            return;
        }

        final GuildChannel channel = jda.getGuildChannelById(channelId);
        if (channel == null) {
            log.error("Status channel {} for '{}' does not exist or the bot cannot see it;"
                    + " it would have been named \"{}\"", channelId, language.tag(), name);
            return;
        }

        // Optimistic: the name is recorded as confirmed before the request, so a rename Discord
        // accepted but whose callback we never saw is not sent twice. A callback that reports
        // failure takes it back again - see below.
        final Rename sent = new Rename(name, now);
        attempts.put(channelId, sent);
        channel.getManager().setName(name).queue(
                success -> log.debug("Status channel for '{}' is now \"{}\"", language.tag(), name),
                failure -> {
                    // Forget the name but keep the attempt time. Without this the entry would still
                    // claim the channel is called `name`, and the next tick would return at the
                    // equality check above - so a channel that failed once would stay stale until
                    // the rendered text happened to change, which for a days-and-hours countdown is
                    // an hour and for MAINTENANCE is for ever. Conditional, so a callback arriving
                    // after a later tick has already written something else cannot undo it.
                    attempts.replace(channelId, sent, new Rename(null, sent.at()));
                    log.warn("Could not rename the status channel for '{}' to \"{}\"; it will be"
                            + " retried once the cooldown is up", language.tag(), name, failure);
                });
    }

    /**
     * What this bot believes a channel is called, and when it last tried to change it.
     *
     * @param confirmed the name last sent and not reported as failed, or {@code null} when the last
     *                  attempt failed - which makes the next eligible tick send it again
     * @param at        when that attempt was made, successful or not; the cooldown runs off this
     */
    private record Rename(String confirmed, Instant at) {
    }
}

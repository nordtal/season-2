package eu.nordtal.season.hungergames.game;

import static eu.nordtal.season.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.season.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks which participants are alive and decides the game.
 * {@link Tiebreak} decides deaths within {@link #SIMULTANEOUS_WINDOW}.
 */
public final class WinTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(WinTracker.class);

    /** The largest time between two deaths that count as simultaneous. */
    private static final Duration SIMULTANEOUS_WINDOW = Duration.ofMillis(500);

    private final GameDao dao;
    private final MessageRenderer renderer;
    private final Identities identities;
    private final HungerGamesSounds sounds;

    /** The living participants. */
    private final java.util.Map<UUID, Instant> aliveSince = new ConcurrentHashMap<>();

    /** The team of each participant, by member id. */
    private final java.util.Map<UUID, UUID> teamOf = new ConcurrentHashMap<>();

    private final ConcurrentLinkedQueue<UUID> recentDeaths = new ConcurrentLinkedQueue<>();
    private volatile @Nullable Instant lastDeathAt;

    private final Clock clock;

    public WinTracker(
            final GameDao dao,
            final MessageRenderer renderer,
            final Identities identities,
            final HungerGamesSounds sounds,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.dao = dao;
        this.renderer = renderer;
        this.identities = identities;
        this.sounds = sounds;
    }

    /** Sets the participants alive. */
    public void reset(final List<Participant> participants) {
        aliveSince.clear();
        teamOf.clear();
        recentDeaths.clear();
        final Instant now = clock.instant();
        for (final Participant participant : participants) {
            aliveSince.put(participant.memberId(), now);
            teamOf.put(participant.memberId(), participant.teamId());
        }
        lastDeathAt = null;
    }

    public int aliveCount() {
        return aliveSince.size();
    }

    public int deadCount(final int totalParticipants) {
        return totalParticipants - aliveSince.size();
    }

    /**
     * Records a death.
     *
     * @param killerMemberId the killer, {@code null} for a death by the border or the environment
     * @return the outcome, or empty when the game goes on
     */
    public Optional<Outcome> recordDeath(
            final UUID gameId, final UUID victimMemberId, final @Nullable UUID killerMemberId) {
        aliveSince.remove(victimMemberId);
        if (killerMemberId != null) {
            dao.recordEvent(gameId, "KILL", killerMemberId, victimMemberId);
        }
        dao.recordEvent(gameId, "DEATH", null, victimMemberId);

        final Instant now = clock.instant();
        final boolean simultaneous =
                lastDeathAt != null && Duration.between(lastDeathAt, now).compareTo(SIMULTANEOUS_WINDOW) <= 0;
        lastDeathAt = now;

        if (aliveSince.size() == 1) {
            final UUID winner = aliveSince.keySet().iterator().next();
            return Optional.of(Outcome.win(winner));
        }

        if (aliveSince.isEmpty() && simultaneous) {
            recentDeaths.add(victimMemberId);
            while (recentDeaths.size() > 2) {
                recentDeaths.poll();
            }
            if (recentDeaths.size() == 2) {
                final UUID first = recentDeaths.poll();
                final UUID second = recentDeaths.poll();
                final int firstKills = dao.killCount(gameId, first);
                final int secondKills = dao.killCount(gameId, second);
                final Optional<UUID> winner = Tiebreak.resolve(first, firstKills, second, secondKills);
                dao.recordTie(gameId, winner.orElse(null));
                return Optional.of(winner.map(id -> Outcome.tieBroken(
                                id, Math.max(firstKills, secondKills), Math.min(firstKills, secondKills)))
                        .orElseGet(() -> Outcome.tieNoWinner(firstKills)));
            }
        } else {
            recentDeaths.add(victimMemberId);
            while (recentDeaths.size() > 2) {
                recentDeaths.poll();
            }
        }

        if (aliveSince.isEmpty()) {
            LOGGER.warn(
                    "hunger-games: all participants dead in game {} with no resolvable tiebreak "
                            + "(deaths not simultaneous) - treating as no winner",
                    gameId);
            return Optional.of(Outcome.noWinner());
        }

        return Optional.empty();
    }

    /** Announces that the last two participants share a team. */
    public void announceIfSameTeamFinalTwo(final World world) {
        final List<UUID> alive = List.copyOf(aliveSince.keySet());
        if (alive.size() != 2) {
            return;
        }
        final UUID firstTeam = teamOf.get(alive.get(0));
        if (firstTeam == null || !firstTeam.equals(teamOf.get(alive.get(1)))) {
            return;
        }
        for (final Player player : world.getPlayers()) {
            player.sendMessage(renderer.format(
                    identities.languageOf(player.getUniqueId()),
                    MESSAGES.hg().win().sameTeamFinalTwo()));
            sounds.play(player, Feedback.NETWORK_EVENT);
        }
    }

    /**
     * The end of a game.
     *
     * @param winnerMemberId the winner, {@code null} without a winner
     * @param tie whether the tiebreak decided the outcome
     * @param winnerKills the higher or shared kills of a tiebreak, otherwise zero
     * @param loserKills the lower kills of a tiebreak, otherwise zero
     */
    public record Outcome(@Nullable UUID winnerMemberId, boolean tie, int winnerKills, int loserKills) {

        /** One participant is alive. */
        static Outcome win(final UUID winnerMemberId) {
            return new Outcome(winnerMemberId, false, 0, 0);
        }

        /** The last two died together and one had more kills. */
        static Outcome tieBroken(final UUID winnerMemberId, final int winnerKills, final int loserKills) {
            return new Outcome(winnerMemberId, true, winnerKills, loserKills);
        }

        /** The last two died together with equal kills. */
        static Outcome tieNoWinner(final int kills) {
            return new Outcome(null, true, kills, kills);
        }

        /** Nobody is alive and no tiebreak applied. */
        static Outcome noWinner() {
            return new Outcome(null, false, 0, 0);
        }
    }
}

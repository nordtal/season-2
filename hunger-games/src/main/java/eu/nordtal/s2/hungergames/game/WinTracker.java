package eu.nordtal.s2.hungergames.game;

import static eu.nordtal.s2.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.player.Identities;
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
 * Tracks who is still alive and decides the game.
 *
 * Deaths within {@link #SIMULTANEOUS_WINDOW} go to {@link Tiebreak}; a same-team final two is only announced.
 */
public final class WinTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(WinTracker.class);

    /**
     * How close together two deaths count as "the same moment"; a strict same-tick check would be nearly unreachable.
     */
    private static final Duration SIMULTANEOUS_WINDOW = Duration.ofMillis(500);

    private final HungerGamesDao dao;
    private final Messages messages;
    private final Identities identities;
    private final HungerGamesSounds sounds;

    /** The living members of the current game; a member is removed on death. */
    private final java.util.Map<UUID, Instant> aliveSince = new ConcurrentHashMap<>();

    private final ConcurrentLinkedQueue<UUID> recentDeaths = new ConcurrentLinkedQueue<>();
    private volatile @Nullable Instant lastDeathAt;

    private final Clock clock;

    public WinTracker(
            final HungerGamesDao dao,
            final Messages messages,
            final Identities identities,
            final HungerGamesSounds sounds,
            final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.dao = dao;
        this.messages = messages;
        this.identities = identities;
        this.sounds = sounds;
    }

    public void reset(final List<HgMember> activeMembers) {
        aliveSince.clear();
        recentDeaths.clear();
        final Instant now = clock.instant();
        for (final HgMember member : activeMembers) {
            aliveSince.put(member.id(), now);
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
     * Records one member's death, and returns the outcome once the game has ended.
     *
     * @param gameId the running game
     * @param victimMemberId who died
     * @param killerMemberId who killed them, if anyone; border and environment deaths have none
     * @return the outcome, if the game just ended
     */
    public Optional<Outcome> recordDeath(
            final UUID gameId, final UUID victimMemberId, final @Nullable UUID killerMemberId) {
        aliveSince.remove(victimMemberId);
        if (killerMemberId != null) {
            dao.recordEvent(gameId, "KILL", killerMemberId, victimMemberId, null);
        }
        dao.recordEvent(gameId, "DEATH", null, victimMemberId, null);

        final Instant now = clock.instant();
        final boolean simultaneous =
                lastDeathAt != null && Duration.between(lastDeathAt, now).compareTo(SIMULTANEOUS_WINDOW) <= 0;
        lastDeathAt = now;

        if (aliveSince.size() == 1) {
            final UUID winner = aliveSince.keySet().iterator().next();
            return Optional.of(Outcome.win(winner));
        }

        if (aliveSince.isEmpty() && simultaneous) {
            // The last two died at the same moment; recentDeaths holds the two victim member ids in order.
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
                dao.recordEvent(
                        gameId, "TIE", null, null, winner.map(UUID::toString).orElse("no-winner"));
                // The kill counts travel with the outcome: the ceremony prints "3 kills to 2", not just "won".
                return Optional.of(winner.map(id -> Outcome.tieBroken(
                                id, Math.max(firstKills, secondKills), Math.min(firstKills, secondKills)))
                        // Equal by definition in this branch: Tiebreak returns empty only then.
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
            // Not a tie: nothing was compared, so the ceremony says "no winner", not a fabricated tie.
            return Optional.of(Outcome.noWinner());
        }

        return Optional.empty();
    }

    /** Announces a final two who share a team; the passive border shrink resolves the stalemate. */
    public void announceIfSameTeamFinalTwo(final World world, final List<HgMember> activeMembers) {
        if (aliveSince.size() != 2) {
            return;
        }
        final List<UUID> aliveIds = aliveSince.keySet().stream().toList();
        final Optional<HgMember> first = activeMembers.stream()
                .filter(m -> m.id().equals(aliveIds.get(0)))
                .findFirst();
        final Optional<HgMember> second = activeMembers.stream()
                .filter(m -> m.id().equals(aliveIds.get(1)))
                .findFirst();
        if (first.isEmpty() || second.isEmpty()) {
            return;
        }
        if (!first.get().teamId().equals(second.get().teamId())) {
            return;
        }
        for (final Player player : world.getPlayers()) {
            player.sendMessage(MessageRenderer.of(messages)
                    .format(
                            identities.languageOf(player.getUniqueId()),
                            MESSAGES.hg().win().sameTeamFinalTwo()));
            // NETWORK_EVENT: about two other people, who are the least likely to be reading chat.
            sounds.play(player, Feedback.NETWORK_EVENT);
        }
    }

    /**
     * The result of a game ending, in the four shapes the ceremony tells apart.
     *
     * @param winnerMemberId the winner, or {@code null} when the game ended without one
     * @param tie whether the tiebreaker decided this outcome, with or without a winner
     * @param winnerKills on a tiebreak, the higher or the shared kill count; zero otherwise
     * @param loserKills on a tiebreak, the lower kill count; zero otherwise
     */
    public record Outcome(@Nullable UUID winnerMemberId, boolean tie, int winnerKills, int loserKills) {

        /** The ordinary ending: one player left standing. */
        static Outcome win(final UUID winnerMemberId) {
            return new Outcome(winnerMemberId, false, 0, 0);
        }

        /** The last two died together and one had more kills. */
        static Outcome tieBroken(final UUID winnerMemberId, final int winnerKills, final int loserKills) {
            return new Outcome(winnerMemberId, true, winnerKills, loserKills);
        }

        /** The last two died together with the same number of kills, so nobody wins. */
        static Outcome tieNoWinner(final int kills) {
            return new Outcome(null, true, kills, kills);
        }

        /** Everybody is dead and no tiebreak applies, which should not happen. */
        static Outcome noWinner() {
            return new Outcome(null, false, 0, 0);
        }
    }
}

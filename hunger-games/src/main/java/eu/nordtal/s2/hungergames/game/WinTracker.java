package eu.nordtal.s2.hungergames.game;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;

import net.kyori.adventure.text.Component;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tracks who is still alive and decides the game:
 * <ul>
 *   <li>Last player standing wins.</li>
 *   <li>Two deaths within the same short window ("the same moment") are resolved by
 *       {@link Tiebreak} on total kills; equal kills means no winner.</li>
 *   <li>A same-team final two is announced rather than resolved automatically - friendly fire is
 *       on from the first second, so there is nothing to "unlock".</li>
 * </ul>
 *
 * <p>"The same moment" is a short wall-clock window ({@link #SIMULTANEOUS_WINDOW}), not literally
 * the same server tick - a strict same-tick check would make the tiebreaker nearly unreachable.</p>
 */
public final class WinTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(WinTracker.class);

    /** How close together two deaths have to be to count as "the same moment" for the tiebreaker. */
    private static final Duration SIMULTANEOUS_WINDOW = Duration.ofMillis(500);

    private final HungerGamesDao dao;
    private final Messages messages;
    private final PlayerLocales locales;
    private final HungerGamesSounds sounds;

    /** memberId -> alive, for the current game. Removed once dead. */
    private final java.util.Map<UUID, Instant> aliveSince = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<UUID> recentDeaths = new ConcurrentLinkedQueue<>();
    private volatile Instant lastDeathAt;

    public WinTracker(final HungerGamesDao dao, final Messages messages, final PlayerLocales locales,
                      final HungerGamesSounds sounds) {
        this.dao = dao;
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
    }

    public void reset(final List<HgMember> activeMembers) {
        aliveSince.clear();
        recentDeaths.clear();
        final Instant now = Instant.now();
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
     * Records one member's death. Returns the game's outcome once this death leaves at most one
     * player alive (or resolves a simultaneous-death pair); empty while the game continues.
     *
     * @param gameId          the running game
     * @param victimMemberId  who died
     * @param killerMemberId  who killed them, if anyone (border/environment deaths have none)
     * @return the outcome, if the game just ended
     */
    public Optional<Outcome> recordDeath(final UUID gameId, final UUID victimMemberId,
                                          final UUID killerMemberId) {
        aliveSince.remove(victimMemberId);
        if (killerMemberId != null) {
            dao.recordEvent(gameId, "KILL", killerMemberId, victimMemberId, null);
        }
        dao.recordEvent(gameId, "DEATH", null, victimMemberId, null);

        final Instant now = Instant.now();
        final boolean simultaneous = lastDeathAt != null
                && Duration.between(lastDeathAt, now).compareTo(SIMULTANEOUS_WINDOW) <= 0;
        lastDeathAt = now;

        if (aliveSince.size() == 1) {
            final UUID winner = aliveSince.keySet().iterator().next();
            return Optional.of(Outcome.win(winner));
        }

        if (aliveSince.isEmpty() && simultaneous) {
            // The survivor set is now empty, so this is the "last two die at the same moment"
            // case. recentDeaths holds the last two victim member ids in order.
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
                dao.recordEvent(gameId, "TIE", null, null,
                        winner.map(UUID::toString).orElse("no-winner"));
                // The kill counts travel with the outcome because the ceremony prints them:
                // "won on the tiebreaker, 3 kills to 2" is a different sentence from "won".
                return Optional.of(winner
                        .map(id -> Outcome.tieBroken(id, Math.max(firstKills, secondKills),
                                Math.min(firstKills, secondKills)))
                        // Equal by definition in this branch - Tiebreak returns empty only then -
                        // so either count is "the" count.
                        .orElseGet(() -> Outcome.tieNoWinner(firstKills)));
            }
        } else {
            recentDeaths.add(victimMemberId);
            while (recentDeaths.size() > 2) {
                recentDeaths.poll();
            }
        }

        if (aliveSince.isEmpty()) {
            LOGGER.warn("hunger-games: all participants dead in game {} with no resolvable tiebreak "
                    + "(deaths not simultaneous) - treating as no winner", gameId);
            // Not a tie: nothing was compared. The ceremony must say "no winner", not invent a
            // simultaneous death that did not happen.
            return Optional.of(Outcome.noWinner());
        }

        return Optional.empty();
    }

    /**
     * Announces a final two who share a team. Friendly fire is on from the first second, so there
     * is nothing to unlock - the passive border shrink is what resolves the stalemate.
     */
    public void announceIfSameTeamFinalTwo(final World world, final List<HgMember> activeMembers) {
        if (aliveSince.size() != 2) {
            return;
        }
        final List<UUID> aliveIds = aliveSince.keySet().stream().toList();
        final Optional<HgMember> first = activeMembers.stream().filter(m -> m.id().equals(aliveIds.get(0))).findFirst();
        final Optional<HgMember> second = activeMembers.stream().filter(m -> m.id().equals(aliveIds.get(1))).findFirst();
        if (first.isEmpty() || second.isEmpty()) {
            return;
        }
        if (!first.get().teamId().equals(second.get().teamId())) {
            return;
        }
        for (final Player player : world.getPlayers()) {
            player.sendMessage(MessageRenderer.of(messages).get(locales.of(player.getUniqueId()), "hg.win.same-team-final-two"));
            // NETWORK_EVENT: addressed to the whole server about two other people, once per game.
            // The two it is about are the least likely to be reading chat.
            sounds.play(player, Feedback.NETWORK_EVENT);
        }
    }

    /**
     * The result of a game ending, in the four shapes the ceremony has to be able to tell apart.
     *
     * <p>{@code tie} means the last two died within {@link #SIMULTANEOUS_WINDOW} of each other and
     * the kill counts decided it. It is not a synonym for "nobody won": a tiebreak can produce a
     * winner, and a game can end without one for a reason that is not a tiebreak at all.</p>
     *
     * @param winnerMemberId the winner, or {@code null} when the game ended without one
     * @param tie            whether the tiebreaker decided this outcome
     * @param winnerKills    on a tiebreak, the higher kill count - or the shared one when the
     *                       tiebreak found no winner. Zero on an ordinary win
     * @param loserKills     on a tiebreak, the lower kill count. Zero otherwise
     */
    public record Outcome(UUID winnerMemberId, boolean tie, int winnerKills, int loserKills) {

        /** The ordinary ending: one player left standing. */
        static Outcome win(final UUID winnerMemberId) {
            return new Outcome(winnerMemberId, false, 0, 0);
        }

        /** The last two died together and one had more kills. */
        static Outcome tieBroken(final UUID winnerMemberId, final int winnerKills, final int loserKills) {
            return new Outcome(winnerMemberId, true, winnerKills, loserKills);
        }

        /** The last two died together with the same number of kills - nobody wins. */
        static Outcome tieNoWinner(final int kills) {
            return new Outcome(null, true, kills, kills);
        }

        /** Everybody is dead and no tiebreak applies. Should not happen; see the warning above. */
        static Outcome noWinner() {
            return new Outcome(null, false, 0, 0);
        }
    }
}

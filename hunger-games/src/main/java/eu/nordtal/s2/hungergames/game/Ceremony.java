package eu.nordtal.s2.hungergames.game;

import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;

import net.kyori.adventure.text.Component;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The post-game ceremony: everyone back to the lobby, an evaluation of the result, the game marked
 * {@code DECIDED} - docs/hunger-games.md#after-the-game. This plugin does not switch the season
 * phase; that stays an explicit admin action elsewhere, per that same section.
 */
public final class Ceremony {

    private static final Logger LOGGER = LoggerFactory.getLogger(Ceremony.class);

    private final Messages messages;
    private final PlayerLocales locales;
    private final HungerGamesSounds sounds;

    public Ceremony(final Messages messages, final PlayerLocales locales,
                    final HungerGamesSounds sounds) {
        this.messages = messages;
        this.locales = locales;
        this.sounds = sounds;
    }

    /**
     * Everything the ceremony needs, all of it read <b>off the main thread</b> before it starts.
     *
     * <p>This record exists to make that guarantee structural rather than remembered. Until
     * 2026-09-04 the ceremony held the DAO and used it three ways on the server thread: one write to
     * decide the game, one read of the roster, and - the expensive one - a kill count per member
     * <em>inside a loop over every player</em>. The tally does not depend on who is being told, so
     * forty participants in front of forty players meant 1 600 identical blocking queries at the one
     * moment of the event when everybody is watching. `Ceremony` now has no DAO at all, which is the
     * only version of this rule that cannot quietly come undone.
     *
     * @param outcome      what {@code WinTracker} decided
     * @param winnerMcUuid the winner's Minecraft account, or {@code null} when nobody won or the
     *                     winner never linked one. The only thing that tells the one player who won
     *                     from the ones being told about it
     * @param members      every active membership, for the names on the tally
     * @param kills        member id to kill count, from one grouped query. Members with none are
     *                     absent
     */
    public record Decision(WinTracker.Outcome outcome, UUID winnerMcUuid,
                           List<HgMember> members, Map<UUID, Integer> kills) {
    }

    /**
     * Teleports everyone in the world back to the lobby and prints the evaluation to every player in
     * their own language.
     *
     * <p><b>Runs on the main thread and touches no database.</b> The game was already written as
     * decided by the caller, off the thread, along with everything in {@link Decision}.
     *
     * @param world    the event world everyone is currently standing in
     * @param lobby    the lobby teleport point
     * @param gameId   the game that just ended, for the log line
     * @param decision every fact this needs, read before it was called
     */
    public void run(final World world, final Location lobby, final UUID gameId,
                    final Decision decision) {
        // DELIBERATELY SILENT, although this is a teleport and TRAVEL exists for teleports. The
        // result lands in the same breath and is what everybody is waiting to hear; a chime for
        // being moved back to the lobby would arrive on top of it and say nothing.
        for (final Player player : world.getPlayers()) {
            player.teleportAsync(lobby);
        }

        for (final Player player : world.getPlayers()) {
            announce(player, decision);
        }
        LOGGER.info("hunger-games game {} decided - winner member id: {}", gameId,
                decision.outcome().winnerMemberId());
    }

    /**
     * One line for everybody and two sounds: {@code BIG_SUCCESS} for the winner, {@code
     * NETWORK_EVENT} for everybody else.
     *
     * <p>The same shape {@code smp}'s milestone announcement uses, and the same argument: a
     * congratulation everybody hears congratulates nobody. The four wordings stay four -
     * {@code WinOutcomeTest} is what keeps them that way - but they collapse into two sounds on
     * purpose, because "did I win" is the only question a chime can answer. A game with no winner
     * is a network event for everyone in the world, including the two who died together; they each
     * already heard {@code LOSS} at the moment it happened.
     */
    private void announce(final Player player, final Decision decision) {
        final WinTracker.Outcome outcome = decision.outcome();
        final List<HgMember> allMembers = decision.members();
        final Locale locale = locales.of(player.getUniqueId());
        player.sendMessage(MessageRenderer.of(messages).get(locale, "hg.ceremony.header"));

        // Four endings, four sentences - docs/hunger-games.md#winning. A tiebreak win printed as a
        // plain win is the one case where the players who watched it happen would be told something
        // they can see is not what occurred.
        if (outcome.winnerMemberId() != null && outcome.tie()) {
            player.sendMessage(MessageRenderer.of(messages).format(locale, "hg.win.tie-broken",
                    "winner", winnerLabel(outcome.winnerMemberId(), allMembers),
                    "winnerKills", outcome.winnerKills(), "loserKills", outcome.loserKills()));
        } else if (outcome.winnerMemberId() != null) {
            player.sendMessage(MessageRenderer.of(messages).format(locale, "hg.win.player",
                    "winner", winnerLabel(outcome.winnerMemberId(), allMembers)));
        } else if (outcome.tie()) {
            player.sendMessage(MessageRenderer.of(messages).format(locale, "hg.win.no-winner",
                    "kills", outcome.winnerKills()));
        } else {
            player.sendMessage(MessageRenderer.of(messages).get(locale, "hg.ceremony.no-winner"));
        }
        sounds.play(player, player.getUniqueId().equals(decision.winnerMcUuid())
                ? Feedback.BIG_SUCCESS : Feedback.NETWORK_EVENT);

        for (final HgMember member : allMembers) {
            final int kills = decision.kills().getOrDefault(member.id(), 0);
            if (kills > 0) {
                player.sendMessage(MessageRenderer.of(messages).format(locale, "hg.ceremony.kills",
                        "player", member.discordId(), "kills", kills));
            }
        }

        player.sendMessage(MessageRenderer.of(messages).get(locale, "hg.ceremony.footer"));
    }

    private String winnerLabel(final UUID winnerMemberId, final List<HgMember> allMembers) {
        return allMembers.stream().filter(member -> member.id().equals(winnerMemberId))
                .map(HgMember::discordId).findFirst().orElse(winnerMemberId.toString());
    }
}

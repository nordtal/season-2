package eu.nordtal.s2.hungergames.game;

import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.PlayerLocales;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.db.HungerGamesDao;

import net.kyori.adventure.text.Component;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The post-game ceremony: everyone back to the lobby, an evaluation of the result, the game marked
 * {@code DECIDED} - docs/hunger-games.md#after-the-game. This plugin does not switch the season
 * phase; that stays an explicit admin action elsewhere, per that same section.
 */
public final class Ceremony {

    private static final Logger LOGGER = LoggerFactory.getLogger(Ceremony.class);

    private final HungerGamesDao dao;
    private final Messages messages;
    private final PlayerLocales locales;

    public Ceremony(final HungerGamesDao dao, final Messages messages, final PlayerLocales locales) {
        this.dao = dao;
        this.messages = messages;
        this.locales = locales;
    }

    /**
     * Teleports everyone in the world back to the lobby, writes the game as decided, and prints the
     * evaluation to every player in their own language.
     *
     * @param world        the event world everyone is currently standing in
     * @param lobby        the lobby teleport point
     * @param gameId       the game that just ended
     * @param outcome      the result from {@link WinTracker}
     * @param allMembers   every active membership of the game, for the kill tally
     */
    public void run(final World world, final Location lobby, final UUID gameId, final WinTracker.Outcome outcome,
                    final List<HgMember> allMembers) {
        dao.decideGame(gameId, outcome.winnerMemberId());

        for (final Player player : world.getPlayers()) {
            player.teleportAsync(lobby);
        }

        for (final Player player : world.getPlayers()) {
            announce(player, outcome, allMembers, gameId);
        }
        LOGGER.info("hunger-games game {} decided - winner member id: {}", gameId, outcome.winnerMemberId());
    }

    private void announce(final Player player, final WinTracker.Outcome outcome, final List<HgMember> allMembers,
                          final UUID gameId) {
        final Locale locale = locales.of(player.getUniqueId());
        player.sendMessage(Component.text(messages.get(locale, "hg.ceremony.header")));

        if (outcome.winnerMemberId() != null) {
            player.sendMessage(Component.text(messages.format(locale, "hg.win.player",
                    "winner", winnerLabel(outcome.winnerMemberId(), allMembers))));
        } else {
            player.sendMessage(Component.text(messages.get(locale, "hg.ceremony.no-winner")));
        }

        for (final HgMember member : allMembers) {
            final int kills = dao.killCount(gameId, member.id());
            if (kills > 0) {
                player.sendMessage(Component.text(messages.format(locale, "hg.ceremony.kills",
                        "player", member.discordId(), "kills", kills)));
            }
        }

        player.sendMessage(Component.text(messages.get(locale, "hg.ceremony.footer")));
    }

    private String winnerLabel(final UUID winnerMemberId, final List<HgMember> allMembers) {
        return allMembers.stream().filter(member -> member.id().equals(winnerMemberId))
                .map(HgMember::discordId).findFirst().orElse(winnerMemberId.toString());
    }
}

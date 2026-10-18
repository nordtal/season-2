package eu.nordtal.season.hungergames.game;

import static eu.nordtal.season.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.PlayerContext;
import eu.nordtal.season.messages.feedback.Feedback;
import eu.nordtal.season.papercommon.player.Identities;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Teleports the players to the lobby and announces the result. */
public final class Ceremony {

    private static final Logger LOGGER = LoggerFactory.getLogger(Ceremony.class);

    private final MessageRenderer renderer;
    private final Identities identities;
    private final HungerGamesSounds sounds;

    public Ceremony(final MessageRenderer renderer, final Identities identities, final HungerGamesSounds sounds) {
        this.renderer = renderer;
        this.identities = identities;
        this.sounds = sounds;
    }

    /**
     * The data of the announcement.
     *
     * @param winnerMcUuid the Minecraft UUID of the winner, {@code null} without a winner
     * @param kills the kills by member id, without members that have none
     * @param names the player of each member id
     */
    public record Decision(
            WinTracker.Outcome outcome,
            @Nullable UUID winnerMcUuid,
            Map<UUID, Integer> kills,
            Map<UUID, PlayerContext> names) {}

    /** Teleports every player in the world to the lobby and announces the result in the language of the player. */
    public void run(final World world, final Location lobby, final UUID gameId, final Decision decision) {
        for (final Player player : world.getPlayers()) {
            final var _ = player.teleportAsync(lobby);
        }

        for (final Player player : world.getPlayers()) {
            announce(player, decision);
        }
        LOGGER.info(
                "hunger-games game {} decided - winner member id: {}",
                gameId,
                decision.outcome().winnerMemberId());
    }

    /** Sends the lines to the player. The winner gets {@code BIG_SUCCESS}, every other player {@code NETWORK_EVENT}. */
    private void announce(final Player player, final Decision decision) {
        final Locale locale = identities.languageOf(player.getUniqueId());
        for (final MessageRef line : lines(decision)) {
            player.sendMessage(renderer.format(locale, line));
        }
        sounds.play(
                player,
                player.getUniqueId().equals(decision.winnerMcUuid()) ? Feedback.BIG_SUCCESS : Feedback.NETWORK_EVENT);
    }

    /** Returns the lines of the announcement. */
    static List<MessageRef> lines(final Decision decision) {
        final WinTracker.Outcome outcome = decision.outcome();
        final List<MessageRef> lines = new ArrayList<>();
        lines.add(MESSAGES.hg().ceremony().header());

        if (outcome.winnerMemberId() != null && outcome.tie()) {
            lines.add(MESSAGES.hg()
                    .win()
                    .tieBroken(
                            player(decision, outcome.winnerMemberId()), outcome.winnerKills(), outcome.loserKills()));
        } else if (outcome.winnerMemberId() != null) {
            lines.add(MESSAGES.hg().win().player(player(decision, outcome.winnerMemberId())));
        } else if (outcome.tie()) {
            lines.add(MESSAGES.hg().win().noWinner(outcome.winnerKills()));
        } else {
            lines.add(MESSAGES.hg().ceremony().noWinner());
        }

        decision.names().forEach((member, named) -> {
            final int kills = decision.kills().getOrDefault(member, 0);
            if (kills > 0) {
                lines.add(MESSAGES.hg().ceremony().kills(named, kills));
            }
        });

        lines.add(MESSAGES.hg().ceremony().footer());
        return lines;
    }

    /** Returns the player of the member. */
    private static PlayerContext player(final Decision decision, final UUID memberId) {
        final PlayerContext named = decision.names().get(memberId);
        if (named != null) {
            return named;
        }
        return PlayerContext.of(PlayerId.of(memberId), "?");
    }
}

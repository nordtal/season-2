package eu.nordtal.s2.hungergames.game;

import static eu.nordtal.s2.hungergames.HungerGamesMessages.MESSAGES;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.hungergames.db.HgMember;
import eu.nordtal.s2.hungergames.feedback.HungerGamesSounds;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.context.PlayerContext;
import eu.nordtal.s2.messages.feedback.Feedback;
import eu.nordtal.s2.papercommon.player.Identities;
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

/** The post-game ceremony: everyone back to the lobby, the result announced, the game marked {@code DECIDED}. */
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
     * Everything the ceremony needs, read off the main thread before it starts, so {@code Ceremony} holds no DAO.
     *
     * @param outcome what {@code WinTracker} decided
     * @param winnerMcUuid the winner's Minecraft account, or {@code null} when there is none
     * @param members every active membership, for the names on the tally
     * @param kills member id to kill count; members with none are absent
     * @param names member id to Minecraft name, as last seen at login; a member never seen is absent
     */
    public record Decision(
            WinTracker.Outcome outcome,
            @Nullable UUID winnerMcUuid,
            List<HgMember> members,
            Map<UUID, Integer> kills,
            Map<UUID, PlayerContext> names) {}

    /**
     * Teleports everyone in the world back to the lobby and announces the result in each player's language.
     *
     * Main thread, no database: the caller already wrote the game as decided.
     */
    public void run(final World world, final Location lobby, final UUID gameId, final Decision decision) {
        for (final Player player : world.getPlayers()) {
            // Fire-and-forget: a failed teleport still gets the announcement, wherever the player stands.
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

    /** One line for everybody; {@code BIG_SUCCESS} for the winner and {@code NETWORK_EVENT} for everybody else. */
    private void announce(final Player player, final Decision decision) {
        final Locale locale = identities.languageOf(player.getUniqueId());
        for (final MessageRef line : lines(decision)) {
            player.sendMessage(renderer.format(locale, line));
        }
        sounds.play(
                player,
                player.getUniqueId().equals(decision.winnerMcUuid()) ? Feedback.BIG_SUCCESS : Feedback.NETWORK_EVENT);
    }

    /** The announcement, line by line, the same for every reader but their language. */
    static List<MessageRef> lines(final Decision decision) {
        final WinTracker.Outcome outcome = decision.outcome();
        final List<HgMember> allMembers = decision.members();
        final List<MessageRef> lines = new ArrayList<>();
        lines.add(MESSAGES.hg().ceremony().header());

        // Four endings, four sentences: a tiebreak printed as a plain win contradicts what players just saw.
        if (outcome.winnerMemberId() != null && outcome.tie()) {
            lines.add(MESSAGES.hg()
                    .win()
                    .tieBroken(
                            player(decision, outcome.winnerMemberId()), outcome.winnerKills(), outcome.loserKills()));
        } else if (outcome.winnerMemberId() != null) {
            // The one line with an icon; the glyph is a parameter since Glyphs names code points.
            lines.add(MESSAGES.hg().win().player(player(decision, outcome.winnerMemberId())));
        } else if (outcome.tie()) {
            lines.add(MESSAGES.hg().win().noWinner(outcome.winnerKills()));
        } else {
            lines.add(MESSAGES.hg().ceremony().noWinner());
        }

        for (final HgMember member : allMembers) {
            final int kills = decision.kills().getOrDefault(member.id(), 0);
            if (kills > 0) {
                lines.add(MESSAGES.hg().ceremony().kills(player(decision, member.id()), kills));
            }
        }

        lines.add(MESSAGES.hg().ceremony().footer());
        return lines;
    }

    /**
     * A member as the tab list names them.
     *
     * Only a member who never logged in has no name on record, and such a member cannot have played.
     */
    private static PlayerContext player(final Decision decision, final UUID memberId) {
        final PlayerContext named = decision.names().get(memberId);
        if (named != null) {
            return named;
        }
        // The member id stands in for an account nobody has, so no hover card and no reader is them.
        return PlayerContext.of(
                PlayerId.of(memberId),
                decision.members().stream()
                        .filter(member -> member.id().equals(memberId))
                        .map(HgMember::discordId)
                        .findFirst()
                        .orElse(DiscordId.of(memberId.toString()))
                        .value());
    }
}

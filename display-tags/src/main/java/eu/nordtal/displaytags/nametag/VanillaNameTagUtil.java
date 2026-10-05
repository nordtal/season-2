package eu.nordtal.displaytags.nametag;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import eu.nordtal.displaytags.PacketUtil;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Hides the vanilla player name tag from individual viewers.
 *
 * DisplayTags renders its own text display above the player's head. Without this, the client would
 * render both that display and the vanilla name tag, resulting in two names per player. The vanilla
 * name tag is suppressed by putting the target into a client-side scoreboard team whose name tag
 * visibility is {@code NEVER}.
 */
public final class VanillaNameTagUtil {
    private VanillaNameTagUtil() {}

    /**
     * Hides {@code target}'s vanilla name tag for a single viewer.
     *
     * @return {@code true} if a team packet was sent, {@code false} if TAB is handling the vanilla
     *         name tags and DisplayTags left the scoreboard alone
     */
    public static boolean hide(final Player target, final UUID viewerId) {
        if (TabUtil.managesNameTags()) return false;

        final WrapperPlayServerTeams.ScoreBoardTeamInfo teamInfo = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.empty(),
                Component.empty(),
                Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.NEVER,
                WrapperPlayServerTeams.CollisionRule.ALWAYS,
                null,
                WrapperPlayServerTeams.OptionData.NONE);

        PacketUtil.sendPacket(
                viewerId,
                new WrapperPlayServerTeams(
                        getTeamName(target), WrapperPlayServerTeams.TeamMode.CREATE, teamInfo, target.getName()));

        return true;
    }

    /**
     * Restores {@code target}'s vanilla name tag for a single viewer.
     *
     * @return {@code true} if a team packet was sent, {@code false} if TAB is handling the vanilla
     *         name tags and DisplayTags left the scoreboard alone
     */
    public static boolean show(final Player target, final UUID viewerId) {
        if (TabUtil.managesNameTags()) return false;

        PacketUtil.sendPacket(
                viewerId,
                new WrapperPlayServerTeams(
                        getTeamName(target),
                        WrapperPlayServerTeams.TeamMode.REMOVE,
                        (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                        target.getName()));

        return true;
    }

    /**
     * The client-side team name used for a player.
     *
     * Keyed by the player's UUID rather than their entity id: the entity id changes on respawn, which
     * would orphan the team on the client and let the vanilla name come back. The UUID is written
     * without its dashes, which keeps the name at 44 characters, far below the 32767 the team name
     * field allows.
     */
    private static String getTeamName(final Player target) {
        return "displaytags_" + target.getUniqueId().toString().replace("-", "");
    }
}

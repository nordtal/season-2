package eu.nordtal.season.displaytags.nametag;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import eu.nordtal.season.displaytags.PacketUtil;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Hides the vanilla name tags from individual viewers.
 * It uses a client-side scoreboard team whose name tag visibility is {@code NEVER}.
 */
public final class VanillaNameTagUtil {
    private VanillaNameTagUtil() {}

    /**
     * Hides the vanilla name tag from a viewer.
     * Returns {@code true} if a team packet was sent, {@code false} if TAB handles it.
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
     * Restores the vanilla name tag for a viewer.
     * Returns {@code true} if a team packet was sent, {@code false} if TAB handles it.
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

    /** The client-side team name of a player. It is keyed by UUID, because the entity id changes on respawn. */
    private static String getTeamName(final Player target) {
        return "displaytags_" + target.getUniqueId().toString().replace("-", "");
    }
}

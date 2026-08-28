package me.skyyiscool.displaytags.util;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Hides the vanilla player name tag from individual viewers.
 * <p>
 * DisplayTags renders its own text display above the player's head. Without this, the client would
 * render both that display and the vanilla name tag, resulting in two names per player. The vanilla
 * name tag is suppressed by putting the target into a client-side scoreboard team whose name tag
 * visibility is {@code NEVER}.
 */
public final class VanillaNameTagUtil {
    private VanillaNameTagUtil() {
    }

    /**
     * Hides {@code target}'s vanilla name tag for a single viewer.
     *
     * @return {@code true} if a team packet was sent, {@code false} if TAB is handling the vanilla
     *         name tags and DisplayTags left the scoreboard alone
     */
    public static boolean hide(Player target, UUID viewerId) {
        if (TabUtil.managesNameTags()) return false;

        WrapperPlayServerTeams.ScoreBoardTeamInfo teamInfo = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.empty(),
                Component.empty(),
                Component.empty(),
                WrapperPlayServerTeams.NameTagVisibility.NEVER,
                WrapperPlayServerTeams.CollisionRule.ALWAYS,
                null,
                WrapperPlayServerTeams.OptionData.NONE
        );

        PacketUtil.sendPacket(viewerId, new WrapperPlayServerTeams(
                getTeamName(target),
                WrapperPlayServerTeams.TeamMode.CREATE,
                teamInfo,
                target.getName()
        ));

        return true;
    }

    /**
     * Restores {@code target}'s vanilla name tag for a single viewer.
     *
     * @return {@code true} if a team packet was sent, {@code false} if TAB is handling the vanilla
     *         name tags and DisplayTags left the scoreboard alone
     */
    public static boolean show(Player target, UUID viewerId) {
        if (TabUtil.managesNameTags()) return false;

        PacketUtil.sendPacket(viewerId, new WrapperPlayServerTeams(
                getTeamName(target),
                WrapperPlayServerTeams.TeamMode.REMOVE,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                target.getName()
        ));

        return true;
    }

    private static String getTeamName(Player target) {
        return "displaytags_" + target.getEntityId();
    }
}

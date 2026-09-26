package eu.nordtal.s2.discordbot.hungergames;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of inviting a partner, from {@link Teams#invite(String, String)}.
 *
 * @param memberId null unless {@link #status()} is {@link Status#INVITED}
 * @param teamId null unless {@link #status()} is {@link Status#INVITED}
 * @param teamName null unless {@link #status()} is {@link Status#INVITED}
 */
public record InviteResult(
        Status status,
        @Nullable UUID memberId,
        @Nullable UUID teamId,
        @Nullable String teamName) {

    public static InviteResult invited(final UUID memberId, final UUID teamId, final String teamName) {
        return new InviteResult(Status.INVITED, memberId, teamId, teamName);
    }

    private static InviteResult of(final Status status) {
        return new InviteResult(status, null, null, null);
    }

    public static InviteResult notRegistered() {
        return of(Status.NOT_REGISTERED);
    }

    /** The inviter is on a team, but as an INVITED or DECLINED row, not as OWNER. */
    public static InviteResult notOwner() {
        return of(Status.NOT_OWNER);
    }

    /** One partner maximum. */
    public static InviteResult teamFull() {
        return of(Status.TEAM_FULL);
    }

    /** A previous invite on this team is still pending an answer. */
    public static InviteResult invitePending() {
        return of(Status.INVITE_PENDING);
    }

    public static InviteResult cannotInviteSelf() {
        return of(Status.CANNOT_INVITE_SELF);
    }

    /** The invited account is a bot, or is already OWNER/INVITED/ACCEPTED somewhere in this game. */
    public static InviteResult targetUnavailable() {
        return of(Status.TARGET_UNAVAILABLE);
    }

    public enum Status {
        INVITED,
        NOT_REGISTERED,
        NOT_OWNER,
        TEAM_FULL,
        INVITE_PENDING,
        CANNOT_INVITE_SELF,
        TARGET_UNAVAILABLE
    }
}

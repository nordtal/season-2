package eu.nordtal.season.discordbot.registration;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.discordbot.AccessMessages;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.TeamContext;
import java.util.Objects;

/**
 * What the registration flow says for each result of {@link Teams}, as functions of plain values.
 *
 * {@link RegisterFlow} only sends the message chosen here.
 */
final class RegisterReplies {

    private static final AccessMessages.Register REGISTER = MESSAGES.register();

    private RegisterReplies() {}

    /** The reply to a registration; {@code name} is the team name that was typed. */
    static MessageRef registration(final RegistrationResult result, final String name) {
        return switch (result.status()) {
            case REGISTERED -> REGISTER.success(name);
            case INVALID_NAME -> REGISTER.invalidName();
            case NAME_TAKEN -> REGISTER.nameTaken();
            case ALREADY_REGISTERED -> REGISTER.alreadyRegistered();
            case CLOSED -> REGISTER.closed();
        };
    }

    /** The reply to the owner who picked a partner. */
    static MessageRef invitation(final InviteResult result, final DiscordMemberContext partner) {
        final AccessMessages.Register.Invite invite = REGISTER.invite();
        return switch (result.status()) {
            case INVITED -> invite.sent(partner);
            case NOT_REGISTERED, NOT_OWNER -> invite.notOwner();
            case TEAM_FULL -> invite.teamFull();
            case INVITE_PENDING -> invite.pending();
            case CANNOT_INVITE_SELF -> invite.cannotInviteSelf();
            case TARGET_UNAVAILABLE -> invite.targetUnavailable();
            case CLOSED -> REGISTER.closed();
        };
    }

    /** The reply to the partner who answered; a closed round keeps the invite open, so it only says so. */
    static MessageRef answer(final AnswerResult result, final boolean accept) {
        return switch (result.status()) {
            case CLOSED -> REGISTER.closed();
            case NOT_PENDING -> REGISTER.invite().noLongerPending();
            case ANSWERED -> {
                final TeamContext team = new TeamContext(Objects.requireNonNull(result.teamName()));
                yield accept
                        ? REGISTER.invite().accepted(team)
                        : REGISTER.invite().declined(team);
            }
        };
    }

    /** The note to the team owner about the partner's answer. */
    static MessageRef ownerNote(final boolean accept, final DiscordMemberContext player, final TeamContext team) {
        return accept
                ? REGISTER.invite().ownerNotifiedAccepted(player, team)
                : REGISTER.invite().ownerNotifiedDeclined(player, team);
    }
}

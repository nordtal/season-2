package eu.nordtal.season.discordbot.registration;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;

import eu.nordtal.season.discordbot.AccessMessages;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.TeamContext;
import java.util.Objects;

/** Chooses the message for each result of {@link Teams}. {@link RegisterFlow} sends it. */
final class RegisterReplies {

    private static final AccessMessages.Register REGISTER = MESSAGES.register();

    private RegisterReplies() {}

    /** Returns the reply to a registration. {@code name} is the team name that the member typed. */
    static MessageRef registration(final RegistrationResult result, final String name) {
        return switch (result.status()) {
            case REGISTERED -> REGISTER.success(name);
            case INVALID_NAME -> REGISTER.invalidName();
            case NAME_TAKEN -> REGISTER.nameTaken();
            case ALREADY_REGISTERED -> REGISTER.alreadyRegistered();
            case CLOSED -> REGISTER.closed();
        };
    }

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

    /** Returns the reply to the partner who answered. A closed round keeps the invite open. */
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

    static MessageRef ownerNote(final boolean accept, final DiscordMemberContext player, final TeamContext team) {
        return accept
                ? REGISTER.invite().ownerNotifiedAccepted(player, team)
                : REGISTER.invite().ownerNotifiedDeclined(player, team);
    }
}

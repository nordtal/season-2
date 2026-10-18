package eu.nordtal.season.discordbot.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.discordrendering.DocumentRenderer.Reply;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.TeamContext;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisterRepliesTest {

    private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final DiscordMemberContext PARTNER =
            new DiscordMemberContext(DiscordId.of("300000000000000002"), "Fox");

    @Test
    void everyRefusedRegistrationHasItsOwnReply() {
        final Set<Reply<?>> replies = new HashSet<>();
        for (final RegistrationResult.Status status : RegistrationResult.Status.values()) {
            if (status != RegistrationResult.Status.REGISTERED) {
                replies.add(RegisterReplies.registration(new RegistrationResult(status, null)));
            }
        }

        assertEquals(RegistrationResult.Status.values().length - 1, replies.size());
    }

    @Test
    void aRefusedTeamIsToldItsReasonAndARegisteredOneIsAnsweredByItsButton() {
        assertEquals(
                Reply.of(Replies.REGISTER_NAME_TAKEN), RegisterReplies.registration(RegistrationResult.nameTaken()));
        assertEquals(
                Reply.of(Replies.REGISTER_INVALID_NAME),
                RegisterReplies.registration(RegistrationResult.invalidName()));
        assertEquals(Reply.of(Replies.REGISTER_CLOSED), RegisterReplies.registration(RegistrationResult.closed()));
        assertThrows(
                IllegalArgumentException.class, () -> RegisterReplies.registration(RegistrationResult.registered(ID)));
    }

    @Test
    void everyInvitationStatusHasAReplyAndOnlyNotRegisteredSharesOne() {
        final Set<Reply<?>> replies = new HashSet<>();
        for (final InviteResult.Status status : InviteResult.Status.values()) {
            replies.add(RegisterReplies.invitation(new InviteResult(status, null, null, null), PARTNER));
        }

        assertEquals(InviteResult.Status.values().length - 1, replies.size());
    }

    @Test
    void anInviterWhoIsNotTheOwnerIsToldSoWhetherOrNotTheyAreOnATeam() {
        final Reply<?> notOwner = Reply.of(Replies.INVITE_NOT_OWNER);

        assertEquals(notOwner, RegisterReplies.invitation(InviteResult.notRegistered(), PARTNER));
        assertEquals(notOwner, RegisterReplies.invitation(InviteResult.notOwner(), PARTNER));
    }

    @Test
    void aSentInviteNamesThePartner() {
        assertEquals(
                new Reply<>(Replies.INVITE_SENT, new Replies.Partner(PARTNER)),
                RegisterReplies.invitation(InviteResult.invited(ID, ID, "Red_Fox"), PARTNER));
    }

    @Test
    void aClosedRoundIsNamedTheSameWayInEveryStep() {
        final Reply<?> closed = Reply.of(Replies.REGISTER_CLOSED);

        assertEquals(closed, RegisterReplies.invitation(InviteResult.closed(), PARTNER));
        assertEquals(closed, RegisterReplies.answer(AnswerResult.closed(), true));
    }

    @Test
    void anAnswerToAnInviteThatIsGoneSaysSo() {
        assertEquals(
                Reply.of(Replies.INVITE_NO_LONGER_PENDING), RegisterReplies.answer(AnswerResult.notPending(), false));
    }

    @Test
    void anAcceptedAndADeclinedInviteAreToldApart() {
        final Replies.Team team = new Replies.Team(new TeamContext("Red_Fox"));

        assertEquals(
                new Reply<>(Replies.INVITE_ACCEPTED, team),
                RegisterReplies.answer(AnswerResult.answered(ID, "Red_Fox"), true));
        assertEquals(
                new Reply<>(Replies.INVITE_DECLINED, team),
                RegisterReplies.answer(AnswerResult.answered(ID, "Red_Fox"), false));
    }

    @Test
    void theOwnerHearsTheAnswerThePartnerGave() {
        final TeamContext team = new TeamContext("Red_Fox");
        final Replies.Answer answer = new Replies.Answer(PARTNER, team);

        assertEquals(
                new Reply<>(Replies.INVITE_OWNER_ACCEPTED, answer), RegisterReplies.ownerNote(true, PARTNER, team));
        assertEquals(
                new Reply<>(Replies.INVITE_OWNER_DECLINED, answer), RegisterReplies.ownerNote(false, PARTNER, team));
    }
}

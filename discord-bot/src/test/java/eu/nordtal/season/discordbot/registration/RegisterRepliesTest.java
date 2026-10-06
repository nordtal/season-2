package eu.nordtal.season.discordbot.registration;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.TeamContext;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the registration flow says for every result of {@link Teams}. */
class RegisterRepliesTest {

    private static final UUID ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final DiscordMemberContext PARTNER =
            new DiscordMemberContext(DiscordId.of("300000000000000002"), "Fox");

    @Test
    void everyRegistrationStatusHasItsOwnReply() {
        final Set<MessageRef> replies = new HashSet<>();
        for (final RegistrationResult.Status status : RegistrationResult.Status.values()) {
            replies.add(RegisterReplies.registration(new RegistrationResult(status, null), "Red_Fox"));
        }

        assertEquals(RegistrationResult.Status.values().length, replies.size());
    }

    @Test
    void aRegisteredTeamIsToldItsNameAndARefusedOneItsReason() {
        assertEquals(
                MESSAGES.register().success("Red_Fox"),
                RegisterReplies.registration(RegistrationResult.registered(ID), "Red_Fox"));
        assertEquals(
                MESSAGES.register().nameTaken(),
                RegisterReplies.registration(RegistrationResult.nameTaken(), "Red_Fox"));
        assertEquals(
                MESSAGES.register().invalidName(), RegisterReplies.registration(RegistrationResult.invalidName(), "x"));
        assertEquals(
                MESSAGES.register().closed(), RegisterReplies.registration(RegistrationResult.closed(), "Red_Fox"));
    }

    @Test
    void everyInvitationStatusHasAReplyAndOnlyNotRegisteredSharesOne() {
        final Set<MessageRef> replies = new HashSet<>();
        for (final InviteResult.Status status : InviteResult.Status.values()) {
            replies.add(RegisterReplies.invitation(new InviteResult(status, null, null, null), PARTNER));
        }

        assertEquals(InviteResult.Status.values().length - 1, replies.size());
    }

    @Test
    void anInviterWhoIsNotTheOwnerIsToldSoWhetherOrNotTheyAreOnATeam() {
        final MessageRef notOwner = MESSAGES.register().invite().notOwner();

        assertEquals(notOwner, RegisterReplies.invitation(InviteResult.notRegistered(), PARTNER));
        assertEquals(notOwner, RegisterReplies.invitation(InviteResult.notOwner(), PARTNER));
    }

    @Test
    void aSentInviteNamesThePartner() {
        assertEquals(
                MESSAGES.register().invite().sent(PARTNER),
                RegisterReplies.invitation(InviteResult.invited(ID, ID, "Red_Fox"), PARTNER));
    }

    @Test
    void aClosedRoundIsNamedTheSameWayInEveryStep() {
        assertEquals(MESSAGES.register().closed(), RegisterReplies.invitation(InviteResult.closed(), PARTNER));
        assertEquals(MESSAGES.register().closed(), RegisterReplies.answer(AnswerResult.closed(), true));
    }

    @Test
    void anAnswerToAnInviteThatIsGoneSaysSo() {
        assertEquals(
                MESSAGES.register().invite().noLongerPending(),
                RegisterReplies.answer(AnswerResult.notPending(), false));
    }

    @Test
    void anAcceptedAndADeclinedInviteAreToldApart() {
        final TeamContext team = new TeamContext("Red_Fox");

        assertEquals(
                MESSAGES.register().invite().accepted(team),
                RegisterReplies.answer(AnswerResult.answered(ID, "Red_Fox"), true));
        assertEquals(
                MESSAGES.register().invite().declined(team),
                RegisterReplies.answer(AnswerResult.answered(ID, "Red_Fox"), false));
    }

    @Test
    void theOwnerHearsTheAnswerThePartnerGave() {
        final TeamContext team = new TeamContext("Red_Fox");

        assertEquals(
                MESSAGES.register().invite().ownerNotifiedAccepted(PARTNER, team),
                RegisterReplies.ownerNote(true, PARTNER, team));
        assertEquals(
                MESSAGES.register().invite().ownerNotifiedDeclined(PARTNER, team),
                RegisterReplies.ownerNote(false, PARTNER, team));
    }
}

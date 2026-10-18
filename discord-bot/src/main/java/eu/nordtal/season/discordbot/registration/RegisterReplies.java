package eu.nordtal.season.discordbot.registration;

import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.TeamContext;
import eu.nordtal.season.messages.document.Sent;
import java.util.Objects;

/** Chooses the reply for each result of {@link Teams}. {@link RegisterFlow} sends it. */
final class RegisterReplies {

    private RegisterReplies() {}

    /**
     * Returns the reply to a registration that did not register.
     *
     * @throws IllegalArgumentException for a registration that registered, which has a document with a button
     */
    static Sent<?> registration(final RegistrationResult result) {
        return switch (result.status()) {
            case REGISTERED -> throw new IllegalArgumentException("a registered team is answered with its button");
            case INVALID_NAME -> Sent.of(Replies.REGISTER_INVALID_NAME);
            case NAME_TAKEN -> Sent.of(Replies.REGISTER_NAME_TAKEN);
            case ALREADY_REGISTERED -> Sent.of(Replies.REGISTER_ALREADY);
            case CLOSED -> Sent.of(Replies.REGISTER_CLOSED);
        };
    }

    static Sent<?> invitation(final InviteResult result, final DiscordMemberContext partner) {
        return switch (result.status()) {
            case INVITED -> new Sent<>(Replies.INVITE_SENT, new Replies.Partner(partner));
            case NOT_REGISTERED, NOT_OWNER -> Sent.of(Replies.INVITE_NOT_OWNER);
            case TEAM_FULL -> Sent.of(Replies.INVITE_TEAM_FULL);
            case INVITE_PENDING -> Sent.of(Replies.INVITE_PENDING);
            case CANNOT_INVITE_SELF -> Sent.of(Replies.INVITE_SELF);
            case TARGET_UNAVAILABLE -> Sent.of(Replies.INVITE_UNAVAILABLE);
            case CLOSED -> Sent.of(Replies.REGISTER_CLOSED);
        };
    }

    /** Returns the reply to the partner who answered. A closed round keeps the invite open. */
    static Sent<?> answer(final AnswerResult result, final boolean accept) {
        return switch (result.status()) {
            case CLOSED -> Sent.of(Replies.REGISTER_CLOSED);
            case NOT_PENDING -> Sent.of(Replies.INVITE_NO_LONGER_PENDING);
            case ANSWERED -> {
                final Replies.Team team = new Replies.Team(new TeamContext(Objects.requireNonNull(result.teamName())));
                yield new Sent<>(accept ? Replies.INVITE_ACCEPTED : Replies.INVITE_DECLINED, team);
            }
        };
    }

    static Sent<?> ownerNote(final boolean accept, final DiscordMemberContext player, final TeamContext team) {
        return new Sent<>(
                accept ? Replies.INVITE_OWNER_ACCEPTED : Replies.INVITE_OWNER_DECLINED,
                new Replies.Answer(player, team));
    }
}

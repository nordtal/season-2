package eu.nordtal.season.discordbot.registration;

import eu.nordtal.season.discordbot.DocumentRenderer.Reply;
import eu.nordtal.season.discordbot.Replies;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.TeamContext;
import java.util.Objects;

/** Chooses the reply for each result of {@link Teams}. {@link RegisterFlow} sends it. */
final class RegisterReplies {

    private RegisterReplies() {}

    /**
     * Returns the reply to a registration that did not register.
     *
     * @throws IllegalArgumentException for a registration that registered, which has a document with a button
     */
    static Reply<?> registration(final RegistrationResult result) {
        return switch (result.status()) {
            case REGISTERED -> throw new IllegalArgumentException("a registered team is answered with its button");
            case INVALID_NAME -> Reply.of(Replies.REGISTER_INVALID_NAME);
            case NAME_TAKEN -> Reply.of(Replies.REGISTER_NAME_TAKEN);
            case ALREADY_REGISTERED -> Reply.of(Replies.REGISTER_ALREADY);
            case CLOSED -> Reply.of(Replies.REGISTER_CLOSED);
        };
    }

    static Reply<?> invitation(final InviteResult result, final DiscordMemberContext partner) {
        return switch (result.status()) {
            case INVITED -> new Reply<>(Replies.INVITE_SENT, new Replies.Partner(partner));
            case NOT_REGISTERED, NOT_OWNER -> Reply.of(Replies.INVITE_NOT_OWNER);
            case TEAM_FULL -> Reply.of(Replies.INVITE_TEAM_FULL);
            case INVITE_PENDING -> Reply.of(Replies.INVITE_PENDING);
            case CANNOT_INVITE_SELF -> Reply.of(Replies.INVITE_SELF);
            case TARGET_UNAVAILABLE -> Reply.of(Replies.INVITE_UNAVAILABLE);
            case CLOSED -> Reply.of(Replies.REGISTER_CLOSED);
        };
    }

    /** Returns the reply to the partner who answered. A closed round keeps the invite open. */
    static Reply<?> answer(final AnswerResult result, final boolean accept) {
        return switch (result.status()) {
            case CLOSED -> Reply.of(Replies.REGISTER_CLOSED);
            case NOT_PENDING -> Reply.of(Replies.INVITE_NO_LONGER_PENDING);
            case ANSWERED -> {
                final Replies.Team team = new Replies.Team(new TeamContext(Objects.requireNonNull(result.teamName())));
                yield new Reply<>(accept ? Replies.INVITE_ACCEPTED : Replies.INVITE_DECLINED, team);
            }
        };
    }

    static Reply<?> ownerNote(final boolean accept, final DiscordMemberContext player, final TeamContext team) {
        return new Reply<>(
                accept ? Replies.INVITE_OWNER_ACCEPTED : Replies.INVITE_OWNER_DECLINED,
                new Replies.Answer(player, team));
    }
}

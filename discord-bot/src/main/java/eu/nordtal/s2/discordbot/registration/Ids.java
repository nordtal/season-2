package eu.nordtal.s2.discordbot.registration;

import eu.nordtal.s2.database.registration.Game;

/**
 * Every component id {@link RegisterFlow} listens for, in its game's own namespace so two games never share a button.
 *
 * @param register          the button on the managed Register message
 * @param registerModal     the team name modal {@code register} opens
 * @param registerNameInput the text input inside {@code registerModal}
 * @param invite            on the post-registration confirmation: opens the partner picker
 * @param inviteSelect      the user select menu {@code invite} opens
 * @param inviteAccept      prefix of the accept button on the invited partner's DM; the invite's
 *                          {@code team_member.id} follows, so the button outlives a bot restart
 * @param inviteDecline     the same for the decline button
 */
record Ids(
        String register,
        String registerModal,
        String registerNameInput,
        String invite,
        String inviteSelect,
        String inviteAccept,
        String inviteDecline) {

    /** Returns the ids of {@code game}'s flow, each under its key. */
    static Ids of(final Game game) {
        final String prefix = game.key() + ":";
        return new Ids(
                prefix + "register",
                prefix + "register-modal",
                prefix + "register-name",
                prefix + "invite",
                prefix + "invite-select",
                prefix + "invite-accept:",
                prefix + "invite-decline:");
    }
}

package eu.nordtal.season.discordbot.registration;

import eu.nordtal.season.database.registration.Game;

/**
 * The component ids that {@link RegisterFlow} handles, prefixed with the game key.
 *
 * @param register          the button on the Register message
 * @param registerModal     the team name modal that {@code register} opens
 * @param registerNameInput the text input of {@code registerModal}
 * @param invite            the button on the registration confirmation, which opens the partner picker
 * @param inviteSelect      the user select menu that {@code invite} opens
 * @param inviteAccept      the prefix of the accept button in the partner's direct message, followed by the invite's
 *                          {@code team_member.id}
 * @param inviteDecline     the same prefix for the decline button
 */
record Ids(
        String register,
        String registerModal,
        String registerNameInput,
        String invite,
        String inviteSelect,
        String inviteAccept,
        String inviteDecline) {

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

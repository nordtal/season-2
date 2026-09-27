package eu.nordtal.s2.discordbot.hungergames;

/**
 * Every component id {@link RegisterFlow} listens for, in its own {@code hg:} namespace.
 *
 * The invite buttons carry the {@code hg_member.id} of the invite, so they survive a bot restart.
 */
final class Ids {

    /** The button on the managed Register message. */
    static final String REGISTER = "hg:register";

    /** The team name modal {@link #REGISTER} opens. */
    static final String REGISTER_MODAL = "hg:register-modal";

    /** The text input inside {@link #REGISTER_MODAL}. */
    static final String REGISTER_NAME_INPUT = "hg:register-name";

    /** On the post-registration confirmation: opens the partner picker. */
    static final String INVITE = "hg:invite";

    /** The user select menu {@link #INVITE} opens. */
    static final String INVITE_SELECT = "hg:invite-select";

    /** Prefix of the accept button on the invited partner's DM; the invite's {@code hg_member.id} follows. */
    static final String INVITE_ACCEPT = "hg:invite-accept:";

    /** @see #INVITE_ACCEPT */
    static final String INVITE_DECLINE = "hg:invite-decline:";

    private Ids() {}
}

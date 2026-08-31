package eu.nordtal.s2.discordbot.hungergames;

/**
 * Every component id {@link RegisterFlow} listens for, prefixed {@code hg:} - its own namespace,
 * separate from {@code access:} ({@code eu.nordtal.s2.discordbot.discord.Ids}), because
 * registering for the start event and the paid access flow are unrelated features that happen to
 * share a bot.
 * <p>
 * {@link #INVITE_ACCEPT} and {@link #INVITE_DECLINE} are prefixes with the {@code hg_member.id}
 * (the INVITED row) appended - the DM the invited partner receives is the only place that id is
 * kept, so the button itself is the whole of the state that needs to survive a bot restart.
 * </p>
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

    private Ids() {
    }
}

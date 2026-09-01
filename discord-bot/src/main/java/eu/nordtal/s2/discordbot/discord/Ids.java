package eu.nordtal.s2.discordbot.discord;

/**
 * Every component id the bot listens for, in one place.
 * <p>
 * All of them are prefixed {@code access:} so a listener can tell at a glance whether an
 * interaction is its business, and so a second bot in the same guild cannot collide with these.
 * Two of them carry a value. {@link #DAYS_SELECT}'s is a number of days rather than the name of a
 * tier - the tiers come from configuration and have no stable identity, but the number of days a
 * user clicked on is exactly what has to be looked up again. {@link #PHASE_CONFIRM} is a prefix
 * with the phase appended, which is what makes the confirmation button mean one specific switch:
 * the message it sits on is the only place the chosen phase is kept between the command and the
 * click, so there is no per-user state to expire and a restart simply makes the old button do
 * nothing.
 * </p>
 */
public final class Ids {

    /** The button on the managed contribution message. */
    public static final String BUY = "access:buy";

    /** The select menu offering the tiers. Its values are day counts. */
    public static final String DAYS_SELECT = "access:days";

    /** Creates the bunq.me tab and shows the link. */
    public static final String CONFIRM = "access:confirm";

    /** Back to the select menu. */
    public static final String CHANGE = "access:change";

    /** Toggles the donation surcharge on the open request. */
    public static final String DONATION = "access:donation";

    /** The button on the managed link message. Opens {@link #LINK_MODAL}. */
    public static final String LINK = "access:link";

    /** The modal a code is typed into. */
    public static final String LINK_MODAL = "access:link-modal";

    /** The text input inside {@link #LINK_MODAL} carrying the code itself. */
    public static final String LINK_CODE_INPUT = "access:link-code";

    /**
     * Prefix of the button that actually performs a phase switch. The {@link
     * eu.nordtal.s2.common.SeasonPhase} name is appended, so nothing but this button can name a
     * phase and no phase can be switched to without one confirmed click.
     */
    public static final String PHASE_CONFIRM = "access:phase-confirm:";

    /** Backs out of a phase switch without touching the row. */
    public static final String PHASE_CANCEL = "access:phase-cancel";

    /**
     * Asks the updater to install what {@code /update} just reported.
     * <p>
     * Carries no value. Unlike the phase buttons there is nothing to remember between the command
     * and the click: the request row the updater answers is written when the button is pressed, and
     * "install whatever is newest right now" is what it means. A plan that has moved on since the
     * report was rendered is a plan the report will show again - the updater resolves afresh.
     * </p>
     */
    public static final String UPDATE_INSTALL = "access:update-install";

    /** Asks for the restart, which starts the countdown rather than restarting anything. */
    public static final String UPDATE_RESTART = "access:update-restart";

    /** Stops a countdown that is still running. */
    public static final String UPDATE_CANCEL = "access:update-cancel";

    private Ids() {
    }
}

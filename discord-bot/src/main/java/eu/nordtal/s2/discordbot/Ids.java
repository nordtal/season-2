package eu.nordtal.s2.discordbot;

/**
 * Every component id the bot listens for, all prefixed {@code access:}.
 *
 * Command confirmations use {@code nordtal:cmd:} ids from {@code DiscordCommands}, read by a different listener.
 */
public final class Ids {

    /** The button on the managed contribution message. */
    public static final String BUY = "access:buy";

    /** The select menu offering the tiers; its values are day counts. */
    public static final String DAYS_SELECT = "access:days";

    /** Creates the bunq.me tab and shows the link. */
    public static final String CONFIRM = "access:confirm";

    /** Back to the select menu. */
    public static final String CHANGE = "access:change";

    /** Toggles the donation surcharge on the open request. */
    public static final String DONATION = "access:donation";

    /** The button on the managed link message; opens {@link #LINK_MODAL}. */
    public static final String LINK = "access:link";

    /** The modal a code is typed into. */
    public static final String LINK_MODAL = "access:link-modal";

    /** The text input inside {@link #LINK_MODAL} carrying the code itself. */
    public static final String LINK_CODE_INPUT = "access:link-code";

    /** The buttons on the onboarding message, each followed by the tag of the language it speaks. */
    public static final String ONBOARD = "access:onboard:";

    /** The modal a language and a region are chosen in, followed by the tag of the language it speaks. */
    public static final String ONBOARD_MODAL = "access:onboard-modal:";

    /** The select menu inside {@link #ONBOARD_MODAL} whose values are language tags. */
    public static final String ONBOARD_LANGUAGE = "access:onboard-language";

    /** The select menu inside {@link #ONBOARD_MODAL} whose values are time zones. */
    public static final String ONBOARD_REGION = "access:onboard-region";

    private Ids() {}
}

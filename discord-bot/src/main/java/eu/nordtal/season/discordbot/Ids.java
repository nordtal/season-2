package eu.nordtal.season.discordbot;

/**
 * The component ids the bot listens for, all prefixed {@code access:}.
 * Command confirmations use {@code nordtal:cmd:} ids from {@code DiscordCommands}, which another listener reads.
 */
public final class Ids {

    public static final String BUY = "access:buy";

    /** The select of the tiers. Its values are day counts. */
    public static final String DAYS_SELECT = "access:days";

    public static final String CONFIRM = "access:confirm";

    public static final String CHANGE = "access:change";

    public static final String DONATION = "access:donation";

    public static final String LINK = "access:link";

    public static final String LINK_MODAL = "access:link-modal";

    public static final String LINK_CODE_INPUT = "access:link-code";

    /** Its values are language tags. */
    public static final String ONBOARD_PICK_LANGUAGE = "access:onboard-pick-language";

    /** Its values are time zones. The tag of the language follows the id. */
    public static final String ONBOARD_PICK_REGION = "access:onboard-pick-region:";

    /** The tag of the language follows the id. */
    public static final String ONBOARD = "access:onboard:";

    /** The tag of the language follows the id. */
    public static final String ONBOARD_MODAL = "access:onboard-modal:";

    /** Its values are language tags. */
    public static final String ONBOARD_LANGUAGE = "access:onboard-language";

    /** Its values are time zones. */
    public static final String ONBOARD_REGION = "access:onboard-region";

    private Ids() {}
}

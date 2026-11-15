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

    public static final String ONBOARD_COUNTRY = "access:onboard-country";
    public static final String ONBOARD_SETTINGS = "access:onboard-settings";
    public static final String ONBOARD_LANGUAGE = "access:onboard-language:";
    public static final String ONBOARD = "access:onboard:";

    private Ids() {}
}

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

    /** The country select of the choose channel. */
    public static final String ONBOARD_COUNTRY = "access:onboard-country";

    /** The Show settings button of the change channel, followed by the tag of the text it stands in. */
    public static final String ONBOARD_SETTINGS = "access:onboard-settings:";

    /** The components of the confirm dialog, followed by the field and the dialog's state. */
    public static final String ONBOARD_DRAFT = "access:onboard-draft:";

    /** The country select of the settings dialog, which saves at once. */
    public static final String ONBOARD_SET_COUNTRY = "access:onboard-set-country";

    /** The language select of the settings dialog, which saves at once. */
    public static final String ONBOARD_SET_LANGUAGE = "access:onboard-set-language";

    private Ids() {}
}

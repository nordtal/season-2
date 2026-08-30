package eu.nordtal.s2.accessbot.discord;

/**
 * Every component id the bot listens for, in one place.
 * <p>
 * All of them are prefixed {@code access:} so a listener can tell at a glance whether an
 * interaction is its business, and so a second bot in the same guild cannot collide with these.
 * Only {@link #DAYS_SELECT} carries a value, and that value is a number of days rather than the
 * name of a tier - the tiers come from configuration and have no stable identity, but the number
 * of days a user clicked on is exactly what has to be looked up again.
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

    private Ids() {
    }
}

package eu.nordtal.season.common.season;

/** Whether the season is open, derived from the launch date and never stored. */
public enum SeasonOpening {

    /** No launch date: members see the closed screen. */
    CLOSED,

    /** A launch date in the future: members see the countdown. */
    COUNTDOWN,

    /** The launch date has passed: the gate lets members through. */
    LAUNCHED
}

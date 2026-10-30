package eu.nordtal.season.database.setting;

/** The rule of a server's settings check that a setting broke, as a settings refusal names it. */
public enum SettingRule {

    /** The setting holds no text or no entry. */
    REQUIRED,

    /** The number is zero or below. */
    POSITIVE,

    /** The number is below zero. */
    NOT_NEGATIVE,

    /** The number is below the value. */
    AT_LEAST,

    /** The number is above the value. */
    AT_MOST,

    /** The number is not above the setting the value names. */
    ABOVE,

    /** The list holds another number of entries than the value. */
    ENTRIES,

    /** The list holds more entries than the value. */
    MOST_ENTRIES,

    /** A text is longer than the value in characters. */
    LONGEST,

    /** The value appears twice. */
    UNIQUE,

    /** The entries are not in ascending order. */
    ASCENDING,

    /** A box has a max corner below its min corner. */
    CORNERS,

    /** The value names nothing this server has. */
    KNOWN,

    /** The milestones do not make a track. */
    TRACK,

    /** The objective the value names asks for something this server does not have. */
    OBJECTIVE_NAMES,

    /** Any other rule, which only the server's log names. */
    VALID
}

package eu.nordtal.season.database.registration;

import java.util.Locale;

/**
 * A game that teams register for, by the key of its registration rows.
 * A second team mode is a constant here, not a copy of the bot's registration flow.
 */
public enum Game {

    /** The event of the Hunger Games server. */
    HUNGER_GAMES;

    /** Returns the key in {@code registration.game}: the lowercase name with hyphens. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}

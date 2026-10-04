package eu.nordtal.s2.database.registration;

import java.util.Locale;

/**
 * A game teams register for, by the key its registration rows carry.
 *
 * The bot's registration flow takes one, so a second team mode is a constant here, not a copy of the flow.
 */
public enum Game {

    /** The Hunger Games server's event. */
    HUNGER_GAMES;

    /** Returns the key {@code registration.game} holds: the constant's name in lowercase, hyphenated. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}

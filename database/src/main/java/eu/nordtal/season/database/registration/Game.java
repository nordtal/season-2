package eu.nordtal.season.database.registration;

import java.util.Locale;

/** A game that teams register for, by the key of its registration rows. */
public enum Game {
    HUNGER_GAMES;

    /** Returns the key in {@code registration.game}: the lowercase name with hyphens. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}

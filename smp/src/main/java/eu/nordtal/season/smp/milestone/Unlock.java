package eu.nordtal.season.smp.milestone;

import java.util.Optional;

/** What a milestone does when it unlocks. */
public enum Unlock {

    /** Sets the Nordtal world border to the {@code border-diameter} of the milestone. */
    BORDER,

    /** Opens the Nether. */
    NETHER,

    /** Opens the End. */
    END,

    /** Does nothing. */
    NOTHING;

    /** Parses a milestone file value, ignoring case, and returns empty for any other value. */
    public static Optional<Unlock> parse(final String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        for (final Unlock unlock : values()) {
            if (unlock.name().equalsIgnoreCase(name.trim())) {
                return Optional.of(unlock);
            }
        }
        return Optional.empty();
    }
}

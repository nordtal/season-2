package eu.nordtal.season.smp.milestone;

import java.util.Optional;

/**
 * What finishing a milestone hands the community.
 *
 * The Nether and the End carry no border step: the dimension is the reward.
 */
public enum Unlock {

    /** Moves the Nordtal world border to the milestone's {@code border-diameter}. */
    BORDER,

    /** Lights the Nether: Nordtal portals ignite and the balloon's entry stops being greyed out. */
    NETHER,

    /** Opens the End, which is entered by balloon; a stronghold's portal stays inactive. */
    END,

    /** Nothing at all; only the opening milestones {@code waiting} and {@code departure}. */
    NOTHING;

    /** Parses a milestone file value, empty for anything else. */
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

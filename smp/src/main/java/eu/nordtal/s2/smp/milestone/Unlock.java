package eu.nordtal.s2.smp.milestone;

import java.util.Optional;

/**
 * What finishing a milestone hands the community.
 *
 * <p>The Nether and the End are their own milestones and carry no border step: the dimension is the
 * reward, and pairing it with a border step would chain the two together.
 */
public enum Unlock {

    /** Moves the Nordtal world border to the milestone's {@code border-diameter}. */
    BORDER,

    /**
     * Lights the Nether: portals in Nordtal begin to ignite and the balloon's entry stops being
     * greyed out. Until then a portal does not ignite at all, so obsidian and a flint and steel
     * cannot walk past the milestone.
     */
    NETHER,

    /**
     * Opens the End, which is entered by balloon and never by portal - a stronghold's End portal
     * stays inactive for good.
     */
    END,

    /** Nothing at all. Only the two opening milestones, {@code waiting} and {@code departure}. */
    NOTHING;

    /**
     * @param name a value from the milestone file
     * @return the unlock, or empty for anything else
     */
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

package eu.nordtal.s2.smp.milestone;

import java.util.Optional;

/**
 * What finishing a milestone hands the community (docs/smp.md#the-track).
 *
 * <p>Three kinds, and the fact that there are three rather than one is a decision: <b>the Nether
 * and the End are their own milestones and carry no border step.</b> The dimension <em>is</em> the
 * reward and it is larger than any number; pairing it with a border step would chain the one to the
 * other and give the track fewer occasions to celebrate over more surface to block on.
 */
public enum Unlock {

    /** Moves the Nordtal world border to the milestone's {@code border-diameter}. */
    BORDER,

    /**
     * Lights the Nether: portals in Nordtal begin to ignite and the balloon's entry stops being
     * greyed out. Until then a portal does not ignite at all - without that gate, a player with
     * obsidian and a flint and steel walks straight past the milestone that is supposed to unlock
     * the Nether.
     */
    NETHER,

    /**
     * Opens the End, which is entered by balloon and never by portal - a stronghold's End portal
     * stays inactive for good.
     */
    END,

    /**
     * Nothing at all. The opening two milestones are the whole of this: {@code waiting} is where
     * the phase switch leaves the world and {@code departure} is opened by an admin, and each
     * carries a border of its own only because the border is what they set.
     */
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

package eu.nordtal.season.smp.world;

public enum WorldRole {

    /** The permanent build world. It holds the spawn, grows with milestones and is never regenerated. */
    NORDTAL,

    /** Reachable by Nether portal, after its milestone is unlocked. */
    NETHER,

    /** Reachable by End portal, after its milestone is unlocked. The vanilla exit portal leaves it after the dragon. */
    END;

    /** Whether a Nether portal lit in this world links the vanilla way. */
    public boolean hasVanillaPortalLinking() {
        return this == NORDTAL || this == NETHER;
    }
}

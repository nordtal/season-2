package eu.nordtal.season.smp.world;

import eu.nordtal.season.packrendering.Glyphs;

public enum WorldRole {

    /** The permanent build world. It holds the spawn, grows with milestones and is never regenerated. */
    NORDTAL(Glyphs.BOSSBAR_ICON_DIM_OVERWORLD),

    /** Reachable by balloon or portal, after its milestone is unlocked. */
    NETHER(Glyphs.BOSSBAR_ICON_DIM_NETHER),

    /** Reachable by balloon only. The vanilla exit portal leaves it, and only after the dragon. */
    END(Glyphs.BOSSBAR_ICON_DIM_END);

    private final String glyph;

    WorldRole(final String glyph) {
        this.glyph = glyph;
    }

    /** The bossbar-font icon of this dimension on HUD line 1. */
    public String glyph() {
        return glyph;
    }

    /** Whether a Nether portal lit in this world links the vanilla way. */
    public boolean hasVanillaPortalLinking() {
        return this == NORDTAL || this == NETHER;
    }
}

package eu.nordtal.season.smp.world;

import eu.nordtal.season.packrendering.Glyphs;

/** Which of the SMP's three worlds a world is, and what each one is allowed to do. */
public enum WorldRole {

    /** The permanent build world. Holds the spawn, grows with milestones, is never regenerated. */
    NORDTAL(Glyphs.BOSSBAR_ICON_DIM_OVERWORLD),

    /** Reached by balloon or by portal, both only once its milestone is unlocked. */
    NETHER(Glyphs.BOSSBAR_ICON_DIM_NETHER),

    /** Entered by balloon only; left through the vanilla exit portal, and only after the dragon. */
    END(Glyphs.BOSSBAR_ICON_DIM_END);

    private final String glyph;

    WorldRole(final String glyph) {
        this.glyph = glyph;
    }

    /** The bossbar-font icon for this dimension, drawn on HUD line 1. */
    public String glyph() {
        return glyph;
    }

    /** Whether a Nether portal lit in this world links the vanilla way: only between Nordtal and the Nether. */
    public boolean hasVanillaPortalLinking() {
        return this == NORDTAL || this == NETHER;
    }
}

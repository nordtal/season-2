package eu.nordtal.s2.smp.world;

import eu.nordtal.s2.common.Glyphs;

/**
 * Which of the SMP's four worlds a world is, and what each one is allowed to do.
 *
 * <p>The rules in docs/smp.md#travel are per role, not per name, so they live here rather than
 * being re-derived from a string comparison at every call site.
 */
public enum WorldRole {

    /** The permanent build world. Holds the spawn, grows with milestones, is never regenerated. */
    NORDTAL(Glyphs.BOSSBAR_ICON_DIM_OVERWORLD, true),

    /** Regenerated daily with a new seed. Nothing in it survives, and no portal network of its own. */
    FARM(Glyphs.BOSSBAR_ICON_DIM_FARM_WORLD, false),

    /** Reached by balloon or by portal, both only once its milestone is unlocked. */
    NETHER(Glyphs.BOSSBAR_ICON_DIM_NETHER, true),

    /** Entered by balloon only; left through the vanilla exit portal, and only after the dragon. */
    END(Glyphs.BOSSBAR_ICON_DIM_END, true);

    private final String glyph;
    private final boolean permanent;

    WorldRole(final String glyph, final boolean permanent) {
        this.glyph = glyph;
        this.permanent = permanent;
    }

    /** The bossbar-font icon for this dimension, drawn on HUD line 1. */
    public String glyph() {
        return glyph;
    }

    /** Whether anything built here outlives the night. False for the farm world alone. */
    public boolean permanent() {
        return permanent;
    }

    /**
     * Whether a Nether portal lit in this world links the vanilla way.
     *
     * <p>True only between Nordtal and the Nether. The farm world is thrown away every day and must
     * not become a permanent address, so every portal in it leads to the Nordtal spawn instead -
     * regardless of where it stands.
     */
    public boolean hasVanillaPortalLinking() {
        return this == NORDTAL || this == NETHER;
    }
}

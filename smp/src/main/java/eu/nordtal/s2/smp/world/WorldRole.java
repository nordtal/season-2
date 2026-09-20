package eu.nordtal.s2.smp.world;

import eu.nordtal.s2.common.Glyphs;

/**
 * Which of the SMP's three worlds a world is, and what each one is allowed to do.
 *
 * <p>The travel rules are per role, not per world name, so they live here rather than being
 * re-derived from a string comparison at every call site.
 *
 * <p>There were four until 2026-09-20. {@code FARM} was the farm world - regenerated nightly with a
 * new seed, nothing in it surviving - and it went with season-2-ingame/30. Nothing took its place:
 * resources come out of Nordtal now, which means the world is visibly mined out over a season and
 * that is the known and accepted cost. Its {@code permanent()} flag went too: it was false for the
 * farm world alone, so with it gone the question has one answer and is not a question.
 */
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

    /**
     * Whether a Nether portal lit in this world links the vanilla way.
     *
     * <p>True only between Nordtal and the Nether, which since 2026-09-20 is every overworld there
     * is. It stays a question rather than becoming {@code this != END} because the End is refused
     * for its own reason - it is left through the vanilla exit portal - and folding two reasons
     * into one expression loses both.
     */
    public boolean hasVanillaPortalLinking() {
        return this == NORDTAL || this == NETHER;
    }
}

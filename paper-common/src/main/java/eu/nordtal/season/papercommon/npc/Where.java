package eu.nordtal.season.papercommon.npc;

/** Where a player stands to the NPC: blocks beyond where they began, and degrees turned away from it. */
public record Where(double beyond, double angle) {

    /** Right where the dialogue began, facing the NPC. */
    public static final Where STILL = new Where(0, 0);
}

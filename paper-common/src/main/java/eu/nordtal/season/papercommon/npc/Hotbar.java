package eu.nordtal.season.papercommon.npc;

/**
 * How the hotbar moves the selected answer while answers show.
 * The hotbar is held on {@link #CHOICE_SLOT}: keys 1 to 3 land on slots 0 to 2, the wheel on the slot either side.
 */
public final class Hotbar {

    /** The slot the hotbar is held on while answers show. */
    public static final int CHOICE_SLOT = 4;

    private Hotbar() {}

    /** Returns the answer selected after the hotbar went to {@code slot}, out of {@code count} answers. */
    public static int select(final int selected, final int count, final int slot) {
        if (slot >= 0 && slot < count) {
            return slot;
        }
        if (slot == CHOICE_SLOT - 1) {
            return Math.max(0, selected - 1);
        }
        if (slot == CHOICE_SLOT + 1) {
            return Math.min(count - 1, selected + 1);
        }
        return selected;
    }
}

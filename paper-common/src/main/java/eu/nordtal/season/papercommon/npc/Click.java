package eu.nordtal.season.papercommon.npc;

import org.bukkit.event.block.Action;

/** What a click means to a running dialogue. */
public enum Click {
    RIGHT,
    LEFT,
    NONE;

    /** Returns the click an interaction {@code action} stands for; stepping on a plate is none. */
    public static Click of(final Action action) {
        return switch (action) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> RIGHT;
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> LEFT;
            case PHYSICAL -> NONE;
        };
    }
}

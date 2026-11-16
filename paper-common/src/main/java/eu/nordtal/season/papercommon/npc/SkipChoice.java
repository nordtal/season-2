package eu.nordtal.season.papercommon.npc;

import eu.nordtal.season.database.npc.Dialogue;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** Decides whether a right-click on an NPC plays its dialogue or opens the menu the player skips to. */
public final class SkipChoice {

    private SkipChoice() {}

    /** What a right-click on an NPC starts. */
    public enum Start {
        /** The dialogue, with the box unticked. */
        PLAY,
        /** The dialogue again, with the box ticked: Shift was held on a skipped NPC. */
        REPLAY,
        /** The skipped-to menu, without the dialogue. */
        OPEN_MENU
    }

    /** What the hand-off writes. */
    public enum Change {
        NONE,
        SKIP,
        UNSKIP
    }

    /** Returns the menus {@code dialogue} hands to. */
    public static Set<String> menus(final Dialogue dialogue) {
        return dialogue.steps().stream()
                .map(Dialogue.Step::menu)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns what a right-click starts for a player who skips to {@code skipped}, or to nothing.
     * A skip to a menu the dialogue no longer hands to is stale and plays the dialogue.
     */
    public static Start start(final @Nullable String skipped, final Dialogue dialogue, final boolean shift) {
        if (skipped == null || !menus(dialogue).contains(skipped)) {
            return Start.PLAY;
        }
        return shift ? Start.REPLAY : Start.OPEN_MENU;
    }

    /** Returns what the hand-off to {@code menu} writes, with the box {@code ticked} and an earlier skip. */
    public static Change handOff(final String menu, final boolean ticked, final @Nullable String skipped) {
        if (ticked) {
            return menu.equals(skipped) ? Change.NONE : Change.SKIP;
        }
        return skipped == null ? Change.NONE : Change.UNSKIP;
    }
}
